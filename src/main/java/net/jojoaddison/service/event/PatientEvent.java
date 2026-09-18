package net.jojoaddison.service.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
 *
 * <h2>The subject's third component is this service's {@code accountId}, and the other producer's is a
 * {@code patientId}</h2>
 *
 * <p>Since backlog item 56 the gateway's half of the subject is {@code {email, login, accountId}} and
 * {@code hc-patient-service}'s copy of this record is still {@code {email, login, patientId}}. <strong>They are two
 * different identifiers for the same person, both on {@code patient-events}, told apart by name and by nothing
 * else.</strong> {@code accountId} is the gateway's own {@code User.id} — the account, which exists from the instant
 * registration returns. {@code patientId} is the other service's internal profile id, which does not exist until
 * {@code POST /api/onboarding} and which its backlog item 54 deletes.</p>
 *
 * <p>So a consumer must read {@code accountId} by name and never "whichever of the two is present": on a frame
 * carrying both it would silently prefer the one being retired. {@code account.id = profile.accountId} is the
 * estate's join, and this component is the left-hand side of it.</p>
 *
 * <p>That divergence is why {@link Subject} says {@code ignoreUnknown} out loud. This same record is what the gateway
 * <em>deserializes</em> inbound frames into, and every frame it consumes comes from the other service — so its
 * subject arrives carrying a {@code patientId} this record no longer names. <b>Measured 2026-09-18: the configured
 * mapper already tolerates that</b>, and the annotation is a pin rather than a repair. It is there because the
 * tolerance is now load-bearing and was not before: with both sides naming the third component identically, nothing
 * depended on it, and a {@code spring.jackson.deserialization.fail-on-unknown-properties} set somewhere would now
 * stop every delegation and erasure mail in the product with nothing failing anywhere.
 * {@code PatientEventConsumerBindingIT} asserts the frame still binds.</p>
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

    /**
     * Who the event is about.
     *
     * @param email lowercased; the correlation key and the Kafka partition key.
     * @param login the account's login.
     * @param accountId the gateway's own {@code User.id}, and <strong>never</strong> the patient service's
     *                  {@code patientId} — see the class comment, which explains why the distinction is the whole
     *                  point of the name. Null only for an account that does not exist.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Subject(String email, String login, String accountId) {}
}
