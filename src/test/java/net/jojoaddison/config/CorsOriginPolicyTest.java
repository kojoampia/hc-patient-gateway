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
 * That the Capacitor webview's own origin stays in {@code jhipster.cors.allowed-origins}, held over
 * the file rather than over a preflight.
 *
 * <h2>Why a file test and not a request test</h2>
 *
 * <p>The quality stack runs {@code SPRING_PROFILES_ACTIVE: dev,test} and sets no
 * {@code JHIPSTER_CORS_*} variable, so {@code application-dev.yml}'s list <b>is</b> that
 * environment's CORS policy, and {@code WebConfigurer} registers it on {@code /services/*&#47;api/**}
 * — which covers the membership stream. Nothing in this repository can see that list. <b>No test
 * here activates the {@code dev} profile</b>, and {@code src/test/resources/config/} carries no
 * {@code application-dev.yml} to mirror it into.</p>
 *
 * <p>A test <em>could</em> be made to activate {@code dev} and send a real {@code OPTIONS}, and it
 * would be the wrong instrument. {@code src/test/resources/config/application.yml} is the same
 * classpath resource as the main one and <b>replaces it wholesale rather than merging</b>, so such a
 * test would bind the dev CORS block on top of a base configuration no deployment runs — asserting
 * against a stack that exists only in this repository, which is the defect backlog items 32 and 57
 * record. The only honest proof is a preflight against a deployed gateway, and it is not a test:</p>
 *
 * <pre>
 * OPTIONS /services/hcpatientservice/api/membership-events
 *   Origin: https://localhost         Access-Control-Request-Headers: authorization  -&gt; 403 before
 *   Origin: https://localhost:8100    (positive control, same run)                   -&gt; 200
 * </pre>
 *
 * <p>Measured against {@code hc-patient-quality-gateway} on 2026-09-24 and again on 2026-09-25. The
 * control is what makes the 403 mean something: an allowed origin gets an explicit
 * {@code Access-Control-Allow-Origin} from the same endpoint in the same run, so the refusal is the
 * policy answering rather than a dead route.</p>
 *
 * <h2>What this catches that a reader would not</h2>
 *
 * <p>{@code https://localhost} is portless and looks like a truncated copy of the
 * {@code https://localhost:8100} beside it. Tidying it away is a one-character edit that fails
 * nothing, and the symptom is a handset that never receives a frame while every desktop browser,
 * every health check and every test stays green.</p>
 *
 * <p>Backlog item 67, which item 39 cycle 3 was blocked on. <b>Production is deliberately not
 * covered here</b> — it has no CORS block at all and the architect deferred it; that is item 75.</p>
 *
 * <p>The idiom — read the file, assert on what it binds — is {@code GatewayRoutePolicyTest}'s and
 * {@code PatientEventFunctionDefinitionTest}'s in this repository.</p>
 */
class CorsOriginPolicyTest {

    /** Relative to the module directory, which is surefire's working directory. */
    private static final Path APPLICATION_DEV_YML = Path.of("src", "main", "resources", "config", "application-dev.yml");

    /**
     * The webview origin on a handset: {@code androidScheme: 'https'} plus Capacitor's default
     * hostname, and <b>no port</b> (`mobile/capacitor.config.ts`).
     */
    private static final String WEBVIEW_ORIGIN = "https://localhost";

    /** {@code ionic serve} on a workstation — the entry {@link #WEBVIEW_ORIGIN} is a prefix of. */
    private static final String IONIC_SERVE_ORIGIN = "https://localhost:8100";

    @Test
    void theCapacitorWebviewOriginIsAllowed() {
        // Compared as a whole list element, never as a substring of the file: WEBVIEW_ORIGIN is a
        // prefix of IONIC_SERVE_ORIGIN, so a text search for it passes with the entry deleted.
        assertThat(allowedOrigins())
            .as(
                "%s is the Capacitor webview's own origin and the membership stream is refused at preflight " +
                "without it — the app reaches everything else through CapacitorHttp, which never preflights, " +
                "but the stream must use window.CapacitorWebFetch and its Authorization header forces one. " +
                "Portless is correct; it is not a truncated copy of %s. See backlog item 67.",
                WEBVIEW_ORIGIN,
                IONIC_SERVE_ORIGIN
            )
            .contains(WEBVIEW_ORIGIN);
    }

    /**
     * The reason has to be beside the value, or the next person tidies it away.
     *
     * <p>Held because this list is generated boilerplate that nobody re-derives: the one entry in it
     * that is not a workstation dev server has to say so where it is written.</p>
     */
    @Test
    void theOriginListSaysWhyThePortlessEntryIsThere() {
        String yml = read(APPLICATION_DEV_YML);

        assertThat(yml).as("name what the portless origin is").contains("Capacitor webview");
        assertThat(yml).as("...and why only one request needs it").contains("CapacitorWebFetch");
        assertThat(yml).as("...and where the argument lives").contains("item 67");
    }

    /** A parser that silently matches nothing passes for ever. */
    @Test
    void theSweepReadsTheListItIsChecking() {
        List<String> origins = allowedOrigins();

        assertThat(origins).as("jhipster.cors.allowed-origins in %s", APPLICATION_DEV_YML).isNotEmpty().contains(IONIC_SERVE_ORIGIN);
        assertThat(IONIC_SERVE_ORIGIN)
            .as("the substring trap this test parses around must still be in the list, or the parsing is untested")
            .startsWith(WEBVIEW_ORIGIN)
            .isNotEqualTo(WEBVIEW_ORIGIN);
    }

    /** {@code jhipster.cors.allowed-origins}, split into whole origins. */
    private static List<String> allowedOrigins() {
        Object origins = path(APPLICATION_DEV_YML, "jhipster", "cors").get("allowed-origins");
        if (origins == null) {
            return List.of();
        }
        return List.of(String.valueOf(origins).split(",")).stream().map(String::trim).filter(origin -> !origin.isEmpty()).toList();
    }

    /**
     * Walks a key path through whichever YAML document in the file carries it.
     *
     * <p>It fails when two documents carry the same path rather than taking either: Spring merges
     * multi-document YAML with the <em>later</em> document winning, so a walk returning the first
     * match has the opposite precedence and would be defeated by an edit one {@code ---} away — the
     * hazard backlog item 69 found in the api's binding pin. {@code application-dev.yml} is a single
     * document today, which is exactly when the guard is cheap to add.</p>
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

    private static String read(Path file) {
        assertThat(file).as("run from the module directory: %s", file.toAbsolutePath()).isRegularFile();
        try {
            return Files.readString(file);
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
