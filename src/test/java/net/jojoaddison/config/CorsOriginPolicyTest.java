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
 * <p><b>And the origin is necessary rather than sufficient.</b> The preflight carries
 * {@code Access-Control-Request-Headers: authorization}, so {@code allowed-headers} has to admit it
 * as well — a "tighten CORS" edit that narrows that one line breaks the stream on device with the
 * origin still present. Both preconditions are held here, and each reddens its own test, so a
 * failure says which half went.</p>
 *
 * <p>Backlog item 67, which item 39 cycle 3 was blocked on. <b>Production is deliberately not
 * covered here</b> — it has no CORS block at all and the architect deferred it; that is item 77.</p>
 *
 * <p>⚠ <b>That deferral number is unguarded, and it has already been wrong once.</b>
 * {@link #theOriginListSaysWhyThePortlessEntryIsThere} pins {@code item 67} and not it, so neither a
 * missing nor a renumbered deferral item reddens anything here. It was written as {@code item 75} when
 * 75 was the next free number, and unrelated work took 75 and 76 before this branch merged — a
 * citation to an unfiled item is a claim about the future, and the future moved. Re-read it against
 * {@code docs/backlog.md} rather than trusting the suite.</p>
 *
 * <p>The idiom — read the file, assert on what it binds — is {@code GatewayRoutePolicyTest}'s and
 * {@code PatientEventFunctionDefinitionTest}'s in this repository.</p>
 */
class CorsOriginPolicyTest {

    /** Relative to the module directory, which is surefire's working directory. */
    private static final Path APPLICATION_DEV_YML = Path.of("src", "main", "resources", "config", "application-dev.yml");

    /**
     * The webview origin on a handset, <b>read off the shipped artefact and the bridge source rather
     * than off the file that authors them</b>.
     *
     * <p>{@code mobile/android/app/build/intermediates/assets/release/mergeReleaseAssets/capacitor.config.json}
     * — the merged config inside the release build — carries {@code "server": {"androidScheme":
     * "https"}} with <b>no {@code hostname} and no {@code url}</b>. In
     * {@code @capacitor/android@8.5.0}, {@code CapConfig.java:38} defaults that hostname to
     * {@code localhost}, and {@code Bridge.java:625} composes the origin as
     * {@code scheme + "://" + authority} — appending no port, and taking the {@code server.url}
     * override branch below it only when that key is present, which it is not. So the origin is
     * {@code https://localhost}, portless, by construction rather than by convention.</p>
     */
    private static final String WEBVIEW_ORIGIN = "https://localhost";

    /** {@code ionic serve} on a workstation — the entry {@link #WEBVIEW_ORIGIN} is a prefix of. */
    private static final String IONIC_SERVE_ORIGIN = "https://localhost:8100";

    /** The header the stream sends, and therefore the one its preflight asks permission for. */
    private static final String AUTHORIZATION_HEADER = "Authorization";

    /** {@code CorsConfiguration.ALL} — what {@code checkHeaders} treats as "admit anything". */
    private static final String WILDCARD = "*";

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
     * <b>The second precondition: {@code allowed-headers} must admit {@code Authorization}.</b>
     *
     * <p>The stream sends a bearer token, which is what forces the preflight in the first place, and
     * the preflight therefore carries {@code Access-Control-Request-Headers: authorization}. An
     * allowed origin with that header refused is still a refused request — so narrowing this one line
     * breaks the device while {@link #theCapacitorWebviewOriginIsAllowed} stays green. Measured on the
     * quality gateway, the passing preflight echoed {@code access-control-allow-headers: authorization}
     * beside the origin, so both halves are visibly part of the same answer.</p>
     *
     * <p>Mirrors {@code CorsConfiguration.checkHeaders} rather than insisting on the wildcard:
     * {@code allowedHeaders.contains("*")} admits anything, and otherwise each requested header is
     * matched with {@code equalsIgnoreCase}. So an explicit {@code 'Authorization,Content-Type'} is a
     * legitimate tightening and passes here, which is the point — this pins the <em>capability</em>,
     * not the current value.</p>
     *
     * <p><b>{@code allowed-methods} is deliberately NOT asserted, and the asymmetry is the argument.</b>
     * Read from {@code spring-web:7.0.7}: {@code setAllowedMethods} falls back to
     * {@code DEFAULT_METHODS = [GET, HEAD]} when the property is empty or absent
     * ({@code CorsConfiguration:305}), so deleting that line leaves this <em>GET</em> stream working.
     * {@code checkHeaders} has no such fallback — {@code if (ObjectUtils.isEmpty(this.allowedHeaders))
     * return null} at {@code CorsConfiguration:731} refuses the preflight outright. One absence is
     * fatal and the other is not, so guarding both would pin a value whose loss costs nothing and
     * leave two tests where one rule lives — the shape item 39 cycle 2 recorded, where a redundant
     * filter hid which one was load-bearing.</p>
     */
    @Test
    void theAllowedHeadersAdmitTheAuthorizationHeaderThePreflightAsksFor() {
        List<String> headers = corsList("allowed-headers");
        boolean admitsAuthorization =
            headers.contains(WILDCARD) || headers.stream().anyMatch(header -> header.equalsIgnoreCase(AUTHORIZATION_HEADER));

        assertThat(admitsAuthorization)
            .as(
                "jhipster.cors.allowed-headers is %s, which does not admit %s. The membership stream's " +
                "preflight carries Access-Control-Request-Headers: authorization and is refused without " +
                "it, whatever the origin list says — so the device gets no frame even though " +
                "theCapacitorWebviewOriginIsAllowed is green. Use '*' or name the header. See backlog item 67.",
                headers,
                AUTHORIZATION_HEADER
            )
            .isTrue();
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
        return corsList("allowed-origins");
    }

    /**
     * One comma-separated {@code jhipster.cors} property, split into whole values.
     *
     * <p>Split rather than matched as text because the values are prefixes of one another:
     * {@code https://localhost} is a prefix of {@code https://localhost:8100}, so a substring search
     * for the first passes with only the second present.</p>
     */
    private static List<String> corsList(String key) {
        Object value = path(APPLICATION_DEV_YML, "jhipster", "cors").get(key);
        if (value == null) {
            return List.of();
        }
        return List.of(String.valueOf(value).split(",")).stream().map(String::trim).filter(entry -> !entry.isEmpty()).toList();
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
