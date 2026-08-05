package net.jojoaddison.web.rest;

import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.User;
import net.jojoaddison.repository.UserRepository;
import net.jojoaddison.web.rest.vm.LoginVM;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Integration tests for the {@link AuthenticateController} REST controller.
 */
@AutoConfigureWebTestClient(timeout = IntegrationTest.DEFAULT_TIMEOUT)
@IntegrationTest
class AuthenticateControllerIT {

    @Autowired
    private ObjectMapper om;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void testAuthorize() throws Exception {
        User user = new User();
        user.setLogin("user-jwt-controller");
        user.setEmail("user-jwt-controller@example.com");
        user.setActivated(true);
        user.setPassword(passwordEncoder.encode("test"));

        userRepository.save(user).block();

        LoginVM login = new LoginVM();
        login.setUsername("user-jwt-controller");
        login.setPassword("test");
        webTestClient
            .post()
            .uri("/api/authenticate")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(login))
            .exchange()
            .expectStatus()
            .isOk()
            .expectHeader()
            .valueMatches("Authorization", "Bearer .+")
            .expectBody()
            .jsonPath("$.id_token")
            .isNotEmpty();
    }

    /**
     * The token must carry the account's email.
     *
     * <p>This is not a cosmetic claim. {@code hc-patient-service} runs with {@code skipUserManagement} and has no
     * User document of its own, so this claim is the only identity it receives that means anything in its data: it
     * resolves email to a Profile and from there to the {@code patientId} that scopes every query. Drop the claim and
     * that service does not fail loudly — it fails <em>closed</em>, and every patient sees an empty portal while the
     * login still works perfectly. This assertion is the cheapest place to notice.</p>
     */
    @Test
    void testAuthorizeCarriesTheEmailClaim() throws Exception {
        User user = new User();
        user.setLogin("user-jwt-email-claim");
        user.setEmail("user-jwt-email-claim@example.com");
        user.setActivated(true);
        user.setPassword(passwordEncoder.encode("test"));

        userRepository.save(user).block();

        LoginVM login = new LoginVM();
        login.setUsername("user-jwt-email-claim");
        login.setPassword("test");

        String body = new String(
            webTestClient
                .post()
                .uri("/api/authenticate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(om.writeValueAsBytes(login))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .returnResult()
                .getResponseBody(),
            java.nio.charset.StandardCharsets.UTF_8
        );

        String idToken = om.readTree(body).get("id_token").asString();
        // Decode the payload rather than trusting the encoder: base64url, middle segment.
        String payload = new String(
            java.util.Base64.getUrlDecoder().decode(idToken.split("\\.")[1]),
            java.nio.charset.StandardCharsets.UTF_8
        );

        org.assertj.core.api.Assertions.assertThat(om.readTree(payload).get("email").asString()).isEqualTo(
            "user-jwt-email-claim@example.com"
        );

        // Issuer and audience: not yet validated anywhere (see the note in AuthenticateController), but they have to
        // be present and correct before validation can be switched on, and this is where a typo would otherwise sit
        // unnoticed until it locked every user out on the day someone enabled the validators.
        org.assertj.core.api.Assertions.assertThat(om.readTree(payload).get("iss").asString()).isEqualTo(AuthenticateController.ISSUER);

        // A single-valued `aud` serializes as a bare string rather than a one-element array — RFC 7519 allows both,
        // and Nimbus takes the shorter form. Accept either, so this does not break if a second audience is ever added.
        tools.jackson.databind.JsonNode audience = om.readTree(payload).get("aud");
        org.assertj.core.api.Assertions.assertThat(audience.isArray() ? audience.get(0).asString() : audience.asString()).isEqualTo(
            AuthenticateController.AUDIENCE
        );
    }

    @Test
    void testAuthorizeWithRememberMe() throws Exception {
        User user = new User();
        user.setLogin("user-jwt-controller-remember-me");
        user.setEmail("user-jwt-controller-remember-me@example.com");
        user.setActivated(true);
        user.setPassword(passwordEncoder.encode("test"));

        userRepository.save(user).block();

        LoginVM login = new LoginVM();
        login.setUsername("user-jwt-controller-remember-me");
        login.setPassword("test");
        login.setRememberMe(true);
        webTestClient
            .post()
            .uri("/api/authenticate")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(login))
            .exchange()
            .expectStatus()
            .isOk()
            .expectHeader()
            .valueMatches("Authorization", "Bearer .+")
            .expectBody()
            .jsonPath("$.id_token")
            .isNotEmpty();
    }

    @Test
    void testAuthorizeFails() throws Exception {
        LoginVM login = new LoginVM();
        login.setUsername("wrong-user");
        login.setPassword("wrong password");
        webTestClient
            .post()
            .uri("/api/authenticate")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(login))
            .exchange()
            .expectStatus()
            .isUnauthorized()
            .expectHeader()
            .doesNotExist("Authorization")
            .expectBody()
            .jsonPath("$.id_token")
            .doesNotExist();
    }
}
