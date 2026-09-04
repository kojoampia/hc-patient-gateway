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

    /** The predicate the professionalservice route must carry, and the one it must not. */
    private static final String NARROW_PREDICATE = "Path=/services/professionalservice/api/duty-roster/customer/**";
    private static final String WIDE_PREDICATE = "Path=/services/professionalservice/**";

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
     * <b>{@code /services/**} stays {@code .authenticated()} — and the control that makes that safe
     * is the route predicate, not this rule.</b>
     *
     * <p>hc-professional tightened its own equivalent rule to a list of authorities on 2026-09-03,
     * and copying that here would break the thing the cross-stack route was added for: the caller is
     * a patient, holding {@code ROLE_USER} and — where hc-patient issues it — {@code ROLE_PATIENT},
     * and neither is a clinical authority. That much this test has always said, and it is still
     * right.
     *
     * <p><b>What it said and got wrong was that those were the only two options.</b> It presented
     * "tighten the matcher" against "leave it open" and concluded the second, which reads as though
     * nothing else could be done — so it pinned the matcher open while leaving the actual exposure
     * unexamined for a day. There is a third option and it is the one in force: <b>narrow the route
     * predicate</b>, so the only path that reaches hc-professional at all is the one the feature
     * needs. Everything else 404s at this gateway, before authorization is consulted.
     *
     * <p>The exposure was not hypothetical. With the predicate written
     * {@code Path=/services/professionalservice/**}, this gateway's {@code .authenticated()} and
     * hc-professional's own {@code /api/** -> authenticated()} were the whole of the check, and
     * {@code ProfileResource.getAllProfiles} carries no {@code @PreAuthorize} and no caller scoping.
     * Any hc-patient account could read the clinician staff directory — {@code birth_date},
     * {@code mobile_phone}, {@code card_number}, {@code address}, {@code emergency_contact} — and
     * {@code /api/profiles/email/{email}} answered as an existence oracle on a clinician's address.
     * The predicate is now
     * {@code Path=/services/professionalservice/api/duty-roster/customer/**}.
     *
     * <p><b>Why hc-admin may do what this gateway may not.</b> hc-admin's gateway carries
     * whole-service prefixes and that is fine there: everybody holding an account on it is an
     * administrator or an operator, so a wide predicate exposes staff data to staff. Every account
     * on this gateway belongs to a member of the public. The same predicate is a different decision
     * on the two gateways because the caller population is different, and that — not the shape of
     * the string — is what has to be argued before this one is widened.
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
                "403-never-an-empty-list check. Narrowing here breaks the portal — narrow the ROUTE " +
                "PREDICATE in the compose files instead, which is what actually keeps the rest of that " +
                "service out of reach. See this method's javadoc."
            )
            .contains(".pathMatchers(\"/services/**\").authenticated()");
    }

    /**
     * <b>The compensating control has to be written down where the matcher is left open.</b>
     *
     * <p>Leaving {@code /services/**} at {@code .authenticated()} is only defensible alongside a
     * route predicate that names an endpoint. The predicates themselves live in
     * {@code deploy/prod-server/compose.yml} and {@code quality/compose.yml} — sibling repositories,
     * absent from this module's CI checkout — so no test here can read the value in force. Two
     * things can be held, and both are:
     *
     * <ul>
     *   <li><b>Here:</b> that {@code application.yml}, the file a person editing routing opens,
     *       states the rule and names the narrow predicate verbatim. A copy-paste widening usually
     *       starts by reading this block.
     *   <li><b>In {@code hc-patient/quality/startup.sh --verify}:</b> the executable copy. It signs
     *       in as a real patient and requires {@code /services/professionalservice/api/profiles} to
     *       404 <em>and</em> the body to contain no {@code card_number}. That check fails the moment
     *       the predicate is widened back, in either compose file, and it is the only thing that
     *       does.
     * </ul>
     *
     * <p>Documentary rather than behavioural, and named as such — but the alternative was a
     * cross-repository file read that would pass vacuously in CI, which is the failure mode
     * hc-admin's pagination sweep is the standing lesson about.
     */
    @Test
    void theRoutingBlockRecordsThatACrossStackRouteNamesAnEndpointRatherThanAService() {
        String yml = read(APPLICATION_YML);

        assertThat(yml)
            .as("application.yml must carry the endpoint-not-service rule beside the routes block")
            .contains("A CROSS-STACK ROUTE ON THIS GATEWAY NAMES AN ENDPOINT, NOT A SERVICE");
        assertThat(yml)
            .as("...and name the narrow predicate in force, so a widening is visible as a diff against it")
            .contains(NARROW_PREDICATE);
        // The wide form does appear in this file — the note names it, because a rule that will not
        // say what it forbids is not a rule. What must not appear is a line that IS it: something a
        // reader can copy out whole and paste into a compose file. A line ending in the wide
        // predicate is that; the same string mid-sentence is not.
        List<String> copyableWideDeclarations = read(APPLICATION_YML)
            .lines()
            .map(line -> line.replaceFirst("^\\s*#?\\s*", "").trim())
            .filter(line -> line.equals(WIDE_PREDICATE) || line.endsWith(" " + WIDE_PREDICATE))
            .toList();

        assertThat(copyableWideDeclarations)
            .as(
                "The whole-service predicate must not stand in this file as a line a reader can copy. " +
                "Keep it inside the sentence that explains what it exposed, or drop it — but do not " +
                "leave it looking like the declaration to use."
            )
            .isEmpty();
    }

    /**
     * The rule is worth nothing if the reason is not beside it.
     *
     * <p>Narrow predicates read as fussiness once the incident is forgotten, and the next person
     * widens one to make a second endpoint work. What stops that is the sentence explaining that
     * this gateway's account holders are patients and hc-admin's are administrators — so the file
     * has to say why hc-admin is allowed to do the thing this one is not.
     */
    @Test
    void theRoutingBlockSaysWhyAWholeServicePrefixIsAcceptableOnHcAdminAndNotHere() {
        String yml = read(APPLICATION_YML);

        assertThat(yml).as("the caller population is the argument — say so, or the narrowing looks arbitrary").contains("administrators");
        assertThat(yml).as("...and name what the wide predicate actually exposed").contains("staff directory");
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
