package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.User;
import net.jojoaddison.repository.UserRepository;
import net.jojoaddison.service.LoginAttemptService;
import net.jojoaddison.web.rest.vm.LoginVM;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Every sign-in outcome, driven through {@code /api/authenticate} rather than by calling the meters directly.
 *
 * <h2>Why this exists at all, when {@code LoginMetersServiceTests} already covers the counters</h2>
 *
 * <p>That test proves the meters increment when told to. It cannot prove that anything ever tells them to, and the
 * gap between those two is where this class of defect lives. Each test here makes one cause actually happen and
 * watches its own series move — a dashboard built on a counter nothing reaches reports a calm, confident zero.</p>
 *
 * <h2>The one that is really a structural assertion</h2>
 *
 * <p>{@link #aRefusedAttemptAgainstALockedAccountIsCounted} is written so that it cannot pass if the
 * {@code account-locked} increment is ever "simplified" into {@code LoginAttemptService#recordFailure}. It asserts,
 * in the same test, that the account's persisted failure count did <em>not</em> move — which is a direct
 * observation that {@code recordFailure} never ran on that path. So the counter moving and {@code recordFailure}
 * not running are asserted together, and no instrumentation living inside that method can satisfy both.</p>
 *
 * <h2>Deltas, not absolutes</h2>
 *
 * <p>The registry is application-wide and the Spring context is shared across test classes, so every assertion is a
 * before/after difference. An absolute would pass or fail depending on which class ran first.</p>
 */
@AutoConfigureWebTestClient(timeout = IntegrationTest.DEFAULT_TIMEOUT)
@IntegrationTest
class LoginMetersIT {

    private static final String LOGINS_METER = "security.authentication.logins";
    private static final String FAILED_LOGINS_METER = "security.authentication.failed-logins";

    private static final String LOGIN = "meters-subject";
    private static final String INACTIVE_LOGIN = "meters-inactive";
    private static final String PASSWORD = "correct-horse";

    @Autowired
    private ObjectMapper om;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        saveUser(LOGIN, true);
        saveUser(INACTIVE_LOGIN, false);
    }

    @Test
    void aSuccessfulSignInIsCountedOncePerAttempt() {
        double successes = successes();
        double failures = allFailures();

        attempt(PASSWORD).expectStatus().isOk();
        attempt(PASSWORD).expectStatus().isOk();

        // Per attempt, not per account — the same person signing in twice is two. Written down here because it is
        // the half of "logins by success | failed" that is NOT counted the same way as the other half.
        assertThat(successes() - successes).isEqualTo(2);
        assertThat(allFailures() - failures).isZero();
    }

    @Test
    void aWrongPasswordCountsTheAccountOnceForTheWholeRun() {
        double badCredentials = failures("bad-credentials");

        attempt("wrong-password").expectStatus().isUnauthorized();
        attempt("wrong-password").expectStatus().isUnauthorized();
        attempt("wrong-password").expectStatus().isUnauthorized();

        // Three attempts, one account, one increment. This is the whole per-account decision: a script making a
        // thousand guesses against one login must not be able to drown out every real user having trouble.
        assertThat(failures("bad-credentials") - badCredentials).isEqualTo(1);
    }

    @Test
    void aFreshRunAfterASuccessfulSignInIsCountedAgain() {
        double badCredentials = failures("bad-credentials");

        attempt("wrong-password").expectStatus().isUnauthorized();
        // A success clears the persisted counter, which ends the run. The next failure starts a new one.
        attempt(PASSWORD).expectStatus().isOk();
        attempt("wrong-password").expectStatus().isUnauthorized();

        assertThat(failures("bad-credentials") - badCredentials).isEqualTo(2);
    }

    @Test
    void aNeverActivatedAccountIsCountedUnderItsOwnCause() {
        double notActivated = failures("not-activated");
        double badCredentials = failures("bad-credentials");

        // Deliberately only "not a success". Measured 2026-09-10, this endpoint answers **500** for a
        // never-activated account — ExceptionTranslator#getMappedStatus maps BadCredentialsException and
        // UsernameNotFoundException to 401 and nothing maps UserNotActivatedException, so it falls through to
        // Spring's default while the body still reads "Unauthorized / Invalid credentials". That is a defect, it
        // predates this metric, and it is not item 34's to fix — so this test refuses to pin either answer, and
        // will keep passing on the day somebody does fix it.
        attemptAs(INACTIVE_LOGIN, PASSWORD).expectStatus().value(status -> assertThat(status).isGreaterThanOrEqualTo(400));

        // The right password against an account that was never activated. If this landed on bad-credentials the
        // dashboard would report a password problem during what is actually an activation-mail outage.
        assertThat(failures("not-activated") - notActivated).isEqualTo(1);
        assertThat(failures("bad-credentials") - badCredentials).isZero();
    }

    @Test
    void anUnknownLoginIsCountedNowhereAtAll() {
        double failures = allFailures();

        attemptAs("no-such-account-anywhere", "whatever").expectStatus().isUnauthorized();

        // Deliberate. LoginAttemptService already declines to record anything against a login it cannot find,
        // because a per-login side effect is an account-existence oracle; counting it in a metric instead would
        // rebuild that oracle for anyone who can read /management/prometheus.
        assertThat(allFailures() - failures).isZero();
    }

    @Test
    void aRefusedAttemptAgainstALockedAccountIsCounted() {
        lockTheAccount();
        User before = userRepository.findOneByLogin(LOGIN).block();
        assertThat(before).isNotNull();

        double accountLocked = failures("account-locked");
        double badCredentials = failures("bad-credentials");
        double notActivated = failures("not-activated");

        attempt(PASSWORD).expectStatus().isUnauthorized();

        assertThat(failures("account-locked") - accountLocked).isEqualTo(1);

        // The structural half. This attempt short-circuited before the authentication manager ran, so
        // recordFailure was never called — and the persisted counter proves it rather than asserting it in prose.
        // An implementation that counted account-locked from inside recordFailure would fail the assertion above
        // while still passing this one, which is exactly the "simplification" this test exists to refuse.
        User after = userRepository.findOneByLogin(LOGIN).block();
        assertThat(after).isNotNull();
        assertThat(after.getFailedLoginAttempts()).isEqualTo(before.getFailedLoginAttempts());
        assertThat(failures("bad-credentials") - badCredentials).isZero();
        assertThat(failures("not-activated") - notActivated).isZero();
    }

    @Test
    void hammeringALockedAccountKeepsCounting() {
        lockTheAccount();
        double accountLocked = failures("account-locked");

        attempt(PASSWORD).expectStatus().isUnauthorized();
        attempt(PASSWORD).expectStatus().isUnauthorized();
        attempt(PASSWORD).expectStatus().isUnauthorized();

        // Per attempt for this cause alone, and stated here so nobody "fixes" it to match the other two. There is
        // no account-level transition left to count — the account is already failing — and the volume IS the
        // signal: this is what a credential-stuffing run looks like while it is happening.
        assertThat(failures("account-locked") - accountLocked).isEqualTo(3);
    }

    /**
     * Locks the account by writing the lock directly, rather than by failing five sign-ins through the endpoint.
     *
     * <p>Failing through the endpoint would move {@code bad-credentials} on the way in, which would leave the
     * locked-cause tests unable to say which increment came from where.</p>
     */
    private void lockTheAccount() {
        User user = userRepository.findOneByLogin(LOGIN).block();
        assertThat(user).isNotNull();
        user.setFailedLoginAttempts(LoginAttemptService.FREE_ATTEMPTS + 1);
        user.setLockedUntil(Instant.now().plus(10, ChronoUnit.MINUTES));
        userRepository.save(user).block();
    }

    private void saveUser(String login, boolean activated) {
        userRepository.findOneByLogin(login).flatMap(userRepository::delete).block();
        User user = new User();
        user.setLogin(login);
        user.setEmail(login + "@example.com");
        user.setActivated(activated);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        userRepository.save(user).block();
    }

    private double successes() {
        return meterRegistry.get(LOGINS_METER).tag("outcome", "success").counter().count();
    }

    private double failures(String cause) {
        return meterRegistry.get(FAILED_LOGINS_METER).tag("cause", cause).counter().count();
    }

    private double allFailures() {
        return failures("bad-credentials") + failures("not-activated") + failures("account-locked");
    }

    private WebTestClient.ResponseSpec attempt(String password) {
        return attemptAs(LOGIN, password);
    }

    private WebTestClient.ResponseSpec attemptAs(String login, String password) {
        LoginVM vm = new LoginVM();
        vm.setUsername(login);
        vm.setPassword(password);
        return webTestClient
            .post()
            .uri("/api/authenticate")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(vm))
            .exchange();
    }
}
