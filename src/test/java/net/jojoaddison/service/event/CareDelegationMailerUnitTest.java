package net.jojoaddison.service.event;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import net.jojoaddison.domain.User;
import net.jojoaddison.repository.UserRepository;
import net.jojoaddison.service.MailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * Which mails a delegation change obliges, and how often.
 *
 * <p>The distinction the tests turn on: an angel stepping down leaves the patient with nobody able to act for them and
 * only they can nominate a replacement, so that mail has to arrive. A patient revoking already knows what they did.</p>
 */
class CareDelegationMailerUnitTest {

    private UserRepository userRepository;
    private MailService mailService;
    private CareDelegationMailer consumer;

    private final User patient = user("ama@example.test", "ama");
    private final User angel = user("kofi@example.test", "kofi");

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        mailService = mock(MailService.class);
        consumer = new CareDelegationMailer(userRepository, mailService);
        when(userRepository.findOneByEmailIgnoreCase("ama@example.test")).thenReturn(Mono.just(patient));
        when(userRepository.findOneByEmailIgnoreCase("kofi@example.test")).thenReturn(Mono.just(angel));
    }

    @Test
    void anAngelSteppingDownTellsThePatientSoTheyCanNominateSomebodyElse() {
        consumer.handle(delegationChanged("e1", "REVOKED_BY_ANGEL"));

        // Verified with a timeout, not immediately: the send is deliberately pushed off the calling thread, so an
        // instant assertion would race it — and pass or fail depending on the machine.
        verify(mailService, timeout(2000)).sendCareAngelSteppedDownMail(patient);
        verify(mailService, timeout(2000)).sendCareAngelAccessEndedMail(angel);
    }

    @Test
    void aPatientRevokingIsNotToldWhatTheyJustDid() {
        consumer.handle(delegationChanged("e2", "REVOKED_BY_PATIENT"));

        verify(mailService, timeout(2000)).sendCareAngelAccessEndedMail(angel);
        verify(mailService, never()).sendCareAngelSteppedDownMail(any());
    }

    @Test
    void aRedeliveredEventDoesNotSendASecondEmail() {
        // Delivery is at least once, so a duplicate is normal rather than exceptional.
        PatientEvent event = delegationChanged("e3", "REVOKED_BY_ANGEL");
        consumer.handle(event);
        consumer.handle(event);

        verify(mailService, timeout(2000).times(1)).sendCareAngelSteppedDownMail(patient);
    }

    @Test
    void everyOtherTypeOnTheTopicIsIgnored() {
        // Onboarding and account events share this stream. Ignoring the unknown is what lets a new type be added
        // without redeploying this service.
        consumer.handle(new PatientEvent("e4", PatientEventType.ACCOUNT_CREATED, 1, Instant.now(), "patientGateway", subject(), Map.of()));

        verify(mailService, never()).sendCareAngelSteppedDownMail(any());
        verify(mailService, never()).sendCareAngelAccessEndedMail(any());
    }

    private static PatientEvent delegationChanged(String id, String change) {
        return new PatientEvent(
            id,
            PatientEventType.CARE_DELEGATION_CHANGED,
            1,
            Instant.now(),
            "hcPatientService",
            subject(),
            Map.of("change", change, "angelEmail", "kofi@example.test", "delegationId", "d1")
        );
    }

    private static PatientEvent.Subject subject() {
        return new PatientEvent.Subject("ama@example.test", "ama", "patient-1");
    }

    private static User user(String email, String login) {
        User user = new User();
        user.setEmail(email);
        user.setLogin(login);
        return user;
    }
}
