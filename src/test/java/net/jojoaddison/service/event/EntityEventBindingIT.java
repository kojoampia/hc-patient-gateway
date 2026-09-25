package net.jojoaddison.service.event;

import static org.assertj.core.api.Assertions.assertThat;

import net.jojoaddison.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.stream.config.BindingServiceProperties;
import org.springframework.core.env.Environment;

/**
 * That the entity-change stream is wired to the topic the estate agreed on.
 *
 * <p>Backlog item 45's gateway half. This exists because a mis-wired producer fails <em>quietly</em>:
 * {@code StreamBridge.send} answers {@code false} for a binding it cannot resolve rather than throwing, and for a
 * binding name with no {@code destination} it invents one named after the binding. So a typo in either the constant or
 * the YAML produces a working publisher writing to a topic nobody reads — every test passes and the frames simply never
 * arrive. hc-admin shipped exactly that with {@code roster-events}.</p>
 *
 * <p><strong>The destination is asserted as a literal, deliberately.</strong> {@code patient.event} is a cross-product
 * contract: hc-admin's consumer will name this string, and a misspelling here is a real topic that is silent on both
 * sides at once. An internal name could be derived; a name another product depends on earns an enumeration.</p>
 *
 * <p>⛔ <strong>This test sees the TEST configuration and nothing else, and an earlier version of this javadoc claimed
 * otherwise.</strong> It said "the two files carrying the same binding is itself part of what is being checked". It is
 * not: {@code BindingServiceProperties} comes from a context built only from
 * {@code src/test/resources/config/application.yml}, which <em>replaces</em> the main file on the classpath rather than
 * merging with it. Deleting the binding from the MAIN file leaves this test green — measured, 4/4 — while production
 * publishes to a topic nobody reads.</p>
 *
 * <p><strong>{@code PatientEventFunctionDefinitionTest} owns that half</strong>, reading both files off disk with
 * SnakeYAML, and its {@code theEntityChangeProducerPointsAtPatientEventInBothFiles} is the assertion that goes red for
 * a main-file deletion. What <em>this</em> file is good for is the running context's own view: that the binding the
 * code names resolves, in the configuration a test actually loads — the harmless direction, and still worth having,
 * because a binding name typo'd in Java rather than in YAML shows up here first.</p>
 */
@IntegrationTest
class EntityEventBindingIT {

    @Autowired
    private BindingServiceProperties bindingServiceProperties;

    @Autowired
    private Environment environment;

    @Test
    void theProducerBindingPointsAtPatientEvent() {
        assertThat(bindingServiceProperties.getBindingDestination(EntityEventPublisher.BINDING))
            .as("the binding name in code and the one in BOTH application.yml files must be the same string")
            .isEqualTo("patient.event");
    }

    /**
     * ⚠ Item 45 is an add. This is the assertion that a change repointing the old binding would trip.
     *
     * <p>{@code patient-events} keeps hc-admin's live {@code hc-admin-directory-patient} consumer and this subsystem's
     * own {@link PatientEventMailRouter}. Retiring it is item 46, and it is blocked on this one.</p>
     */
    @Test
    void theLifecycleStreamIsUntouched() {
        assertThat(bindingServiceProperties.getBindingDestination(PatientEventPublisher.BINDING))
            .as("item 45 adds a topic; it does not repoint patient-events, which hc-admin and our own mail router read")
            .isEqualTo("patient-events");
        assertThat(bindingServiceProperties.getBindingDestination("patientEventsConsumer-in-0"))
            .as("the mail router must keep reading the lifecycle stream")
            .isEqualTo("patient-events");
    }

    @Test
    void everyChangeToOneDocumentLandsOnOnePartition() {
        String keyExpression = environment.getProperty(
            "spring.cloud.stream.kafka.bindings." + EntityEventPublisher.BINDING + ".producer.messageKeyExpression"
        );

        assertThat(keyExpression)
            .as("no messageKeyExpression for %s — a create and a delete could be audited out of order", EntityEventPublisher.BINDING)
            .isNotNull();
        assertThat(keyExpression)
            .as("the key expression must read the header the publisher sets")
            .contains(EntityEventPublisher.KEY_HEADER);
    }

    @Test
    void theKeySerializerMatchesTheKeyTheExpressionProduces() {
        // The failure this repo has already paid for once, on the binding next door: messageKeyExpression yields a
        // String, the binder's default key serializer is ByteArraySerializer, and the mismatch throws at SEND time.
        // Here that is doubly silent — the publisher swallows failures AND sends on a thread of its own.
        String keySerializer = environment.getProperty(
            "spring.cloud.stream.kafka.bindings." + EntityEventPublisher.BINDING + ".producer.configuration.key.serializer"
        );

        assertThat(keySerializer)
            .as("a String key needs StringSerializer, or every frame is lost at send time")
            .isEqualTo("org.apache.kafka.common.serialization.StringSerializer");
    }
}
