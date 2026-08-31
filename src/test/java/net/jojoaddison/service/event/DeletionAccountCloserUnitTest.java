package net.jojoaddison.service.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

/**
 * That an erased record's login stops working.
 *
 * <p>The patient service erases the clinical record and cannot touch the gateway's {@code User}, so until this
 * existed a completed erasure left somebody able to sign in — resolving to no patient and seeing an empty portal.
 * Correct behaviour, and not the same thing as being gone.</p>
 *
 * <p><b>Deactivated rather than deleted, decided 2026-08-31.</b> Deleting is what the patient literally asked for
 * and would also remove their email; deactivating keeps the audit trail, at the cost of retaining that address.
 * The tests below pin the choice as a choice, so changing it is a decision rather than an edit — and it changes the
 * privacy policy and the Play data-safety declaration with it.</p>
 */
class DeletionAccountCloserUnitTest {

    private UserRepository userRepository;
    private DeletionAccountCloser closer;
    private User patient;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        closer = new DeletionAccountCloser(userRepository);

        patient = new User();
        patient.setLogin("kojo");
        patient.setEmail("kojo@example.test");
        patient.setActivated(true);

        when(userRepository.findOneByEmailIgnoreCase("kojo@example.test")).thenReturn(Mono.just(patient));
        when(userRepository.save(any(User.class))).thenAnswer(call -> Mono.just(call.getArgument(0)));
    }

    @Test
    void aCompletedErasureClosesTheAccount() {
        closer.handle(event("e1", "COMPLETED"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository, timeout(2000)).save(saved.capture());

        assertThat(saved.getValue().isActivated()).isFalse();
    }

    @Test
    void itDeactivatesRatherThanDeleting() {
        // The decision, pinned. Deleting would remove the email — which is what the patient asked for — but the
        // retained DeletionRequest names a login, and a login resolving to nothing is a weaker record of what was
        // done than one resolving to a closed account. Changing this changes the privacy policy too.
        closer.handle(event("e2", "COMPLETED"));

        verify(userRepository, timeout(2000)).save(any(User.class));
        verify(userRepository, never()).delete(any(User.class));
        assertThat(patient.getEmail()).isEqualTo("kojo@example.test");
    }

    @Test
    void nothingElseClosesAnAccount() {
        // Raised, withdrawn and refused all leave the login exactly as it was. Only an erasure closes it — and
        // closing one on a withdrawal would lock somebody out of a record they had just decided to keep.
        for (String change : new String[] { "RAISED", "CANCELLED", "REJECTED" }) {
            closer.handle(event("e-" + change, change));
        }

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void anAlreadyClosedAccountIsNotSavedAgain() {
        patient.setActivated(false);

        closer.handle(event("e3", "COMPLETED"));

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void aRedeliveredEventClosesItOnce() {
        PatientEvent event = event("e4", "COMPLETED");
        closer.handle(event);
        closer.handle(event);

        verify(userRepository, timeout(2000).times(1)).save(any(User.class));
    }

    @Test
    void everyOtherTypeOnTheTopicIsIgnored() {
        closer.handle(
            new PatientEvent("e5", PatientEventType.CARE_DELEGATION_CHANGED, 1, Instant.now(), "hcPatientService", subject(), Map.of())
        );

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void anEventWithNoAddressClosesNothingRatherThanFailing() {
        PatientEvent orphan = new PatientEvent(
            "e6",
            PatientEventType.DELETION_REQUEST_CHANGED,
            1,
            Instant.now(),
            "hcPatientService",
            null,
            Map.of("change", "COMPLETED")
        );

        closer.handle(orphan);

        verify(userRepository, never()).save(any(User.class));
    }

    private static PatientEvent event(String id, String change) {
        Map<String, Object> data = new HashMap<>();
        data.put("change", change);
        data.put("requestId", "req-1");
        return new PatientEvent(id, PatientEventType.DELETION_REQUEST_CHANGED, 1, Instant.now(), "hcPatientService", subject(), data);
    }

    private static PatientEvent.Subject subject() {
        return new PatientEvent.Subject("kojo@example.test", "kojo", "patient-1");
    }
}
