package net.jojoaddison.config;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import net.jojoaddison.service.event.AuditedEntities;
import net.jojoaddison.service.event.EntityChangeAction;
import net.jojoaddison.service.event.EntityEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.mongodb.core.mapping.event.AbstractMongoEventListener;
import org.springframework.data.mongodb.core.mapping.event.AfterDeleteEvent;
import org.springframework.data.mongodb.core.mapping.event.AfterSaveEvent;
import org.springframework.data.mongodb.core.mapping.event.BeforeConvertEvent;
import org.springframework.stereotype.Component;

/**
 * Observes every document this gateway writes or removes, and publishes one {@code patient.event} frame for each
 * audit-worthy one.
 *
 * <p>Backlog item 45's gateway half. {@link AbstractMongoEventListener}{@code <Object>} fires for <strong>every
 * collection</strong>, which is exactly the scope the estate decision asks for — one channel per product carrying all
 * entity CRUD. The api's {@code EntityChangeCallback} and hc-admin's {@code AuditLogCallback} are the references; this
 * class departs from both in two measured ways, each argued below.</p>
 *
 * <h2>✅ It really does fire in a reactive application — established by invocation, not by reading bytecode</h2>
 *
 * <p>This was item 45's deciding unknown and the answer was taken twice. A constant-pool read of
 * {@code ReactiveMongoTemplate} (spring-data-mongodb 5.0.5, the version resolved through Boot 4.0.6 → spring-data-bom
 * 2025.1.5) shows {@code maybeEmitEvent} and all five lifecycle events — but a reference is not an invocation.
 * <strong>Measured 2026-09-25: a {@code User} save through {@code UserRepository} fires {@code BeforeConvertEvent},
 * {@code BeforeSaveEvent} and {@code AfterSaveEvent}, and a {@code remove} fires {@code BeforeDeleteEvent} and
 * {@code AfterDeleteEvent}.</strong> Independently corroborated by this application's own
 * {@code ValidatingMongoEventListener} — itself an {@code AbstractMongoEventListener} bean in
 * {@link DatabaseConfiguration} — which refuses a {@code User} with an illegal login at save time, and could not do so
 * if these events were not emitted.</p>
 *
 * <p>⚠ <strong>The first version of that probe reported the opposite, and the reason is worth more than the result.</strong>
 * It registered its listener as a nested {@code @TestConfiguration}, which was never picked up, so nothing fired and the
 * conclusion "reactive templates do not emit events" was one assertion away from being reported as fact. What caught it
 * was an identity check — asserting the probe's own bean was present before trusting its silence. A listener that is not
 * registered and a listener that is never called are indistinguishable from the assertion's point of view.</p>
 *
 * <h2>⛔ Why this cannot use the api's {@code ThreadLocal}, and what would have broken silently</h2>
 *
 * <p>An insert and an update are only distinguishable before the save — at {@code BeforeConvertEvent} a document about to
 * be inserted still has a null id — so the answer has to be carried across two events. The api carries it in a
 * {@code ThreadLocal}, documented there as safe because "the two events for one save happen on the thread that called
 * {@code save}".</p>
 *
 * <p><strong>In this reactive gateway that is false, and it was measured:</strong></p>
 *
 * <pre>
 * beforeConvert:User thread=main                          identity=1093909489
 * afterSave:User     thread=multiThreadIoEventLoopGroup-2-3 identity=1093909489
 * </pre>
 *
 * <p>{@code BeforeConvertEvent} arrives on the subscribing thread; {@code AfterSaveEvent} arrives on the MongoDB
 * reactive driver's IO event loop. So a {@code ThreadLocal} written by the first is invisible to the second.
 * <strong>Copying the api's class would have produced a listener on which no frame ever says {@code CREATED}</strong> —
 * every insert reported as {@code UPDATED} — plus a reference leak, because each pending entry would sit unread on a
 * pooled thread for the life of the application. Every test would have passed; the defect is only visible if you assert
 * the action, and only explicable if you look at the threads.</p>
 *
 * <p>The identity, however, <em>is</em> stable across the two events (note the equal {@code identity} above), so the set
 * below is keyed on identity and shared across threads rather than confined to one. {@code IdentityHashMap} because two
 * distinct new documents with all-null fields are {@code equals} to one another and are not the same insert — and
 * because {@code User.equals} is by id, which is null for precisely the inserts being tracked.</p>
 *
 * <h2>Why a listener and not a call at each write</h2>
 *
 * <p>The alternative — a publish beside each {@code repository.save} — covers the writes somebody remembered and
 * silently misses the rest. {@code UserService} alone saves from registration, activation, password reset, profile
 * update, failed-login counting and care-angel creation, and {@link net.jojoaddison.service.event.DeletionAccountCloser}
 * saves from a Kafka consumer. A listener covers all of them the day it is added.</p>
 *
 * <h2>⚠ What this cannot see</h2>
 *
 * <p>Spring Data raises these events for {@code save}, {@code insert} and {@code remove} going through a template or a
 * repository built on one. <strong>Query-based updates raise no event at all</strong> — {@code updateFirst},
 * {@code updateMulti}, {@code upsert} and {@code findAndModify} change documents without materialising one. This gateway
 * has one such call site, {@code ClinicalDisciplineRolesMigration}'s {@code template.remove(Query...)}, and it operates
 * on {@link net.jojoaddison.domain.Authority}, which is suppressed anyway — so the gap costs nothing today. It is named
 * because the next query-based write added here would be invisible to this stream with nothing reporting the gap.</p>
 *
 * <h2>Self-recursion does not arise, and that is worth saying</h2>
 *
 * <p>hc-admin's version must exclude its own collection, because it <em>writes</em> an audit row and saving one fires
 * the listener that saves another. This class writes nothing to Mongo: it publishes to a topic, and this gateway
 * consumes neither {@code patient.event} nor anything derived from it. ⛔ <strong>So do not add a Mongo write to this
 * class or anything it calls</strong> — the absence of a self-exclusion is a consequence of that, not an oversight.</p>
 */
@Component
public class EntityChangeCallback extends AbstractMongoEventListener<Object> {

    private static final Logger LOG = LoggerFactory.getLogger(EntityChangeCallback.class);

    /**
     * Documents seen at {@code BeforeConvertEvent} with no id yet — which is to say, inserts.
     *
     * <p>Identity-based and <strong>synchronized rather than thread-confined</strong>, because the two events arrive on
     * different threads in this application. See the class javadoc for the measurement.</p>
     *
     * <p><strong>The leak has a named, ordinary trigger, not just "a save that throws".</strong>
     * {@code ValidatingMongoEventListener} ({@link DatabaseConfiguration}) raises at {@code BeforeSaveEvent} — which
     * falls <em>between</em> the two events here — so <strong>every bean-validation rejection leaks exactly one
     * entry</strong>. A registration with an over-long name does it. That is a slow drip bounded by the cap below,
     * not a hazard, but it is a normal path rather than an exceptional one and the cap should be read in that light.</p>
     *
     * <p>⚠ <strong>Identity keying assumes the saved object is the same instance at both events.</strong> It is, for a
     * mutable class like {@code User}. A future domain type declared as a {@code record} would break that — Spring Data
     * returns a new instance for an immutable entity — so every insert would be reported {@code UPDATED} and every
     * entry would leak. It fails noisily (the cap's WARN, and {@code EntityChangeCallbackIT}'s {@code CREATED}
     * assertion) rather than silently, which is the better direction, but it is worth knowing before adding one.</p>
     */
    private static final Set<Object> PENDING_INSERTS = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

    /**
     * A save that throws between the two events leaves its entry behind. The cap bounds that: a leak costs at most this
     * many references and then resets, where an unbounded set would hold every failed write for ever.
     *
     * <p>It matters more here than in the api's thread-confined version, because this set is process-wide rather than
     * per-thread — so every failed save in the application accumulates in the same place.</p>
     */
    private static final int PENDING_INSERTS_CAP = 256;

    private final EntityEventPublisher publisher;

    /**
     * Lazy because the publisher is built on the {@code StreamBridge}, and this listener is created while the
     * {@code MongoTemplate} that raises these events is still being wired — injecting eagerly closes a cycle at context
     * startup. The api's callback and hc-admin's carry the same note for the same reason.
     */
    public EntityChangeCallback(@Lazy EntityEventPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * Remembers that this document is about to be inserted.
     *
     * <p>The only point at which an insert and an update are distinguishable. See {@link EntityChangeAction} for the one
     * case this gets conservatively wrong.</p>
     */
    @Override
    public void onBeforeConvert(BeforeConvertEvent<Object> event) {
        Object source = event.getSource();
        if (source == null || !AuditedEntities.isPublished(source.getClass()) || idOf(source) != null) {
            return;
        }
        synchronized (PENDING_INSERTS) {
            if (PENDING_INSERTS.size() >= PENDING_INSERTS_CAP) {
                // Reached when saves have been failing between BeforeConvertEvent and AfterSaveEvent — most often a
                // bean-validation rejection, which raises at BeforeSaveEvent, between the two. See the field javadoc.
                //
                // ⚠ The clear is indiscriminate and the message says so: entries mid-save are dropped along with the
                // leaked ones, and each of those then publishes UPDATED for what was really an insert. That is the
                // trade — a bounded set that occasionally mislabels, against an unbounded one that never forgets a
                // failed write. The message must not claim these are all unresolved; it cannot tell which are.
                LOG.warn(
                    "Clearing all {} pending inserts to stay bounded — some are leaked, some may be saves still in flight, " +
                    "and those will be reported as UPDATED rather than CREATED",
                    PENDING_INSERTS.size()
                );
                PENDING_INSERTS.clear();
            }
            PENDING_INSERTS.add(source);
        }
    }

    @Override
    public void onAfterSave(AfterSaveEvent<Object> event) {
        Object source = event.getSource();
        if (source == null || !AuditedEntities.isPublished(source.getClass())) {
            return;
        }
        // Removed whether or not it was present, so the entry never outlives the save that created it.
        boolean wasInsert = PENDING_INSERTS.remove(source);
        publish(source.getClass().getSimpleName(), idOf(source), wasInsert ? EntityChangeAction.CREATED : EntityChangeAction.UPDATED);
    }

    @Override
    public void onAfterDelete(AfterDeleteEvent<Object> event) {
        // A delete reports the query it matched, because after the fact there is nothing else left to report. The type
        // is what decides whether this collection is published at all, so a delete that does not name one is refused
        // rather than guessed: a collection name would have to be mapped back to a class, and mapping it wrongly
        // publishes an account deletion as something else, or something else as an account deletion.
        if (event.getType() == null) {
            LOG.debug("A delete named no entity type — no frame published");
            return;
        }
        if (!AuditedEntities.isPublished(event.getType())) {
            return;
        }
        Object id = event.getDocument() == null ? null : event.getDocument().get("_id");
        // A delete by criteria yields a query naming no _id, so `publish` refuses it rather than emitting a frame that
        // names nothing — one frame per `remove` call could not name the several documents it removed anyway.
        publish(event.getType().getSimpleName(), id == null ? null : id.toString(), EntityChangeAction.DELETED);
    }

    /**
     * Hands the frame to the publisher.
     *
     * <p>Every failure is caught. This runs inside somebody's write — and on the Mongo driver's event loop — so a stream
     * that cannot describe a change must not be able to prevent it, nor to break the connection describing it.</p>
     *
     * <p>The actor is passed as {@code null} unconditionally and that is the measured answer rather than a placeholder:
     * the reactive security context does not reach a Mongo lifecycle listener. {@link EntityEventPublisher}'s javadoc
     * carries the probe, the decision to omit the key rather than send a null, and what a later change would have to
     * add.</p>
     *
     * <h2>⛔ Why this catches {@link Throwable} and not {@link RuntimeException}</h2>
     *
     * <p>Because an {@link Error} raised on this path <strong>kills the write</strong>, and that was measured rather
     * than imagined. A blocking call planted at the top of {@code publish} raised BlockHound's
     * {@code BlockingOperationError} — an {@code Error}, not an exception — which sailed through a
     * {@code catch (RuntimeException)}, propagated into the save pipeline and failed
     * {@code userRepository.save(...)} itself. A publisher had become the reason a registration failed, which is the
     * one thing every javadoc in this package promises cannot happen.</p>
     *
     * <p>⚠ <strong>The cost, stated because it is real:</strong> BlockHound's error is now swallowed here too, so a
     * future blocking call added to the publish path no longer fails a test by killing the save. It is not thereby
     * invisible — the frame is never sent, so {@code ErasureEntityEventIT} goes red on a missing frame instead. That is
     * a worse diagnostic and a better failure mode: the symptom lands on the publishing path that caused it rather than
     * on a patient's write. (BlockHound is test-scope, so in production the members of this category are
     * {@code NoClassDefFoundError} and friends, where swallowing is plainly right.)</p>
     *
     * <h2>⛔ …except {@link VirtualMachineError}, which is rethrown</h2>
     *
     * <p>Because for that one the log line below is <em>a lie</em>. "The record is unaffected" cannot be honoured after
     * an {@link OutOfMemoryError}: the JVM is in an undefined state, the write may well have failed, and every later
     * allocation fails anyway — so swallowing it would print a reassurance nobody can stand behind and carry on. A
     * message asserting a property the code does not have is this estate's recurring defect class, and a gateway
     * fronting the whole subsystem is the worst place to add one.</p>
     *
     * <p>This <strong>keeps</strong> the trade above rather than reopening it: {@code BlockingOperationError} and
     * {@code NoClassDefFoundError} are {@code Error}s but <em>not</em> {@code VirtualMachineError}s, so they are still
     * caught and a write still survives them.</p>
     *
     * <p>⚠ One known over-reach, recorded rather than smoothed over: {@link StackOverflowError} is also a
     * {@code VirtualMachineError} and, unlike the others, is usually <em>recoverable</em> — the stack unwinds and the
     * thread is fine — so rethrowing it fails a write for a condition that did not need to.</p>
     *
     * <p><strong>It is accepted for a reason that does not depend on reachability, because a reachability argument
     * would not survive scrutiny.</strong> Swallowing a {@code StackOverflowError} is not the safe direction either:
     * the handler below needs stack of its own to run — {@code LOG.warn} with a throwable drives Logback's whole
     * appender chain, which is the deepest thing on this thread — so it can simply re-trip, and a write continuing on
     * a near-exhausted stack will very likely blow again in the driver's continuation, outside any catch at all. The
     * two branches differ little in outcome, and the rethrow is the one that <em>surfaces</em> the condition instead of
     * reporting it as handled.</p>
     *
     * <p>⛔ <strong>Do not restate this as "a StackOverflowError is unreachable here" — an earlier draft did, and it
     * was wrong.</strong> An SOE does not need the frame that trips it to be recursive; it fires when the stack is
     * <em>already</em> near exhaustion and the victim can be arbitrary and shallow. This listener runs mid-pipeline
     * under Netty, the reactive Mongo driver, a Reactor operator chain and
     * {@code SimpleApplicationEventMulticaster}. The accurate claim is the narrower one: <em>nothing on this path
     * recurses</em> — the guarded call builds a one-entry {@code LinkedHashMap} (one, not two: since the actor key is
     * omitted and this gateway can never name an actor, every payload is {@code action} alone), walks it twice, and
     * hands off to {@code sender.execute} — so only an already-exhausted stack gets you here.</p>
     *
     * <p>Narrowing the rethrow to {@code OutOfMemoryError} was the alternative and was declined: {@code InternalError}
     * and {@code UnknownError} carry exactly the same false reassurance, and enumerating the unrecoverable subtypes is
     * the list that goes stale.</p>
     */
    private void publish(String entityType, String entityId, EntityChangeAction action) {
        try {
            publisher.publish(entityType, entityId, action, null);
        } catch (VirtualMachineError e) {
            // The JVM is unrecoverable. Swallowing this would log a reassurance we cannot honour. Let it go up.
            throw e;
        } catch (Throwable e) {
            LOG.warn("Could not publish the {} of a {} — the record is unaffected", action, entityType, e);
        }
    }

    /**
     * The document's own id, by reflection, because the domain types share no interface that exposes one.
     *
     * <p>The id and nothing else. Every field beside it on a {@code User} is identifying content.</p>
     */
    private static String idOf(Object entity) {
        try {
            Method getId = entity.getClass().getMethod("getId");
            Object id = getId.invoke(entity);
            return id == null ? null : id.toString();
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
