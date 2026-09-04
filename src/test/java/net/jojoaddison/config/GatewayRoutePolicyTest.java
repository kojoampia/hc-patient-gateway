package net.jojoaddison.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Two rules this gateway's routing rests on, held over the source rather than over a request.
 *
 * <p>Both arrived with hc-patient's <b>first</b> cross-stack route on 2026-09-04 —
 * {@code /services/professionalservice/**}, for the patient day plan, which lives in
 * {@code deploy/prod-server/compose.yml} and {@code quality/compose.yml} because production routes
 * statically. Neither rule is expressible as a test that sends a request, which is why they are
 * read out of the files instead:
 *
 * <ul>
 *   <li>A route <b>declared in a compose file</b> cannot be exercised from this repository at all.
 *       What can be checked here is the shape of anything declared <em>in</em> this repository, and
 *       the comment beside it that tells the next person which file to use.
 *   <li>A matcher's <b>absence</b> is invisible to a request-based test whenever a lower rule
 *       decides the same way — the lesson hc-admin wrote down when eight of its
 *       {@code GatewayAuthorizationIT} cases turned out to pass with the rule they were written for
 *       deleted.
 * </ul>
 *
 * <p>The idiom — read the file, assert on its text — is {@code BrandTermsTest}'s in hc-admin's
 * gateway and {@code global-styles.spec.ts}'s in its console.
 */
class GatewayRoutePolicyTest {

    /** Relative to the module directory, which is surefire's working directory. */
    private static final Path APPLICATION_YML = Path.of("src", "main", "resources", "config", "application.yml");
    private static final Path SECURITY_CONFIGURATION = Path.of(
        "src",
        "main",
        "java",
        "net",
        "jojoaddison",
        "config",
        "SecurityConfiguration.java"
    );

    /** A {@code - Path=…} predicate inside the routes list. */
    private static final Pattern PATH_PREDICATE = Pattern.compile("^\\s*-\\s*Path=(\\S+)\\s*$", Pattern.MULTILINE);

    /**
     * The one route in this file that is not {@code /services/**}-shaped, and why.
     *
     * <p>{@code abofonsa-plans} proxies another product's price list at {@code /api/plans}. It is a
     * narrow literal path, not a prefix, and it reaches a marketing site rather than a service
     * holding anybody's records — so it is an exception in form and not in kind. Named here so that
     * a <em>second</em> such route has to be argued for rather than merely added.
     */
    private static final List<String> ALLOWED_API_PATHS = List.of("/api/plans");

    /**
     * <b>No route declared here may take a prefix of this gateway's own {@code /api} surface.</b>
     *
     * <p>The sharpest sentence in the roster migration's plan, and the one worth mechanising:
     * a cross-stack route written as {@code /api/**} swallows {@code /api/account},
     * {@code /api/authenticate} and {@code /api/users} and proxies <em>authentication itself</em> to
     * another stack. The symptom is sign-in breaking for everyone, and the cause is one character.
     *
     * <p>Prefixes are what this refuses; a single literal path that names no service is allowed by
     * {@link #ALLOWED_API_PATHS}, which has exactly one entry and is meant to keep having one.
     */
    @Test
    void noRouteDeclaredHereTakesAPrefixOfThisGatewaysOwnApiSurface() {
        List<String> offenders = new ArrayList<>();
        for (String path : declaredPaths()) {
            boolean ownSurface = path.startsWith("/api");
            if (ownSurface && !ALLOWED_API_PATHS.contains(path)) {
                offenders.add(path);
            }
        }

        assertThat(offenders)
            .as(
                "A route on this gateway's own /api surface proxies authentication to another stack. " +
                "Match on /services/<service>/** instead. If a literal /api path is genuinely right, " +
                "add it to ALLOWED_API_PATHS with the argument for it."
            )
            .isEmpty();
    }

    /** A parser that silently matches nothing passes for ever. */
    @Test
    void theSweepFindsTheRoutesItIsChecking() {
        assertThat(declaredPaths()).as("Path= predicates in %s", APPLICATION_YML).isNotEmpty().contains("/api/plans");
    }

    /**
     * The file has to say that declaring a route here does not put one in production.
     *
     * <p>Spring Boot does not merge collections across property sources, so the indexed
     * {@code SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_ROUTES_*} variables in both compose files replace
     * this list entire. Measured on the running quality stack, where the admin route table is one
     * entry and {@code /api/plans} answers 404. Without the note, the next person adds a route here,
     * sees it work under {@code ./mvnw}, and ships nothing.
     */
    @Test
    void theRoutesBlockWarnsThatAnEnvironmentReplacesItRatherThanExtendingIt() {
        String yml = read(APPLICATION_YML);

        assertThat(yml).as("the routes block must say it is replaced, not extended").contains("REPLACED, NOT EXTENDED");
        assertThat(yml).as("...and name the variable that does it").contains("SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_ROUTES_");
        assertThat(yml)
            .as("...and repeat the /api/** rule where a route is written")
            .contains("NEVER DECLARE A CROSS-STACK ROUTE ON /api/**");
    }

    /**
     * <b>{@code /services/**} stays {@code .authenticated()}, and the patient day plan depends on
     * it.</b>
     *
     * <p>hc-professional tightened its own equivalent rule to a list of authorities on 2026-09-03,
     * and copying that here would break the thing this route was added for: the caller is a patient,
     * holding {@code ROLE_USER} and — where hc-patient issues it — {@code ROLE_PATIENT}, and neither
     * is a clinical authority. The real boundary is the far endpoint's, which refuses anybody who is
     * not that customer with a 403 an unknown id gets identically. A rule here would be a second,
     * weaker copy of it.
     *
     * <p>Asserted structurally because no request can see the difference: with the rule tightened,
     * a patient token gets 403 and so does a caller with no route at all, and the screen shows an
     * empty day either way.
     */
    @Test
    void theServicesRuleIsAuthenticatedRatherThanAListOfAuthorities() {
        String security = read(SECURITY_CONFIGURATION);

        assertThat(security)
            .as(
                "/services/** must stay .authenticated(). The patient day plan is reached by a patient " +
                "token holding no clinical authority, and its real boundary is hc-professional's own " +
                "403-never-an-empty-list check. Narrowing here breaks the portal and buys nothing."
            )
            .contains(".pathMatchers(\"/services/**\").authenticated()");
    }

    private static List<String> declaredPaths() {
        List<String> paths = new ArrayList<>();
        Matcher matcher = PATH_PREDICATE.matcher(read(APPLICATION_YML));
        while (matcher.find()) {
            paths.add(matcher.group(1));
        }
        return paths;
    }

    private static String read(Path path) {
        assertThat(path).as("run from the module directory: %s", path.toAbsolutePath()).isRegularFile();
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + path, e);
        }
    }
}
