package net.jojoaddison.service.event;

import java.util.Collections;
import java.util.LinkedHashMap;
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
 * Turns care-delegation changes into the emails they oblige.
 *
 * <p>Only the gateway can send mail, and only the patient service knows when a delegation changed, so the two are
 * joined by the event stream rather than by one calling the other. This consumer reads the same
 * {@code patient-events} topic everything else is published to and ignores every type but one — which is the point of
 * the shared envelope.</p>
 *
 * <h2>Delivery is at least once</h2>
 *
 * <p>So a duplicate must not send a second email. {@code eventId} is unique per emission and is what
 * {@link #alreadyHandled} keys on. The memory is deliberately small and in-process: it stops a redelivery seconds
 * later, which is the case that actually happens, and does not pretend to be a distributed guarantee. A duplicate
 * mail is a nuisance rather than a hazard, and paying for exactly-once here would mean a shared store on the hot path
 * of a notification.</p>
 *
 * <h2>Mail failure never rewinds anything</h2>
 *
 * <p>By the time this runs the delegation has already been revoked and access has already stopped. The event is a
 * notification, never the mechanism — if the mail fails, somebody is not told, and that is the whole of the damage.
 * Nothing here may make the revocation conditional on it.</p>
 */
@Component
public class CareDelegationMailer {

    private static final Logger LOG = LoggerFactory.getLogger(CareDelegationMailer.class);

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

    public CareDelegationMailer(UserRepository userRepository, MailService mailService) {
        this.userRepository = userRepository;
        this.mailService = mailService;
    }

    /**
     * Called by {@link PatientEventMailRouter}, which owns the binding.
     *
     * <p>This method used to be the {@code @Bean} bound to {@code patientEventsConsumer-in-0}. It moved on
     * 2026-08-31, when deletion requests became a second family of mail on the same topic and one of the two mailers
     * would otherwise have had to own the consumer for both. The bean name derives from the <em>method</em> name and
     * that name went with it, so the binding is unchanged — which is the only thing that mattered, because
     * unbinding it stops every mail in the product with nothing failing anywhere.</p>
     */
    void handle(PatientEvent event) {
        if (event == null || !PatientEventType.CARE_DELEGATION_CHANGED.equals(event.type())) {
            // Every other type on this topic belongs to somebody else. Ignoring the unknown is what lets a new event
            // be added without redeploying this service.
            return;
        }
        if (alreadyHandled(event.eventId())) {
            LOG.debug("Ignoring a redelivered {}", event.eventId());
            return;
        }

        String change = String.valueOf(event.data().get("change"));
        String patientEmail = event.subject() == null ? null : event.subject().email();
        String angelEmail = String.valueOf(event.data().get("angelEmail"));

        switch (change) {
            case "REVOKED_BY_ANGEL" -> {
                // The one that has to arrive. The patient is left with nobody able to act for them, and only they can
                // nominate a replacement.
                mail(patientEmail, mailService::sendCareAngelSteppedDownMail);
                mail(angelEmail, mailService::sendCareAngelAccessEndedMail);
            }
            case "REVOKED_BY_PATIENT" -> mail(angelEmail, mailService::sendCareAngelAccessEndedMail);
            case "STANDBY_ACTIVATED" -> mail(angelEmail, mailService::sendCareAngelNominationToExistingUserMail);
            default -> LOG.debug("No mail is owed for delegation change {}", change);
        }
    }

    /** @return true if this exact emission has been seen before. */
    private boolean alreadyHandled(String eventId) {
        return eventId != null && !handled.add(eventId);
    }

    /**
     * Looks the recipient up and sends, off the event loop.
     *
     * <p>{@code MailService} already moves the SMTP conversation to a bounded-elastic thread, but the repository read
     * in front of it is reactive and must not be resolved by blocking either. Subscribing here rather than returning
     * the {@code Mono} is deliberate: the consumer's contract is "handled", and a mail that is still in flight has
     * been handled as far as the broker is concerned.</p>
     */
    private void mail(String email, Consumer<User> send) {
        if (email == null || email.isBlank() || "null".equals(email)) {
            LOG.debug("No address to notify");
            return;
        }
        userRepository
            .findOneByEmailIgnoreCase(email)
            .doOnNext(send)
            .switchIfEmpty(Mono.fromRunnable(() -> LOG.debug("Nobody with that address to notify")))
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(null, e -> LOG.warn("Could not notify {} — the delegation is unaffected", email, e));
    }
}
