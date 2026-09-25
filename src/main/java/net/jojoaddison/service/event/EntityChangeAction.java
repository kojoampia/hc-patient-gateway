package net.jojoaddison.service.event;

/**
 * What happened to a document — the "what happened" of the audit row hc-admin writes from an {@link EntityEvent}.
 *
 * <p>Byte-identical to {@code hc-patient-service}'s enum of the same name, because both halves of this product publish
 * onto {@code patient.event} and a consumer reads one vocabulary. Do not add a constant here without adding it there.</p>
 *
 * <h2>⚠ {@link #CREATED} and {@link #UPDATED} are told apart before the save, not after it</h2>
 *
 * <p>Spring Data MongoDB's {@code AfterSaveEvent} fires identically for an insert and an update and carries nothing
 * that distinguishes them — by then the id has been generated and the document is simply present. The id is the
 * signal, and it is only readable earlier: at {@code BeforeConvertEvent} a document about to be inserted with a
 * generated id still has a null one. {@link net.jojoaddison.config.EntityChangeCallback} remembers that answer across
 * the two events — and <strong>in this reactive gateway the two events arrive on different threads</strong>, which is
 * why that class cannot remember it the way the api's does. Its javadoc carries the measurement.</p>
 *
 * <p><strong>The known limit, stated rather than hidden:</strong> a document saved with an id the caller assigned
 * itself is indistinguishable from an update at every point the listener can observe, and is reported as
 * {@link #UPDATED}. Closing that would mean a read of the collection before every write — a query per save, to sharpen
 * a label on a notification — which is a worse trade than a label that is occasionally conservative. Nothing in this
 * gateway assigns its own {@code User} ids; Mongo generates them.</p>
 */
public enum EntityChangeAction {
    /** The document did not exist before this write. */
    CREATED,

    /** The document existed, or this listener could not establish that it did not — see the class javadoc. */
    UPDATED,

    /** The document was removed. */
    DELETED,
}
