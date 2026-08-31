package net.jojoaddison.service.event;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import net.jojoaddison.domain.User;
import net.jojoaddison.repository.UserRepository;
import net.jojoaddison.service.MailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Turns deletion-request changes into the emails they oblige.
 *
 * <p>Only the gateway can send mail, and only the patient service knows when a request moved, so the two are joined
 * by the event stream rather than by one calling the other — the same arrangement {@link CareDelegationMailer}
 * already uses, and for a stronger reason here: by the time {@code COMPLETED} is emitted the patient service has
 * already destroyed the record. A synchronous call from there would be a cross-service request made from the one
 * place that can no longer undo anything if it failed.</p>
 *
 * <p><b>Until 2026-08-31 a patient heard nothing at all.</b> They raised the one irreversible request in the product
 * and learnt the outcome only by signing back in, if they thought to — and after {@code COMPLETED} they could not
 * even do that, because there is no record left to show them. This mail is the only proof they get.</p>
 *
 * <h2>Delivery is at least once</h2>
 *
 * <p>So a duplicate must not send a second email, and this one matters more than the delegation mails: telling
 * somebody twice that their medical record has been erased is its own small harm. {@code eventId} is unique per
 * emission and is what {@link #alreadyHandled} keys on. The memory is small and in-process on purpose — it stops the
 * redelivery-seconds-later case, which is the one that happens, and does not pretend to be a distributed guarantee.</p>
 *
 * <h2>Mail failure never rewinds anything</h2>
 *
 * <p>By the time this runs, the erasure has happened and the request is saved. The record is already gone. The event
 * is a notification, never the mechanism, and nothing here may make the erasure conditional on a mail.</p>
 */
@Component
public class DeletionRequestMailer {

    private static final Logger LOG = LoggerFactory.getLogger(DeletionRequestMailer.class);

    /** Bounded so a long-running gateway cannot grow one entry per event forever. */
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
    private final MailService mailService;

    public DeletionRequestMailer(UserRepository userRepository, MailService mailService) {
        this.userRepository = userRepository;
        this.mailService = mailService;
    }

    void handle(PatientEvent event) {
        if (event == null || !PatientEventType.DELETION_REQUEST_CHANGED.equals(event.type())) {
            return;
        }
        if (alreadyHandled(event.eventId())) {
            LOG.debug("Ignoring a redelivered {}", event.eventId());
            return;
        }

        String change = String.valueOf(event.data().get("change"));
        String email = event.subject() == null ? null : event.subject().email();

        switch (change) {
            case "RAISED" -> {
                String dueDate = formatDue(event.data().get("dueAt"));
                mail(email, user -> mailService.sendDeletionRequestedMail(user, dueDate));
            }
            case "CANCELLED" -> mail(email, mailService::sendDeletionWithdrawnMail);
            case "COMPLETED" -> mail(email, mailService::sendDeletionCompletedMail);
            case "REJECTED" -> mail(email, mailService::sendDeletionRefusedMail);
            default -> LOG.debug("No mail is owed for deletion change {}", change);
        }
    }

    /**
     * The due date as a person reads it, or the raw value if it cannot be parsed.
     *
     * <p>Never blank and never an exception. This date is the substance of the first mail, but a mail that says
     * nothing about the date is still better than no mail — and this runs after the request is already saved, so
     * throwing here would lose the notification for a formatting problem.</p>
     *
     * <p>UTC rather than the patient's zone, which the gateway does not know. A day either side of a fourteen-day
     * window is not the risk; silently showing the wrong day because a zone was guessed would be.</p>
     */
    private String formatDue(Object dueAt) {
        if (dueAt == null) {
            return "";
        }
        try {
            return DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
                .withLocale(Locale.ENGLISH)
                .withZone(ZoneOffset.UTC)
                .format(Instant.parse(String.valueOf(dueAt)));
        } catch (RuntimeException e) {
            LOG.debug("Could not format dueAt '{}' — sending it as it arrived", dueAt);
            return String.valueOf(dueAt);
        }
    }

    /** @return true if this exact emission has been seen before. */
    private boolean alreadyHandled(String eventId) {
        return eventId != null && !handled.add(eventId);
    }

    /**
     * Looks the recipient up and sends, off the event loop.
     *
     * <p>The account is still there to look up, and that is not an accident of timing: the patient service erases the
     * clinical record and cannot touch the gateway's {@code User}. Whenever closing the account is automated, it has
     * to happen <b>after</b> this — close it first and there is nobody left to tell.</p>
     */
    private void mail(String email, Consumer<User> send) {
        if (email == null || email.isBlank() || "null".equals(email)) {
            LOG.warn("A deletion request moved with no address on it — nobody was told");
            return;
        }
        userRepository
            .findOneByEmailIgnoreCase(email)
            .doOnNext(send)
            .switchIfEmpty(Mono.fromRunnable(() -> LOG.warn("No account for {} — nobody was told their record moved", email)))
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(null, e -> LOG.warn("Could not notify {} — the erasure is unaffected", email, e));
    }
}
