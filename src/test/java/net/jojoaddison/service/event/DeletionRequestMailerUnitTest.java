package net.jojoaddison.service.event;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import net.jojoaddison.domain.User;
import net.jojoaddison.repository.UserRepository;
import net.jojoaddison.service.MailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

/**
 * Which mail a deletion request obliges, and what it may say.
 *
 * <p>Until 2026-08-31 the answer was none: a patient raised the one irreversible request in this product and heard
 * nothing, whether it was carried out or refused. After {@code COMPLETED} they could not even sign in to find out,
 * because there is no record left to show them — so that mail is the only proof they get.</p>
 */
class DeletionRequestMailerUnitTest {

    private UserRepository userRepository;
    private MailService mailService;
    private DeletionRequestMailer mailer;

    private final User patient = user("kojo@example.test", "kojo");

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        mailService = mock(MailService.class);
        mailer = new DeletionRequestMailer(userRepository, mailService);
        when(userRepository.findOneByEmailIgnoreCase("kojo@example.test")).thenReturn(Mono.just(patient));
    }

    @Test
    void raisingIsConfirmedWithTheDateInWriting() {
        mailer.handle(event("e1", "RAISED", "2026-09-14T10:15:30Z"));

        // Verified with a timeout, not immediately: the send is pushed off the calling thread, so an instant
        // assertion would race it and pass or fail depending on the machine.
        ArgumentCaptor<String> due = ArgumentCaptor.forClass(String.class);
        verify(mailService, timeout(2000)).sendDeletionRequestedMail(eq(patient), due.capture());

        // The point of this mail: the date exists somewhere other than a screen they must sign in to reach.
        org.assertj.core.api.Assertions.assertThat(due.getValue()).contains("2026").contains("14");
    }

    @Test
    void aWithdrawalIsConfirmedSoSomebodyKnowsTheClockStopped() {
        mailer.handle(event("e2", "CANCELLED", null));

        verify(mailService, timeout(2000)).sendDeletionWithdrawnMail(patient);
    }

    @Test
    void completionIsToldToAnAddressThatNoLongerHasARecordBehindIt() {
        // The account is still there to look up only because closing it is a separate, later step. Whenever that is
        // automated it has to run AFTER this — close it first and there is nobody left to tell.
        mailer.handle(event("e3", "COMPLETED", null));

        verify(mailService, timeout(2000)).sendDeletionCompletedMail(patient);
    }

    @Test
    void refusalIsToldWithoutCarryingTheAdministratorsWords() {
        mailer.handle(event("e4", "REJECTED", null));

        verify(mailService, timeout(2000)).sendDeletionRefusedMail(patient);
        // The reason is not on the wire and not in the mail; the patient reads it in the portal, authenticated.
        verify(mailService, never()).sendDeletionCompletedMail(any());
    }

    @Test
    void aRedeliveredEventDoesNotTellSomebodyTwiceThatTheirRecordIsGone() {
        // Delivery is at least once, so a duplicate is normal. This one matters more than the delegation mails:
        // being told twice that your medical record has been erased is its own small harm.
        PatientEvent event = event("e5", "COMPLETED", null);
        mailer.handle(event);
        mailer.handle(event);

        verify(mailService, timeout(2000).times(1)).sendDeletionCompletedMail(patient);
    }

    @Test
    void everyOtherTypeOnTheTopicIsIgnored() {
        mailer.handle(
            new PatientEvent("e6", PatientEventType.CARE_DELEGATION_CHANGED, 1, Instant.now(), "hcPatientService", subject(), Map.of())
        );

        verify(mailService, never()).sendDeletionCompletedMail(any());
        verify(mailService, never()).sendDeletionRequestedMail(any(), anyString());
    }

    @Test
    void anUnparseableDateStillSendsTheMail() {
        // This runs after the request is already saved. Losing the notification because a date would not parse
        // would trade the whole message for its least important sentence.
        mailer.handle(event("e7", "RAISED", "not-a-date"));

        verify(mailService, timeout(2000)).sendDeletionRequestedMail(eq(patient), anyString());
    }

    @Test
    void anEventWithNoAddressTellsNobodyRatherThanFailing() {
        PatientEvent orphan = new PatientEvent(
            "e8",
            PatientEventType.DELETION_REQUEST_CHANGED,
            1,
            Instant.now(),
            "hcPatientService",
            null,
            Map.of("change", "COMPLETED")
        );

        mailer.handle(orphan);

        verify(mailService, never()).sendDeletionCompletedMail(any());
    }

    private static PatientEvent event(String id, String change, String dueAt) {
        Map<String, Object> data = new HashMap<>();
        data.put("change", change);
        data.put("requestId", "req-1");
        if (dueAt != null) {
            data.put("dueAt", dueAt);
        }
        return new PatientEvent(id, PatientEventType.DELETION_REQUEST_CHANGED, 1, Instant.now(), "hcPatientService", subject(), data);
    }

    private static PatientEvent.Subject subject() {
        return new PatientEvent.Subject("kojo@example.test", "kojo", "patient-1");
    }

    private static User user(String email, String login) {
        User user = new User();
        user.setEmail(email);
        user.setLogin(login);
        return user;
    }
}
