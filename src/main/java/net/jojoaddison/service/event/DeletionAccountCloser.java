package net.jojoaddison.service.event;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.jojoaddison.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Closes the gateway account once a patient's record has been erased.
 *
 * <p>{@code hc-patient-service} runs {@code skipUserManagement} and holds no {@code User}: it erases the clinical
 * record and cannot touch the login. So until this existed, a completed erasure left somebody who could still sign
 * in — resolving to no patient and seeing an empty portal, which is correct behaviour and is not the same thing as
 * being gone.</p>
 *
 * <h2>Deactivated, not deleted — decided 2026-08-31</h2>
 *
 * <p>The alternative was deleting the {@code User} outright, which is what the patient literally asked for and would
 * also remove their email address. <b>Deactivating keeps the audit trail whole</b>: the retained
 * {@code DeletionRequest} names a login, and a login that resolves to nothing is a weaker record of what was done
 * than one that resolves to a closed account.</p>
 *
 * <p>The cost is real and should not be glossed: <b>this retains an email address</b> — the one piece of personal
 * data being erased everywhere else in the flow. Anyone revisiting this should weigh that against the audit trail
 * rather than assume the current answer was obvious. Note the privacy policy and the Play data-safety declaration
 * both describe what erasure removes, so <b>changing this decision changes those two documents with it.</b></p>
 *
 * <p>One thing the choice quietly bought: ordering stopped mattering. Deleting the {@code User} would have had to
 * happen strictly <em>after</em> {@link DeletionRequestMailer} ran, because the mail resolves its recipient by
 * looking the account up — close it first and there is nobody left to tell. Deactivation leaves the row in place,
 * so the mail resolves either way.</p>
 *
 * <h2>Failure posture</h2>
 *
 * <p>By the time this runs the clinical record is already gone. A failure here leaves an active login attached to an
 * erased record — bad, and still not a reason to unwind anything, because there is nothing to unwind. It is logged
 * at {@code WARN} so it is visible, and the request stays {@code COMPLETED} because it was.</p>
 */
@Component
public class DeletionAccountCloser {

    private static final Logger LOG = LoggerFactory.getLogger(DeletionAccountCloser.class);

    private static final int REMEMBERED_EVENTS = 500;

    private final Set<String> handled = Collections.newSetFromMap(
        Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > REMEMBERED_EVENTS;
                }
            }
        )
    );

    private final UserRepository userRepository;

    public DeletionAccountCloser(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    void handle(PatientEvent event) {
        if (event == null || !PatientEventType.DELETION_REQUEST_CHANGED.equals(event.type())) {
            return;
        }
        if (!"COMPLETED".equals(String.valueOf(event.data().get("change")))) {
            // Raised, withdrawn and refused all leave the account exactly as it was. Only an erasure closes it.
            return;
        }
        if (event.eventId() != null && !handled.add(event.eventId())) {
            LOG.debug("Ignoring a redelivered {}", event.eventId());
            return;
        }

        String email = event.subject() == null ? null : event.subject().email();
        if (email == null || email.isBlank() || "null".equals(email)) {
            LOG.warn("An erasure completed with no address on the event — no account was closed");
            return;
        }

        userRepository
            .findOneByEmailIgnoreCase(email)
            .flatMap(user -> {
                if (!user.isActivated()) {
                    LOG.debug("Account for {} was already closed", email);
                    return Mono.just(user);
                }
                user.setActivated(false);
                return userRepository.save(user);
            })
            .switchIfEmpty(Mono.fromRunnable(() -> LOG.warn("No account for {} — nothing to close after an erasure", email)))
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(
                null,
                e -> LOG.warn("Could not close the account for {} — the record is still erased, the login is not closed", email, e),
                () -> LOG.info("Closed the gateway account after an erasure")
            );
    }
}
