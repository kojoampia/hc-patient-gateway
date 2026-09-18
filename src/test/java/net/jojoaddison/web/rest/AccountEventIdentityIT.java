package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.util.Map;
import java.util.Set;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.config.Constants;
import net.jojoaddison.domain.User;
import net.jojoaddison.repository.UserRepository;
import net.jojoaddison.security.AuthoritiesConstants;
import net.jojoaddison.service.event.PatientEventPublisher;
import net.jojoaddison.service.event.PatientEventType;
import net.jojoaddison.web.rest.vm.ManagedUserVM;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * That the two account events name the account, through the endpoints that actually emit them.
 *
 * <p><b>Backlog item 56.</b> The publisher's subject carried a hardcoded {@code null} where the identifier belongs,
 * so every frame this service had ever published was id-less. hc-admin's {@code Patient.accountId} is {@code @NotNull}
 * and {@code AccountCreated} is the first frame of every new patient, which means the gap dead-letters their
 * registration on a stack that looks healthy, with the consumer group's lag at zero.</p>
 *
 * <p>Pinned at the call sites rather than only on the publisher, because the two halves fail differently: a publisher
 * that drops the id it was handed, and a resource that hands it an account with none. {@code
 * PatientEventPublisherUnitTest} owns the first half — that a supplied id reaches {@code subject.accountId} under the
 * name hc-admin reads. This owns the second.</p>
 *
 * <p>Both events, deliberately, and the item says why: carrying the id on {@code AccountCreated} and not on
 * {@code AccountActivated} makes a consumer's join depend on which frame arrived first.</p>
 */
@AutoConfigureWebTestClient(timeout = IntegrationTest.DEFAULT_TIMEOUT)
@IntegrationTest
class AccountEventIdentityIT {

    @Autowired
    private ObjectMapper om;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WebTestClient webTestClient;

    @MockitoSpyBean
    private PatientEventPublisher events;

    @BeforeEach
    void setup() {
        userRepository.deleteAll().block();
    }

    @Test
    void registeringPublishesAnAccountCreatedThatNamesTheNewAccount() {
        ManagedUserVM newUser = new ManagedUserVM();
        newUser.setLogin("event-identity-created");
        newUser.setPassword("password");
        newUser.setFirstName("Ama");
        newUser.setLastName("Mensah");
        newUser.setEmail("event-identity-created@example.com");
        newUser.setLangKey(Constants.DEFAULT_LANGUAGE);
        newUser.setAuthorities(Set.of(AuthoritiesConstants.USER));

        webTestClient
            .post()
            .uri("/api/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(newUser))
            .exchange()
            .expectStatus()
            .isCreated();

        User stored = userRepository.findOneByLogin("event-identity-created").block();
        assertThat(published(PatientEventType.ACCOUNT_CREATED).getId())
            .as("AccountCreated must name the account that was just created — it is hc-admin's only source for it")
            .isNotNull()
            .isEqualTo(stored.getId());
    }

    @Test
    void activatingPublishesAnAccountActivatedThatNamesTheSameAccount() {
        User user = new User();
        user.setLogin("event-identity-activated");
        user.setEmail("event-identity-activated@example.com");
        user.setPassword(RandomStringUtils.randomAlphanumeric(60));
        user.setActivated(false);
        user.setActivationKey("event-identity-key");
        User stored = userRepository.save(user).block();

        webTestClient.get().uri("/api/activate?key={key}", "event-identity-key").exchange().expectStatus().isOk();

        assertThat(published(PatientEventType.ACCOUNT_ACTIVATED).getId())
            .as("AccountActivated must name the same account as AccountCreated, or the join depends on arrival order")
            .isNotNull()
            .isEqualTo(stored.getId());
    }

    /** The account the resource handed the publisher for {@code type}, waited for because publishing is fire-and-forget. */
    private User published(String type) {
        ArgumentCaptor<User> account = ArgumentCaptor.forClass(User.class);
        verify(events, timeout(5000)).publish(eq(type), account.capture(), any(Map.class));
        return account.getValue();
    }
}
