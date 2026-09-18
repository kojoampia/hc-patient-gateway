package net.jojoaddison.service.event;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.jojoaddison.domain.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Publishes account events onto {@code patient-events}.
 *
 * <h2>The {@code subscribeOn} is the whole point of this class and must not be removed</h2>
 *
 * <p>This service is reactive, and <strong>this exact mistake has already cost the subsystem a production
 * incident.</strong> {@code MailService} wrapped a blocking JavaMail send in {@code Mono.defer(...).subscribe()} with
 * no scheduler, which runs the work inline on the subscribing thread — every caller here is a handler on a Netty event
 * loop, so the SMTP conversation ran <em>on the event loop</em>, measured at 2.8s on {@code ntLoopGroup-4-3} and
 * stalling every other request on that thread. It read as asynchronous, which is what kept it invisible, and BlockHound
 * did not catch it because the test mocked the sender.</p>
 *
 * <p>A Kafka send is the same shape of hazard: {@code StreamBridge.send} resolves a binding, serializes and hands off
 * to the producer, and none of that is guaranteed non-blocking. So it goes to the bounded-elastic scheduler, and
 * {@code PatientEventPublisherUnitTest.publishingRunsOffTheCallingThread} asserts the property directly
 * rather than trusting BlockHound to notice — though on this occasion BlockHound did, when the envelope was still
 * being built on the calling thread.</p>
 *
 * <h2>Publishing never fails the operation</h2>
 *
 * <p>The account already exists by the time anything is published. Losing an event costs observability; failing a
 * registration because the broker was unreachable would cost somebody their account. Fire-and-forget, with failures
 * logged — <em>do not</em> make the caller wait on this, and do not propagate the error.</p>
 *
 * <h2>Which identifier the subject carries, now that two are on this topic</h2>
 *
 * <p><strong>{@code subject.accountId} is this gateway's own {@code User.id}.</strong> It is the account, it exists
 * from the moment registration returns, and it is the left-hand side of the estate's
 * {@code account.id = profile.accountId} join — which is what hc-admin's {@code Patient.accountId} is sourced from
 * (their backlog item 115).</p>
 *
 * <p><strong>It is not {@code hc-patient-service}'s {@code patientId}</strong>, which rides on
 * {@code OnboardingStarted} under that name in that service's own copy of the envelope. Both identifiers are
 * therefore on {@code patient-events} at once, they are about the same person, they look alike, and <em>the only
 * thing that tells them apart is the field name</em>. A consumer that reads whichever one is present would silently
 * prefer the {@code patientId} — an identifier hc-patient is actively retiring (its backlog item 54). Read
 * {@code accountId}, by name.</p>
 *
 * <h2>Why this takes a {@link User} rather than the three strings it needs</h2>
 *
 * <p>Because the id had been structurally absent rather than merely forgotten: the signature was
 * {@code publish(type, email, login, data)} and the subject's third component was a hardcoded {@code null}, so
 * <em>every</em> event this class had ever emitted was id-less and no call site could have fixed it. Taking the
 * account whole means there is no argument for a future call site to pass {@code null} to, and the id, the email and
 * the login cannot disagree about who the event is about. "Every event carries the account id" is then a property of
 * the signature rather than of the four call sites that happened to be audited —
 * {@code PatientEventPublisherUnitTest.theOnlyWayToPublishIsWithAnAccount} pins it.</p>
 */
@Service
public class PatientEventPublisher {

    /** Mapped to the {@code patient-events} destination in {@code application.yml}. */
    public static final String BINDING = "patientEvents-out-0";

    /** Read by {@code messageKeyExpression}, so one patient's events share a partition and therefore an order. */
    public static final String KEY_HEADER = "patientKey";

    private static final String SOURCE = "patientGateway";

    private final Logger log = LoggerFactory.getLogger(PatientEventPublisher.class);

    private final StreamBridge streamBridge;

    public PatientEventPublisher(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }

    /**
     * Publishes without making the caller wait.
     *
     * @param type see {@link PatientEventType}.
     * @param account the account the event is about. Its {@code email} is the correlation key, lowercased here so
     *                both services agree without having to remember to; its {@code id} becomes
     *                {@code subject.accountId} — read the class comment for which identifier that is, because the
     *                topic now carries two.
     * @param data the payload. Nothing clinical belongs on this stream — this service has none to leak, but the rule
     *             is the stream's rather than any one producer's.
     */
    public void publish(String type, User account, Map<String, Object> data) {
        String email = account == null ? null : account.getEmail();
        String login = account == null ? null : account.getLogin();
        String accountId = account == null ? null : account.getId();
        String key = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
        Map<String, Object> payload = data == null ? Map.of() : Map.copyOf(data);

        if (accountId == null) {
            // Still published: a frame with a key is one a consumer can attribute, and losing it costs more than the
            // missing id does. Said out loud because the failure it precedes is on somebody else's side of the wire —
            // hc-admin's Patient.accountId is @NotNull, so an id-less frame dead-letters over there while both stacks
            // look healthy and the consumer group sits at lag zero. The only way to get here is an unsaved account.
            log.warn("Publishing {} for an account with no id — a consumer keying on subject.accountId cannot use it", type);
        }

        // Everything happens on the other thread, not just the send. Building the envelope calls
        // UUID.randomUUID(), which draws on SecureRandom and can block — BlockHound caught exactly that here, and
        // rightly: a registration is handled on a Netty event loop, and anything that can block on one eventually
        // does, for every other request sharing that thread.
        Mono.fromRunnable(() -> {
            PatientEvent event = new PatientEvent(
                UUID.randomUUID().toString(),
                type,
                PatientEvent.VERSION,
                Instant.now(),
                SOURCE,
                new PatientEvent.Subject(key, login, accountId),
                payload
            );
            boolean sent = streamBridge.send(
                BINDING,
                MessageBuilder.withPayload(event).setHeader(KEY_HEADER, key == null ? "" : key).build()
            );
            if (!sent) {
                // StreamBridge answers false for a binding it could not resolve rather than throwing — a
                // mis-wired producer failing quietly.
                log.warn("Publishing {} was refused by the binder — check the {} binding", type, BINDING);
            }
        })
            .subscribeOn(Schedulers.boundedElastic())
            .doOnError(e -> log.warn("Could not publish {} — the account is unaffected", type, e))
            .onErrorComplete()
            .subscribe();
    }
}
