package net.jojoaddison.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * That {@code spring.cloud.function.definition} still names the mail router, in <b>both</b> YAML files.
 *
 * <h2>Why a file test and not a binding test</h2>
 *
 * <p>Backlog item 61 deleted the JHipster Kafka scaffold — {@code PatientGatewayKafkaResource},
 * {@code broker/KafkaConsumer}, {@code broker/KafkaProducer} — and with it two of the three names the
 * definition used to carry. The survivor, {@code patientEventsConsumer}, is
 * {@code PatientEventMailRouter}'s binding and it sends real mail: the care-delegation and erasure
 * notices. Dropping it from the definition fails nothing a running context can show —
 * {@code PatientEventConsumerBindingIT} asserts the <em>bean</em> exists and the <em>binding
 * properties</em> are configured, and both of those stay true with the name gone from the list, while
 * Spring Cloud Stream simply never binds the function and every one of those mails silently stops.</p>
 *
 * <p>And an IT can only see the test configuration. {@code src/test/resources/config/application.yml}
 * is the <em>same classpath resource</em> as the main one and replaces it wholesale rather than
 * merging — the trap that has already cost this repository three defects with a green suite. A name
 * dropped from the main file alone is dropped for production and for nothing any test can see, which
 * is why this test reads both files off disk instead of asking a context that only ever loads one of
 * them. The same idiom as {@code GatewayRoutePolicyTest} here and {@code MembershipStreamBindingTest}
 * in the api.</p>
 */
class PatientEventFunctionDefinitionTest {

    /** Relative to the module directory, which is surefire's working directory. */
    private static final Path MAIN = Path.of("src", "main", "resources", "config", "application.yml");
    private static final Path TEST = Path.of("src", "test", "resources", "config", "application.yml");

    private static final String MAIL_ROUTER = "patientEventsConsumer";
    private static final String BINDING = "patientEventsConsumer-in-0";

    @Test
    void theMailRouterIsNamedInBothFilesOrItNeverBinds() {
        // The @Bean method name is the function name, and this property is the only thing that binds
        // it. A definition without the name starts cleanly, serves every request, and sends no mail.
        // Asserted over the definition split into names, not as a substring — a substring match would
        // stay green under a rename to `patientEventsConsumerV2`, which is exactly the silent drop
        // this test exists to catch.
        assertThat(names(MAIN)).as("main application.yml must keep the mail router bound").contains(MAIL_ROUTER);
        assertThat(names(TEST))
            .as("the test resource replaces the main one wholesale; it must carry the same definition")
            .contains(MAIL_ROUTER);
    }

    @Test
    void theScaffoldNamesStayDeleted() {
        // `kafkaConsumer` and `kafkaProducer` were the generated scaffold, deleted by item 61. A name
        // in this list with no @Bean behind it does NOT fail the context — measured on the quality
        // api on 2026-09-24, which ran healthy for five days with two deleted names in its
        // definition, logging one WARN per name at startup and serving throughout. So both halves of
        // a scaffold revival are silent: the names alone warn where nobody reads, and a regenerated
        // scaffold would bring the classes back and bind them. This is what makes either one a red
        // test instead of a silent revival of a once-a-second publisher and an unfiltered fan-out.
        for (Path file : List.of(MAIN, TEST)) {
            assertThat(names(file))
                .as("%s must not name the deleted scaffold functions", file)
                .doesNotContain("kafkaConsumer", "kafkaProducer");
        }
    }

    @Test
    void theBindingAgreesWithTheDefinitionInBothFiles() {
        // The definition names the function; the binding points it at the topic and joins the group.
        // Either half alone is silence: a bound name with no destination consumes nothing, and the
        // group is what stops every running instance sending its own copy of each mail.
        for (Path file : List.of(MAIN, TEST)) {
            assertThat(binding(file, BINDING))
                .as("%s: the mail router's binding", file)
                .containsEntry("destination", "patient-events")
                .containsEntry("group", "patient-gateway");
        }
    }

    /** A parser that silently matches nothing passes for ever. */
    @Test
    void theSweepFindsWhatItIsChecking() {
        assertThat(names(MAIN)).isNotEmpty();
        assertThat(names(TEST)).isNotEmpty();
        assertThat(binding(MAIN, BINDING)).isNotEmpty();
        assertThat(binding(TEST, BINDING)).isNotEmpty();
    }

    /** {@code spring.cloud.stream.bindings.<name>} */
    private static Map<String, Object> binding(Path file, String name) {
        return child(path(file, "spring", "cloud", "stream", "bindings"), name);
    }

    /** The definition split on {@code ;} into whole function names. */
    private static List<String> names(Path file) {
        Object definition = path(file, "spring", "cloud", "function").get("definition");
        if (definition == null) {
            return List.of();
        }
        return List.of(String.valueOf(definition).split(";")).stream().map(String::trim).filter(name -> !name.isEmpty()).toList();
    }

    /**
     * Walks a key path through whichever YAML document in the file carries it.
     *
     * <p>{@code application.yml} is several documents separated by {@code ---}, so a single
     * {@code load} sees only the first and would report every key missing.</p>
     */
    private static Map<String, Object> path(Path file, String... keys) {
        assertThat(file).as("run from the module directory: %s", file.toAbsolutePath()).isRegularFile();
        for (Object document : documents(file)) {
            Map<String, Object> node = asMap(document);
            for (String key : keys) {
                node = child(node, key);
            }
            if (!node.isEmpty()) {
                return node;
            }
        }
        return Map.of();
    }

    private static Iterable<Object> documents(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            // Materialised inside the try: loadAll is lazy and would read from a closed stream otherwise.
            List<Object> all = new ArrayList<>();
            new Yaml().loadAll(in).forEach(all::add);
            return all;
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + file, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static Map<String, Object> child(Map<String, Object> node, String key) {
        return asMap(node.get(key));
    }
}
