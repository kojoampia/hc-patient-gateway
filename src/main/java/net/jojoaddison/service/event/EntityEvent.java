package net.jojoaddison.service.event;

import java.time.Instant;
import java.util.Map;

/**
 * One entity change in this gateway, on {@code patient.event} — enough for hc-admin to write one audit row and
 * deliberately not one byte more.
 *
 * <h2>⛔ This record is a copy of {@code hc-patient-service}'s and must stay one</h2>
 *
 * <p>Backlog item 45's gateway half. The api shipped this envelope on 2026-09-18 and it is <strong>live on the topic
 * with frames on it</strong>; this class exists because the gateway and the api are separate repositories sharing no
 * module, so "one envelope" is a convention held by two files rather than by a type. Every component name, the
 * {@link #TYPE} literal and {@link #VERSION} are therefore copied deliberately and not re-derived. A consumer reads
 * one shape from one product; the gateway and the api are one product.</p>
 *
 * <p>⚠ <strong>The normative artefact is owed and this makes the debt worse, not better.</strong> The api's copy
 * already records that four products built this envelope from prose on one day and shipped three different shapes.
 * This is now a fifth file carrying the same shape by hand, in a second repository, with nothing mechanical comparing
 * them — so an edit to either side diverges silently and no test anywhere goes red. hc-admin item 124's "done when" is
 * a shared schema, fixture or contract test; until then, changing this file means changing the api's in the same
 * breath.</p>
 *
 * <h2>The subject is the record. The actor rides in {@code data}.</h2>
 *
 * <pre>
 * subject : { entityType, entityId }     the record this event is about
 * data    : { action, actorAccountId }   what happened, and who did it
 * </pre>
 *
 * <p>The estate decision of 2026-09-18 (hc-admin item 124), copied from the api along with the shape: "subject" means
 * <em>the thing this event is about</em>, and an audit row is about a record. Note what that buys this half
 * specifically — for a {@code User} write <strong>the subject is the account</strong>, so a frame names <em>whose</em>
 * account changed even when it cannot name who changed it. That distinction is what makes an always-null actor
 * survivable here; {@link EntityEventPublisher} argues it at length.</p>
 *
 * <p>This gateway's other stream, {@code patient-events}, means something different by {@code subject} — there it is
 * the patient, because that stream is <em>about a patient</em>. Two streams, two meanings, both documented rather than
 * accidental. {@link PatientEventPublisher} is untouched by this and its bytes are a live cross-product contract.</p>
 *
 * <h2>⛔ {@code data} carries identifiers and metadata. Never a changed value.</h2>
 *
 * <p>Two independent rules land on the same payload. hc-admin's item 110: a channel carrying entity <em>contents</em>
 * rebuilds the local mirrors their item 107 exists to delete, so this is an architectural constraint and not only a
 * privacy one. And this subsystem's own rule: a topic is the least controlled copy of anything that enters it.
 * <strong>Here the second rule bites harder than it does in the api</strong>, because the documents this gateway
 * writes are accounts — a {@code User} holds a login, an email, a password hash, an activation key and a reset key.
 * "All entity CRUD" plus "no identifying content" can only both be true if the payload never says what the record now
 * holds.</p>
 *
 * <p>{@link EntityEventPublisher} enforces that at runtime against a closed allowlist of keys, rather than leaving it
 * to whoever next adds a field.</p>
 *
 * @param eventId unique per emission. Delivery is at least once, so duplicates are normal rather than exceptional.
 * @param type always {@link #TYPE}. Present so the envelope stays routable by a consumer reading several of this
 *     estate's streams through one code path.
 * @param version the envelope's schema version, not the payload's.
 * @param occurredAt <strong>when</strong> — one of the five fields an audit row needs.
 * @param source which service emitted it. See {@link EntityEventPublisher#SOURCE}: this is the one component whose
 *     value differs from the api's, and it differs on purpose.
 * @param subject which record changed — {@code entityType} and {@code entityId}.
 * @param data {@code action} and {@code actorAccountId} — and nothing else, ever.
 */
public record EntityEvent(
    String eventId,
    String type,
    int version,
    Instant occurredAt,
    String source,
    Subject subject,
    Map<String, Object> data
) {
    /** The current envelope version. Bump only for a change a consumer cannot ignore — and bump the api's with it. */
    public static final int VERSION = 1;

    /**
     * The one frame kind this stream carries.
     *
     * <p>A single type with the action in the payload, rather than three types — {@code entity.created} and friends —
     * because all three mean the same thing to the only consumer there is: write an audit row.</p>
     */
    public static final String TYPE = "EntityChanged";

    /** Subject component, and the field name a consumer reads: the domain class whose document changed. */
    public static final String ENTITY_TYPE = "entityType";

    /** Subject component, and the field name a consumer reads: the document's own id. */
    public static final String ENTITY_ID = "entityId";

    /** Payload key: one of {@link EntityChangeAction}. */
    public static final String ACTION = "action";

    /**
     * Payload key: the gateway {@code User.id} of whoever made the change.
     *
     * <p>Present on every frame and explicitly {@code null} when this service cannot name the caller, rather than
     * omitted — one payload shape, so a consumer never has to tell "no actor" from "this producer stopped sending the
     * field". <strong>In this gateway it is always null</strong>, and {@link EntityEventPublisher} explains why that is
     * a measured limit of the reactive listener rather than an omission.</p>
     */
    public static final String ACTOR_ACCOUNT_ID = "actorAccountId";

    /**
     * Which record the event is about.
     *
     * <p>A typed pair rather than two more payload keys: these two are the correlation key <em>and</em> the partition
     * key (see {@link EntityEventPublisher#KEY_HEADER}), so a consumer must be able to find them without knowing what
     * else the payload happens to hold. Being record components also puts them out of the allowlist guard's reach by
     * construction rather than by omission.</p>
     *
     * @param entityType the simple class name of the domain type — {@code User}, and so on. Deliberately not the
     *     fully-qualified name: a consumer must not be coupled to this product's package layout, and a repackaging
     *     here must not read as a new entity type there.
     * @param entityId the document's own id. Never null on a published frame — {@link EntityEventPublisher} refuses a
     *     frame that names nothing, because no consumer can turn one into an audit row.
     */
    public record Subject(String entityType, String entityId) {}
}
