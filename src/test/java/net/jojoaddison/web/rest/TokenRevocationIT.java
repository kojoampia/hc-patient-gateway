package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;

import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.User;
import net.jojoaddison.repository.RevokedTokenRepository;
import net.jojoaddison.repository.UserRepository;
import net.jojoaddison.web.rest.vm.LoginVM;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Signing out actually invalidates the token.
 *
 * <p>Until 2026-08-05 it did not. Authentication is stateless, logout was a client-side
 * {@code localStorage.removeItem}, and the token stayed valid until it expired — up to thirty days, and valid across
 * all three Health Connect products because they share a signing key. The only way to kill one was to rotate that key,
 * which would have signed out every user of all three.</p>
 */
@AutoConfigureWebTestClient(timeout = IntegrationTest.DEFAULT_TIMEOUT)
@IntegrationTest
class TokenRevocationIT {

    private static final String LOGIN = "revocation-subject";
    private static final String PASSWORD = "correct-horse";

    @Autowired
    private ObjectMapper om;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RevokedTokenRepository revokedTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        revokedTokenRepository.deleteAll().block();
        userRepository.findOneByLogin(LOGIN).flatMap(userRepository::delete).block();
        User user = new User();
        user.setLogin(LOGIN);
        user.setEmail(LOGIN + "@example.com");
        user.setActivated(true);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        userRepository.save(user).block();
    }

    @Test
    void aTokenWorksUntilItIsRevokedAndNotAfterwards() {
        String token = signIn();

        // Works.
        get("/api/account", token).expectStatus().isOk();

        // Sign out.
        webTestClient
            .post()
            .uri("/api/account/logout")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .exchange()
            .expectStatus()
            .isOk();

        // The same token, which has not expired, is now refused.
        get("/api/account", token).expectStatus().isUnauthorized();
    }

    @Test
    void aRevokedTokenCannotEvenBeUsedToSignOutAgain() {
        // Written first as an idempotency test, which was the wrong expectation: revocation is enforced in the
        // decoder, so a revoked token is refused before it reaches any endpoint — logout included. That is the
        // stronger property and worth asserting deliberately rather than discovering.
        String token = signIn();

        webTestClient
            .post()
            .uri("/api/account/logout")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .exchange()
            .expectStatus()
            .isOk();

        webTestClient
            .post()
            .uri("/api/account/logout")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .exchange()
            .expectStatus()
            .isUnauthorized();

        // And the first sign-out wrote exactly one row, keyed on the jti, so a replay could not grow the collection.
        assertThat(revokedTokenRepository.count().block()).isEqualTo(1L);
    }

    @Test
    void revokingOneSessionLeavesAnotherAlone() {
        // The whole reason for a per-token id rather than a per-user flag: signing out on a phone must not sign the
        // same person out on their laptop.
        String phone = signIn();
        String laptop = signIn();

        webTestClient
            .post()
            .uri("/api/account/logout")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + phone)
            .exchange()
            .expectStatus()
            .isOk();

        get("/api/account", phone).expectStatus().isUnauthorized();
        get("/api/account", laptop).expectStatus().isOk();
    }

    @Test
    void theRevocationRecordCarriesTheTokensOwnExpiry() {
        // The TTL index deletes the row at that instant. Without it the collection only ever grows, which is what
        // makes people give up on revocation lists.
        String token = signIn();
        webTestClient
            .post()
            .uri("/api/account/logout")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .exchange()
            .expectStatus()
            .isOk();

        assertThat(revokedTokenRepository.findAll().blockFirst()).satisfies(revoked -> {
            assertThat(revoked.getId()).isNotBlank();
            assertThat(revoked.getExpiresAt()).isNotNull().isAfter(java.time.Instant.now());
        });
    }

    private WebTestClient.ResponseSpec get(String uri, String token) {
        return webTestClient.get().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
    }

    private String signIn() {
        LoginVM vm = new LoginVM();
        vm.setUsername(LOGIN);
        vm.setPassword(PASSWORD);
        byte[] body = webTestClient
            .post()
            .uri("/api/authenticate")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(vm))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .returnResult()
            .getResponseBody();
        return om.readTree(new String(body, java.nio.charset.StandardCharsets.UTF_8)).get("id_token").asString();
    }
}
