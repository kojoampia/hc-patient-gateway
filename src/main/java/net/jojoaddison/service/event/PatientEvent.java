package net.jojoaddison.service.event;

import java.time.Instant;
import java.util.Map;

/**
 * One thing that happened to one patient, on the shared {@code patient-events} stream.
 *
 * <p>Deliberately a copy of the record in {@code hc-patient-service} rather than a shared library. The two services
 * are separately deployed and separately versioned, and a shared type would couple their release cycles to make a
 * six-field record marginally less repetitive. What matters is that the wire shape agrees, which is what the field
 * names here are — change one side and the other stops correlating, silently.</p>
 *
 * <p>The gateway contributes the two events that happen <em>before there is a patient at all</em>: an account being
 * created and an account being activated. Neither can carry a {@code patientId}, because none exists until onboarding
 * step 1 creates a profile in the other service. That is why the correlation key is the email.</p>
 */
public record PatientEvent(
    String eventId,
    String type,
    int version,
    Instant occurredAt,
    String source,
    Subject subject,
    Map<String, Object> data
) {
    public static final int VERSION = 1;

    /** @param patientId always null from this service — see the class comment. */
    public record Subject(String email, String login, String patientId) {}
}
