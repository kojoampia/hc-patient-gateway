package net.jojoaddison.service.event;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * Publishes every account change in this gateway to {@code patient.event}.
 *
 * <p>Backlog item 45's gateway half, under the architect's decision of 2026-09-25: <strong>the gateway publishes all of
 * its entity CRUD to {@code patient.event}</strong>, not a curated account subset and not staying on
 * {@code patient-events}. The api shipped the other half on 2026-09-18 and this envelope is a deliberate copy of its
 * one — see {@link EntityEvent}. hc-admin consumes both into their audit trail (their item 109, not yet built).</p>
 *
 * <h2>Why this topic, and what the two declined shapes would have cost</h2>
 *
 * <p><strong>Staying on {@code patient-events}</strong> was free — the binding already exists — and was declined for a
 * reason that is easy to miss and is the strongest argument for the shape that was chosen: it would have
 * <strong>blocked backlog item 46 permanently.</strong> Item 46 exists to retire {@code patient-events}; if the fact
 * hc-admin needs to honour an erasure lived there, that topic could never be retired, and the migration item 46
 * describes would have no end state. It also costs hc-admin two subscriptions to make one erasure decision.</p>
 *
 * <p><strong>{@code patient.event} carrying account facts only</strong> was smaller and was declined because the estate
 * rule is one channel per <em>product</em> carrying every entity CRUD, and the gateway and the api are one product. A
 * curated subset means the next field a consumer wants is another change in two repositories, which is the pattern that
 * produced hc-admin's items 25, 47 and 54.</p>
 *
 * <p><strong>{@code patient-events} is untouched.</strong> It keeps its live hc-admin consumer
 * (<i>hc-admin-directory-patient</i>) and this subsystem's own {@link PatientEventMailRouter}. Add first, remove
 * nothing.</p>
 *
 * <h2>⚠ The actor is ALWAYS null on this stream, and that is measured rather than conceded</h2>
 *
 * <p>The api resolves an actor through {@code ActorAccountId}, which reads {@code RequestContextHolder} — a servlet
 * request scope that <strong>does not exist in this reactive gateway at all</strong>. The reactive equivalent was
 * probed on 2026-09-25 rather than assumed, and the answer is negative: {@code SecurityUtils.getCurrentUserLogin()}
 * returns {@code Mono<String>} sourced from {@code ReactiveSecurityContextHolder}, which reads the <em>Reactor
 * subscriber context</em>. A Mongo lifecycle listener is handed no context and subscribes afresh, so the Mono is
 * <strong>empty — observed 15 times out of 15, including for a save explicitly wrapped in
 * {@code contextWrite(ReactiveSecurityContextHolder.withAuthentication(...))}.</strong> The context does not propagate
 * there; it is not a ThreadLocal and there is nothing on the thread to read.</p>
 *
 * <p>Two further things would have to be true even if it did propagate, and neither is: the context yields a
 * <strong>login</strong>, not an id, and this gateway's own token carries no id claim either
 * ({@code AuthenticateController} builds {@code sub}, {@code auth} and {@code email} — backlog item 56 is that gap).
 * So naming an actor would need a login → {@code User.id} database read <em>inside a write callback, on the Mongo
 * driver's own event loop</em>, which cannot be awaited without blocking the thread that every other query on this
 * connection is using.</p>
 *
 * <p><strong>So a null actor here is honest, and the subject carries the part that matters.</strong> For a {@code User}
 * write the subject <em>is</em> the account — {@code entityId} is the {@code User.id} — so a frame says <em>whose</em>
 * account changed and how, and declines to say who did it. hc-admin's erasure decision needs the former. And for the
 * headline path the distinction is almost moot: {@link DeletionAccountCloser} deactivates in response to the api's
 * {@code DELETION_REQUEST_CHANGED} frame, so the actor genuinely is not a person — it is this gateway reacting to a
 * message. A perfect actor implementation would report null for that write too.</p>
 *
 * <p>⛔ <strong>It is carried explicitly, never omitted</strong> — the api's design decision, preserved here, so a
 * consumer can tell <em>no actor</em> from <em>this producer stopped sending the field</em>. A future change that wanted
 * a real actor would need one of: an id claim on the JWT (item 56, which touches a token three products validate
 * against a shared key), or an actor threaded explicitly from each service call site into the publisher — which is the
 * shape the listener exists to avoid, because it covers the writes somebody remembered. Do not attempt it by enabling
 * {@code Hooks.enableAutomaticContextPropagation()}: that changes operator behaviour across a gateway that routes an
 * entire subsystem, and it would still yield a login rather than an id.</p>
 *
 * <h2>⚠ Nothing here may run on the calling thread, and in this gateway that thread is the Mongo driver's</h2>
 *
 * <p>{@link StreamBridge} creates an output binding <em>lazily, inside the first {@code send()} for that
 * destination</em>, and that creation opens an AdminClient bounded by {@code default.api.timeout.ms} — sixty seconds
 * against a broker that is not there, under a lock every later publisher queues on. hc-admin measured it: a first
 * {@code POST} took 60.6s and the second 15ms, with nothing failing.</p>
 *
 * <p><strong>Measured here 2026-09-25, and it is worse than the item anticipated.</strong> The brief expected the
 * listener to run on "the reactive pipeline thread". It does not: {@code BeforeConvertEvent} arrives on whichever thread
 * subscribed ({@code main}, {@code parallel-1}, in production a Netty event loop) but {@code AfterSaveEvent} — the one
 * that publishes — arrives on <strong>{@code multiThreadIoEventLoopGroup-2-3}, the MongoDB reactive driver's own IO
 * event loop.</strong> Blocking there does not stall one request; it stalls <em>every database operation on that
 * connection</em>, which for this gateway means user lookups for every login in flight. BlockHound may or may not police
 * that particular group, so this is not left to BlockHound — {@code EntityEventPublisherTest} asserts the sending
 * thread directly, which is the technique {@code PatientEventPublisherUnitTest} adopted after BlockHound missed a
 * blocking send behind a mock.</p>
 *
 * <h2>Why a private bounded executor and not {@code Schedulers.boundedElastic()}</h2>
 *
 * <p>{@code boundedElastic} is this repository's house idiom and {@link PatientEventPublisher} uses it, so the
 * departure needs a reason rather than a preference. Both shapes get the send off the calling thread without blocking
 * it, so that is not the differentiator. <strong>Isolation is.</strong> {@code boundedElastic} is shared with
 * everything else here that needs to leave a reactive thread — the mail sender, {@link DeletionAccountCloser}'s own
 * account write, authority resolution — and <em>this</em> publisher fires on every write rather than on seven curated
 * lifecycle moments. Against a hung broker each send occupies a thread for a minute, so a write-heavy minute of broker
 * trouble would queue erasure closures and registration mail behind audit frames nobody is waiting for. A private
 * single-thread pool with a bounded queue makes this stream's loss its own load. It also matches the api's half of the
 * same product, so both behave alike during an outage.</p>
 *
 * <p>⛔ <strong>The rejection policy must stay {@link ThreadPoolExecutor.AbortPolicy}.</strong> {@code CallerRunsPolicy}
 * is the conventional choice for a bounded queue and it would silently restore exactly the defect this class exists to
 * prevent — handing the sixty-second send back to the caller the moment the queue fills, which is the moment the broker
 * is slowest, and here the caller is the Mongo driver's event loop. {@code AbortPolicy} is also what makes
 * {@code execute} non-blocking: it throws instead of waiting for a slot. {@code EntityEventPublisherTest} pins it,
 * because the defect is a one-word edit.</p>
 *
 * <h2>Losing a frame is allowed. Delaying or failing a write is not.</h2>
 *
 * <p>By the time anything is published the write has already happened. There is no outbox and no transaction to hang
 * one on — Mongo runs standalone here — so best-effort after a successful write is the honest design. Every failure is
 * caught and logged, the queue is bounded, and a full queue drops the frame rather than growing without limit.</p>
 */
@Component
public class EntityEventPublisher {

    /** The binding name; {@code application.yml} maps it to the {@code patient.event} destination — in BOTH files. */
    public static final String BINDING = "entityEvents-out-0";

    /**
     * The header the Kafka binder reads to choose a partition key, via {@code messageKeyExpression}.
     *
     * <p>The <strong>entity id</strong>, not the actor and not a patient. Two changes to one document must stay in order
     * or an audit trail reports them in whichever order two partitions happened to be drained, which for a
     * create-then-delete pair is a trail saying the document still exists. Note this is the opposite choice from
     * {@link PatientEventPublisher#KEY_HEADER}, which keys on the patient — and for the opposite reason: that stream
     * answers "what happened to this person, in what order", this one answers it about a document.</p>
     */
    public static final String KEY_HEADER = "entityKey";

    /**
     * Which service emitted the frame.
     *
     * <p>⚠ <strong>Deliberately NOT {@code hcPatientService}, which is what the api sends.</strong> The gateway and the
     * api are one product publishing to one topic, so reusing the api's value was the tempting choice and it would have
     * made this half misreport its own origin — an audit trail whose "source" column is wrong is worse than one with an
     * extra value in it. The cost is real and is stated rather than hidden: {@code patient.event} now carries
     * <em>two</em> source values for one product, so a consumer grouping by source sees two rows where a reader of the
     * estate's "one channel per product" rule might expect one.</p>
     *
     * <p>Honesty wins that trade because it is the recoverable direction: <strong>a consumer can coalesce two sources
     * into one product, and cannot recover a distinction that was never on the wire.</strong> It matters now rather than
     * later precisely because hc-admin's consumer <em>does not exist yet</em> — nothing reads this topic, so the value
     * is free to get right today and becomes a contract the moment their item 109 ships. If they ever key audit rows by
     * producer, this choice is already made for them, and it is made in the direction that lets them decide.</p>
     *
     * <p>It matches this gateway's other stream, where {@link PatientEventPublisher} already sends
     * {@code patientGateway} — so a consumer reading both of this service's topics sees one name for one service.</p>
     */
    public static final String SOURCE = "patientGateway";

    /**
     * Every key a frame on this stream may carry — a closed allowlist, checked at runtime.
     *
     * <p><strong>An allowlist, where {@link PatientEventPublisher} deliberately uses a denylist.</strong> That one
     * publishes payloads shaped by many call sites for many event types, so an unknown key is a new field somebody is
     * adding and the right answer is to refuse loudly at that point. This payload is a closed shape built by one class:
     * there is no legitimate new key, so anything unrecognised is a mistake, and an allowlist catches the mistake this
     * design is most exposed to — a field value reaching the wire because somebody found it useful. The denylist is
     * applied as well, in {@link #assertNothingClinical}, because it costs nothing and the two fail differently.</p>
     */
    private static final Set<String> ALLOWED_KEYS = Set.of(EntityEvent.ACTION, EntityEvent.ACTOR_ACCOUNT_ID);

    /**
     * Keys that would be identifying or clinical content, refused outright.
     *
     * <p>⚠ <strong>Copied from {@code hc-patient-service}'s {@code PatientEventPublisher.assertNothingClinical}, because
     * this gateway had no such guard — a finding of this item.</strong> Backlog item 45's brief described the gateway's
     * {@code PatientEventPublisher} as "the source of {@code assertNothingClinical}"; it is not, and a grep of
     * {@code src/main} for it returns nothing. The rule is the <em>stream's</em> rather than any one producer's, and both
     * halves of this product write to {@code patient.event}, so the guard belongs on both sides of it.</p>
     *
     * <p>The list is the api's twenty-three keys plus the four this gateway is the only place that could leak —
     * {@code login}, {@code email}, {@code password} and the two key fields — because a {@code User} is exactly the
     * document those live on. An account is not clinical and is squarely identifying, which is the same prohibition
     * arriving by the other of {@link EntityEvent}'s two independent rules.</p>
     */
    private static final Set<String> FORBIDDEN_KEYS = Set.of(
        "bloodgroup",
        "allergy",
        "allergies",
        "medication",
        "medications",
        "condition",
        "conditions",
        "cardnumber",
        "cardtype",
        "address",
        "diagnosis",
        "symptoms",
        "height",
        "weight",
        "systolic",
        "diastolic",
        "heartrate",
        "bloodsugar",
        "value",
        "reading",
        "readings",
        "note",
        "notes",
        "login",
        "email",
        "password",
        "activationkey",
        "resetkey"
    );

    /**
     * Bounded, and small on purpose.
     *
     * <p>The queue exists to absorb a burst, not to buffer an outage. Against an absent broker every send blocks for a
     * minute, so a large queue would hold minutes of stale frames and report nothing; a small one starts dropping — with
     * a log line and a counter each — while the write path stays at full speed.</p>
     */
    private static final int QUEUE_CAPACITY = 512;

    private final Logger log = LoggerFactory.getLogger(EntityEventPublisher.class);

    private final StreamBridge streamBridge;

    private final ThreadPoolExecutor sender;

    private final Counter droppedFrames;

    public EntityEventPublisher(StreamBridge streamBridge, MeterRegistry meterRegistry) {
        this.streamBridge = streamBridge;
        this.droppedFrames = Counter.builder("events.publishing.dropped")
            .baseUnit("events")
            .description("Frames dropped because the publisher's queue was full. The write always succeeded; the event was lost.")
            .tag("topic", "patient.event")
            .register(meterRegistry);
        this.sender = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            runnable -> {
                Thread thread = new Thread(runnable, "entity-event-publisher");
                // Daemon so a queue still draining cannot hold a shutdown open; drain() gives it a bounded chance first.
                thread.setDaemon(true);
                return thread;
            },
            // ⛔ Not CallerRunsPolicy. See the class javadoc — the caller here is the Mongo driver's event loop.
            new ThreadPoolExecutor.AbortPolicy()
        );
    }

    /**
     * Builds the envelope on the calling thread and hands the send to the sender thread.
     *
     * @param entityType the simple class name of the domain type whose document changed. Carried in
     *     {@link EntityEvent.Subject}.
     * @param entityId the document's own id; a frame without one names nothing and is refused. Carried in
     *     {@link EntityEvent.Subject}.
     * @param action what happened. Carried in {@code data}.
     * @param actorAccountId the gateway {@code User.id} of whoever made the change, or null when this service cannot
     *     name them — which in this gateway is <strong>always</strong>, for the reasons in the class javadoc. Carried in
     *     {@code data} explicitly, null and all. Null is a legitimate frame, unlike a null entity id.
     */
    public void publish(String entityType, String entityId, EntityChangeAction action, String actorAccountId) {
        if (entityType == null || entityType.isBlank() || entityId == null || entityId.isBlank() || action == null) {
            // Refused rather than sent. An audit row needs to name the thing that changed; a frame that cannot is one no
            // consumer can turn into a row, and publishing it would move the diagnosis into somebody else's log.
            log.warn("Not publishing an entity change — entityType, entityId or action was absent");
            return;
        }

        // LinkedHashMap rather than Map.of so the payload serializes in the order it is written here, and because
        // Map.of refuses a null value, which an unnameable actor is. Map.of's iteration order is randomised per JVM.
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(EntityEvent.ACTION, action.name());
        // Put unconditionally, null and all, so the payload has one shape rather than two.
        data.put(EntityEvent.ACTOR_ACCOUNT_ID, actorAccountId);
        assertIdentifiersOnly(data);
        // Defence in depth, and a different failure: the denylist catches a forbidden key that somehow reached a
        // payload, the allowlist catches any key that is not one of the two. Neither subsumes the other.
        assertNothingClinical(data);

        EntityEvent event = new EntityEvent(
            UUID.randomUUID().toString(),
            EntityEvent.TYPE,
            EntityEvent.VERSION,
            Instant.now(),
            SOURCE,
            new EntityEvent.Subject(entityType, entityId),
            data
        );

        try {
            sender.execute(() -> send(event, entityId));
        } catch (RejectedExecutionException e) {
            // The queue is full, which means the broker is not draining it. Dropping is the design; see the class
            // javadoc on why this must never become CallerRunsPolicy. Counted as well as logged — a run of drops must
            // be a graph, not a grep.
            droppedFrames.increment();
            log.warn("Dropped an entity change for {} — the publishing queue is full", entityType);
        }
    }

    private void send(EntityEvent event, String key) {
        try {
            // The boolean is worth reading: StreamBridge answers false for a binding it could not resolve rather than
            // throwing, which is a mis-wired producer failing quietly.
            boolean sent = streamBridge.send(BINDING, MessageBuilder.withPayload(event).setHeader(KEY_HEADER, key).build());
            if (!sent) {
                log.warn("Publishing an entity change was refused by the binder — check the {} binding", BINDING);
            }
        } catch (Exception e) {
            // Deliberately swallowed, and on a thread of its own, so it cannot reach the write that provoked it.
            log.warn("Could not publish an entity change — the record is unaffected", e);
        }
    }

    /**
     * Refuses a payload carrying anything but the action and the actor.
     *
     * <p>Throws rather than stripping: a dropped key would let the caller believe a field is being published, and the
     * next person to read the consumer would wonder why it never arrives. It throws on the <em>calling</em> thread,
     * which is deliberate — this is a programming error rather than a runtime condition, and it should surface in the
     * test that introduced it rather than as a log line on a background thread.</p>
     */
    static void assertIdentifiersOnly(Map<String, Object> data) {
        for (String key : data.keySet()) {
            if (!ALLOWED_KEYS.contains(key)) {
                throw new IllegalArgumentException(
                    "An entity event may not carry '" +
                    key +
                    "'. This stream says that a document changed, never what it now holds — see EntityEvent."
                );
            }
        }
    }

    /**
     * Refuses a payload carrying identifying or clinical content, by name.
     *
     * <p>The denylist half. See {@link #FORBIDDEN_KEYS} for why this gateway needed its own copy of a rule the api
     * already had.</p>
     */
    static void assertNothingClinical(Map<String, Object> data) {
        for (String key : data.keySet()) {
            if (FORBIDDEN_KEYS.contains(key.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException(
                    "An entity event would carry identifying or clinical content in '" +
                    key +
                    "'. Events say that a thing happened, never what it said — see EntityEvent."
                );
            }
        }
    }

    /**
     * Gives the queue a bounded chance to drain on shutdown.
     *
     * <p>Two seconds, because the frames are notifications and a deployment must not wait on a broker that may be the
     * reason it is being redeployed.</p>
     */
    @PreDestroy
    void drain() {
        sender.shutdown();
        try {
            if (!sender.awaitTermination(2, TimeUnit.SECONDS)) {
                log.warn("Shutting down with entity changes still queued — they are lost, and the records are unaffected");
                sender.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sender.shutdownNow();
        }
    }

    /** For the test that pins the rejection policy. The defect it guards against is a one-word edit. */
    ThreadPoolExecutor senderForTest() {
        return sender;
    }
}
