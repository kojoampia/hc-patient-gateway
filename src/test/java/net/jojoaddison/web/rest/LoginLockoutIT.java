package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
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
 * Per-account lockout, through the sign-in endpoint.
 *
 * <p>Until 2026-08-05 there was no limit of any kind on {@code /api/authenticate}: unlimited credential stuffing
 * against every account including the administrator, on a publicly reachable endpoint, with self-registration open.
 * The nginx limit added at the same time keys on the source address and is therefore blind to the distributed case;
 * this is the half that follows the account.</p>
 */
@AutoConfigureWebTestClient(timeout = IntegrationTest.DEFAULT_TIMEOUT)
@IntegrationTest
class LoginLockoutIT {

    private static final String LOGIN = "lockout-subject";

    /** A second account that never activated. Item 35. */
    private static final String DORMANT_LOGIN = "dormant-item35";
    private static final String PASSWORD = "correct-horse";

    @Autowired
    private ObjectMapper om;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        userRepository.findOneByLogin(LOGIN).flatMap(userRepository::delete).block();
        userRepository.findOneByLogin(DORMANT_LOGIN).flatMap(userRepository::delete).block();
        User user = new User();
        user.setLogin(LOGIN);
        user.setEmail(LOGIN + "@example.com");
        user.setActivated(true);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        userRepository.save(user).block();
    }

    @Test
    void repeatedFailuresEventuallyLockTheAccount() {
        for (int i = 0; i < LoginAttemptService.FREE_ATTEMPTS + 1; i++) {
            attempt("wrong-password").expectStatus().isUnauthorized();
        }

        User locked = userRepository.findOneByLogin(LOGIN).block();
        assertThat(locked).isNotNull();
        assertThat(locked.getFailedLoginAttempts()).isGreaterThan(LoginAttemptService.FREE_ATTEMPTS);
        assertThat(locked.getLockedUntil()).isAfter(Instant.now());
    }

    @Test
    void aLockedAccountIsRefusedEvenWithTheCorrectPassword() {
        for (int i = 0; i < LoginAttemptService.FREE_ATTEMPTS + 1; i++) {
            attempt("wrong-password").expectStatus().isUnauthorized();
        }

        // The point of the whole thing: knowing the password is not enough while the account is locked.
        attempt(PASSWORD).expectStatus().isUnauthorized();
    }

    @Test
    void theLockedResponseIsIndistinguishableFromAWrongPassword() {
        // A "your account is locked" response is an account-existence oracle, and enumeration is precisely what the
        // attacker doing this is building towards. Both answers must look identical.
        for (int i = 0; i < LoginAttemptService.FREE_ATTEMPTS + 1; i++) {
            attempt("wrong-password").expectStatus().isUnauthorized();
        }

        byte[] lockedBody = attempt(PASSWORD).expectStatus().isUnauthorized().expectBody().returnResult().getResponseBody();
        byte[] unknownBody = attemptAs("no-such-account", "whatever")
            .expectStatus()
            .isUnauthorized()
            .expectBody()
            .returnResult()
            .getResponseBody();

        assertThat(new String(lockedBody == null ? new byte[0] : lockedBody)).isEqualTo(
            new String(unknownBody == null ? new byte[0] : unknownBody)
        );
    }

    /**
     * Item 35. A never-activated account must answer exactly what a wrong password answers.
     *
     * <p>This asserts INDISTINGUISHABILITY, not {@code isUnauthorized()}, and the difference is the whole
     * point. "Is 401" would keep passing on the day a later change moved the wrong-password response
     * somewhere else, leaving the two divergent again with a green test over it. The property being
     * defended is that the two responses cannot be told apart, so the two responses are what is
     * compared.</p>
     *
     * <p>Before the fix this failed on the status alone: the unactivated path answered <b>500</b>, which
     * is an account-state oracle that needs no body-reading at all — and it defeated
     * {@link #theLockedResponseIsIndistinguishableFromAWrongPassword} by going round it, since that test
     * only ever compared the locked and unknown-account answers to each other.</p>
     */
    @Test
    void theUnactivatedResponseIsIndistinguishableFromAWrongPassword() {
        User dormant = new User();
        dormant.setLogin(DORMANT_LOGIN);
        dormant.setEmail(DORMANT_LOGIN + "@example.com");
        dormant.setActivated(false);
        dormant.setPassword(passwordEncoder.encode(PASSWORD));
        userRepository.save(dormant).block();

        // The CORRECT password against an unactivated account. Using a wrong one would pass for the
        // wrong reason: it would be rejected as bad credentials before activation was ever consulted.
        byte[] unactivatedBody = attemptAs(DORMANT_LOGIN, PASSWORD)
            .expectStatus()
            .isUnauthorized()
            .expectBody()
            .returnResult()
            .getResponseBody();

        byte[] wrongPasswordBody = attempt("wrong-password").expectStatus().isUnauthorized().expectBody().returnResult().getResponseBody();

        assertThat(new String(unactivatedBody == null ? new byte[0] : unactivatedBody)).isEqualTo(
            new String(wrongPasswordBody == null ? new byte[0] : wrongPasswordBody)
        );
    }

    @Test
    void theCounterIsClearedByASuccessfulSignIn() {
        attempt("wrong-password").expectStatus().isUnauthorized();
        attempt("wrong-password").expectStatus().isUnauthorized();

        attempt(PASSWORD).expectStatus().isOk();

        User user = userRepository.findOneByLogin(LOGIN).block();
        assertThat(user).isNotNull();
        assertThat(user.getFailedLoginAttempts()).isZero();
        assertThat(user.getLockedUntil()).isNull();
    }

    @Test
    void aFewMistypedPasswordsCostNothing() {
        // Real people mistype. Locking on the first failure would read as an outage to them.
        for (int i = 0; i < LoginAttemptService.FREE_ATTEMPTS; i++) {
            attempt("wrong-password").expectStatus().isUnauthorized();
        }

        attempt(PASSWORD).expectStatus().isOk();
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
