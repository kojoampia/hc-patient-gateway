package net.jojoaddison.service.event;

/** The types this service publishes onto {@code patient-events}. The patient service publishes the rest. */
public final class PatientEventType {

    public static final String ACCOUNT_CREATED = "AccountCreated";
    public static final String ACCOUNT_ACTIVATED = "AccountActivated";

    /** Published by {@code hc-patient-service}; named here because this service consumes it. */
    public static final String CARE_DELEGATION_CHANGED = "CareDelegationChanged";

    private PatientEventType() {}
}
