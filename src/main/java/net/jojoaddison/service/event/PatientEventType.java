package net.jojoaddison.service.event;

/** The types this service publishes onto {@code patient-events}. The patient service publishes the rest. */
public final class PatientEventType {

    public static final String ACCOUNT_CREATED = "AccountCreated";
    public static final String ACCOUNT_ACTIVATED = "AccountActivated";

    /** Published by {@code hc-patient-service}; named here because this service consumes it. */
    public static final String CARE_DELEGATION_CHANGED = "CareDelegationChanged";

    /**
     * A deletion request was raised, withdrawn, carried out or refused. Published by the patient service.
     *
     * <p><b>{@code COMPLETED} is the one event on this stream whose subject no longer exists.</b> The erasure takes
     * the patient's {@code Profile} with it, so the address is read off the stored request rather than resolved —
     * and the gateway {@code User} is still there only because closing it is a separate, later step. Whenever that
     * is automated it must run <em>after</em> the mail, or there is nobody left to tell.</p>
     */
    public static final String DELETION_REQUEST_CHANGED = "DeletionRequestChanged";

    private PatientEventType() {}
}
