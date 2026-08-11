package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;

import net.jojoaddison.IntegrationTest;
import net.jojoaddison.config.Constants;
import net.jojoaddison.domain.User;
import net.jojoaddison.repository.UserRepository;
import net.jojoaddison.web.rest.vm.UsernameCheckVM;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Integration tests for {@code POST /api/account/username-available}, the registration form's
 * username look-ahead.
 */
@AutoConfigureWebTestClient(timeout = IntegrationTest.DEFAULT_TIMEOUT)
@IntegrationTest
class UsernameAvailabilityIT {

    @Autowired
    private ObjectMapper om;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WebTestClient accountWebTestClient;

    @BeforeEach
    void setup() {
        userRepository.deleteAll().block();
    }

    private WebTestClient.ResponseSpec check(String login) throws Exception {
        UsernameCheckVM vm = new UsernameCheckVM();
        vm.setLogin(login);
        return accountWebTestClient
            .post()
            .uri("/api/account/username-available")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(vm))
            .exchange();
    }

    /** User.password is @Size(min = 60, max = 60) — the width of a bcrypt hash. Not a real one. */
    private static final String PASSWORD_HASH_PLACEHOLDER = "x".repeat(60);

    private User activatedUser(String login) {
        User user = new User();
        user.setLogin(login);
        user.setPassword(PASSWORD_HASH_PLACEHOLDER);
        user.setEmail(login + "@example.com");
        user.setActivated(true);
        user.setLangKey(Constants.DEFAULT_LANGUAGE);
        return user;
    }

    @Test
    void isReachableWithoutAuthentication() throws Exception {
        // The whole point: nobody filling in the registration form has a token. If SecurityConfiguration
        // ever stops listing this path, the look-ahead silently 401s on every keystroke.
        check("nobody-has-this").expectStatus().isOk();
    }

    @Test
    void reportsAFreeLoginAsAvailableWithNoSuggestions() throws Exception {
        check("brand-new-login")
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath("$.available")
            .isEqualTo(true)
            .jsonPath("$.suggestions")
            .isEmpty();
    }

    @Test
    void reportsATakenLoginAsUnavailableAndOffersFreeAlternatives() throws Exception {
        userRepository.save(activatedUser("kojo")).block();

        check("kojo")
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath("$.available")
            .isEqualTo(false)
            .jsonPath("$.suggestions")
            .isNotEmpty()
            .jsonPath("$.suggestions[0]")
            .isEqualTo("kojo1");
    }

    @Test
    void skipsSuggestionsThatAreThemselvesTaken() throws Exception {
        userRepository.save(activatedUser("kojo")).block();
        userRepository.save(activatedUser("kojo1")).block();
        userRepository.save(activatedUser("kojo2")).block();

        check("kojo").expectStatus().isOk().expectBody().jsonPath("$.suggestions[0]").isEqualTo("kojo3");
    }

    @Test
    void matchesRegistrationByIgnoringCase() throws Exception {
        // registerUser lower-cases the login before storing it, so "Kojo" and "kojo" are the same
        // account. Reporting "Kojo" free would send the user to a registration that then fails.
        userRepository.save(activatedUser("kojo")).block();

        check("KOJO").expectStatus().isOk().expectBody().jsonPath("$.available").isEqualTo(false);
    }

    @Test
    void treatsALoginHeldOnlyByAnUnactivatedRegistrationAsAvailable() throws Exception {
        // registerUser deletes a never-activated user and carries on, so this login IS registrable.
        // Reporting it taken would send someone away from a name they could have had.
        User abandoned = activatedUser("abandoned");
        abandoned.setActivated(false);
        userRepository.save(abandoned).block();

        check("abandoned").expectStatus().isOk().expectBody().jsonPath("$.available").isEqualTo(true);

        assertThat(userRepository.findOneByLogin("abandoned").blockOptional()).isPresent();
    }

    @Test
    void rejectsALoginThatCouldNeverBeRegistered() throws Exception {
        // Same constraints as ManagedUserVM. Answering "available" for a login the registration
        // validator rejects would be a lie the form could not act on.
        check("funky-log(n").expectStatus().isBadRequest();
    }

    @Test
    void rejectsAnEmptyLogin() throws Exception {
        check("").expectStatus().isBadRequest();
    }

    @Test
    void revealsNothingAboutTheUserHoldingATakenLogin() throws Exception {
        userRepository.save(activatedUser("kojo")).block();

        String body = new String(
            check("kojo").expectStatus().isOk().expectBody().returnResult().getResponseBodyContent(),
            java.nio.charset.StandardCharsets.UTF_8
        );

        // Unauthenticated endpoint: everything it returns is public. "Taken" is all the form needs.
        assertThat(body).doesNotContain("@example.com").doesNotContain("password").doesNotContain("email");
    }
}
