package net.jojoaddison.service.event;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
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
 * {@code PatientEventPublisherIT} asserts the property directly rather than trusting BlockHound to notice.</p>
 *
 * <h2>Publishing never fails the operation</h2>
 *
 * <p>The account already exists by the time anything is published. Losing an event costs observability; failing a
 * registration because the broker was unreachable would cost somebody their account. Fire-and-forget, with failures
 * logged — <em>do not</em> make the caller wait on this, and do not propagate the error.</p>
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
     * @param email the correlation key; lowercased here so both services agree without having to remember to.
     * @param login the account's login.
     * @param data the payload. Nothing clinical belongs on this stream — this service has none to leak, but the rule
     *             is the stream's rather than any one producer's.
     */
    public void publish(String type, String email, String login, Map<String, Object> data) {
        String key = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
        Map<String, Object> payload = data == null ? Map.of() : Map.copyOf(data);

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
                new PatientEvent.Subject(key, login, null),
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
