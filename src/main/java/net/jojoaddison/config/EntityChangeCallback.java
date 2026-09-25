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
                // Only reachable when saves have been failing between the two events. Worth a line, because the visible
                // symptom otherwise is inserts quietly reported as updates once the cap is hit.
                LOG.warn("Clearing {} unresolved pending inserts — some saves did not complete", PENDING_INSERTS.size());
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
     * carries the probe and what a later change would have to add.</p>
     */
    private void publish(String entityType, String entityId, EntityChangeAction action) {
        try {
            publisher.publish(entityType, entityId, action, null);
        } catch (RuntimeException e) {
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
