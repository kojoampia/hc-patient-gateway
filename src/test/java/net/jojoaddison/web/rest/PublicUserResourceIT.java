package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;

import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.User;
import net.jojoaddison.repository.UserRepository;
import net.jojoaddison.security.AuthoritiesConstants;
import net.jojoaddison.service.dto.UserDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Integration tests for the {@link PublicUserResource} REST controller.
 */
@AutoConfigureWebTestClient(timeout = IntegrationTest.DEFAULT_TIMEOUT)
@WithMockUser(authorities = AuthoritiesConstants.ADMIN)
@IntegrationTest
class PublicUserResourceIT {

    private static final String DEFAULT_LOGIN = "johndoe";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WebTestClient webTestClient;

    private User user;

    @BeforeEach
    public void initTest() {
        user = UserResourceIT.initTestUser(userRepository);
    }

    @Test
    void getAllPublicUsers() {
        // Initialize the database
        userRepository.save(user).block();

        // Get all the users
        UserDTO foundUser = webTestClient
            .get()
            .uri("/api/users?sort=id,desc")
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus()
            .isOk()
            .expectHeader()
            .contentType(MediaType.APPLICATION_JSON)
            .returnResult(UserDTO.class)
            .getResponseBody()
            .blockFirst();

        assertThat(foundUser.getLogin()).isEqualTo(DEFAULT_LOGIN);
    }

    /**
     * An ordinary account must not be able to enumerate logins.
     *
     * <p>This endpoint was reachable by every authenticated caller until 2026-08-05 — its name and its generated
     * Javadoc both said "allowed for anyone" — which handed anyone who registered a complete list of valid logins to
     * aim a credential-stuffing run at. Registration is open to the internet, so that was a low bar.</p>
     */
    @Test
    @WithMockUser(authorities = AuthoritiesConstants.USER)
    void getAllPublicUsersIsForbiddenForOrdinaryUsers() {
        userRepository.save(user).block();

        webTestClient.get().uri("/api/users?sort=id,desc").accept(MediaType.APPLICATION_JSON).exchange().expectStatus().isForbidden();
    }
}
