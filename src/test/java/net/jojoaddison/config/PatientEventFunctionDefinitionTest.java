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

    /** Item 45's producer. Kept as a literal rather than read from {@code EntityEventPublisher.BINDING}: this test's
     * job is to catch the two drifting apart, and sourcing both sides from one constant cannot. */
    private static final String ENTITY_BINDING = "entityEvents-out-0";

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

    /**
     * That the entity-change producer points at {@code patient.event} in <b>both</b> files — backlog item 45.
     *
     * <h2>⛔ Why this cannot live in {@code EntityEventBindingIT}, which is where it looks like it belongs</h2>
     *
     * <p>Because an {@code @IntegrationTest} injecting {@code BindingServiceProperties} reads a context built
     * <em>only</em> from {@code src/test/resources/config/application.yml}. It cannot see the main file at all.
     * <strong>Measured 2026-09-25: deleting the whole {@code entityEvents-out-0} block from the MAIN file leaves the
     * entire suite green</strong>, that IT included, 4/4 and BUILD SUCCESS — while production publishes to a topic
     * nobody reads.</p>
     *
     * <p>And it is silent on every layer, which is why it needs a test rather than care. {@code StreamBridge} invents a
     * destination named after the binding for a binding with no {@code destination}, so {@code send()} returns
     * <b>true</b> and {@code EntityEventPublisher}'s {@code if (!sent)} warning never fires. Frames land on a topic
     * called {@code entityEvents-out-0}, {@code patient.event} stays empty, and hc-admin's erasure fact is missing
     * again — the exact defect item 45 exists to fix. It is the api's item 32 shape, and this repository's own
     * deleted-scaffold {@code /publish} endpoint did the same thing for as long as it existed.</p>
     */
    @Test
    void theEntityChangeProducerPointsAtPatientEventInBothFiles() {
        for (Path file : List.of(MAIN, TEST)) {
            assertThat(binding(file, ENTITY_BINDING))
                .as("%s: the entity-change stream's binding. A destination in one file only is a production-only topic", file)
                .containsEntry("destination", "patient.event");
        }
    }

    /**
     * That the key configuration is in both files too — the half that fails at send time rather than at startup.
     *
     * <p>{@code messageKeyExpression} yields a String and the binder's default key serializer is
     * {@code ByteArraySerializer}; the mismatch throws when a frame is sent, not when the context starts. In this
     * publisher that is doubly buried — the send is swallowed by design and happens on a thread of its own — so a
     * serializer configured in the test file alone would lose every frame in production with a green suite. This
     * repository has already paid for that once on the binding next door.</p>
     */
    @Test
    void theEntityChangeProducerIsKeyedAndSerializedInBothFiles() {
        for (Path file : List.of(MAIN, TEST)) {
            Map<String, Object> producer = child(kafkaBinding(file, ENTITY_BINDING), "producer");

            assertThat(String.valueOf(producer.get("messageKeyExpression")))
                .as("%s: one document's changes must share a partition, or a create and a delete can be audited out of order", file)
                .contains("entityKey");
            assertThat(child(producer, "configuration"))
                .as("%s: a String key needs StringSerializer, or every frame is lost at send time", file)
                .containsEntry("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        }
    }

    /** A parser that silently matches nothing passes for ever. */
    @Test
    void theSweepFindsWhatItIsChecking() {
        assertThat(names(MAIN)).isNotEmpty();
        assertThat(names(TEST)).isNotEmpty();
        assertThat(binding(MAIN, BINDING)).isNotEmpty();
        assertThat(binding(TEST, BINDING)).isNotEmpty();
        // Item 45's two additions need their own positive control: `binding` and `kafkaBinding` walk different key
        // paths, and a typo in either would return an empty map that `containsEntry` reports as a missing destination
        // rather than as a broken reader.
        assertThat(binding(MAIN, ENTITY_BINDING)).isNotEmpty();
        assertThat(binding(TEST, ENTITY_BINDING)).isNotEmpty();
        assertThat(kafkaBinding(MAIN, ENTITY_BINDING)).isNotEmpty();
        assertThat(kafkaBinding(TEST, ENTITY_BINDING)).isNotEmpty();
    }

    /** {@code spring.cloud.stream.bindings.<name>} */
    private static Map<String, Object> binding(Path file, String name) {
        return child(path(file, "spring", "cloud", "stream", "bindings"), name);
    }

    /**
     * {@code spring.cloud.stream.kafka.bindings.<name>} — a different subtree from {@link #binding}.
     *
     * <p>The destination lives under {@code stream.bindings}; the key expression and serializer live under
     * {@code stream.kafka.bindings}. Two paths, both required, and a binding configured in one and not the other is
     * the failure this file exists for.</p>
     */
    private static Map<String, Object> kafkaBinding(Path file, String name) {
        return child(path(file, "spring", "cloud", "stream", "kafka", "bindings"), name);
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
     *
     * <p><b>It fails when two documents carry the same path rather than taking either.</b> Spring
     * merges multi-document YAML with the <em>later</em> document winning; a walk that returns the
     * first match has the opposite precedence, so a second occurrence would defeat this whole test
     * in both directions at once. Measured, not reasoned: appending a document reading
     * {@code spring.cloud.function.definition: kafkaConsumer} to the main file left all four tests
     * here <em>green</em>, including the one whose only job is to catch that name. A test written
     * against the replace-rather-than-merge YAML trap must not itself be defeatable by a YAML edit
     * one {@code ---} away. A legitimate second occurrence — a profile-gated definition, say — is
     * precisely the moment somebody should re-derive what this reads, so it is a red test and not a
     * silent choice.</p>
     */
    private static Map<String, Object> path(Path file, String... keys) {
        assertThat(file).as("run from the module directory: %s", file.toAbsolutePath()).isRegularFile();
        List<Map<String, Object>> carried = new ArrayList<>();
        for (Object document : documents(file)) {
            Map<String, Object> node = asMap(document);
            for (String key : keys) {
                node = child(node, key);
            }
            if (!node.isEmpty()) {
                carried.add(node);
            }
        }
        assertThat(carried)
            .as(
                "%s: more than one YAML document carries %s; the later one wins at runtime and this test reads the first",
                file,
                String.join(".", keys)
            )
            .hasSizeLessThan(2);
        return carried.isEmpty() ? Map.of() : carried.get(0);
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
