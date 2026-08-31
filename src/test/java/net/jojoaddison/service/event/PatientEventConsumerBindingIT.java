package net.jojoaddison.service.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Consumer;
import net.jojoaddison.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.stream.config.BindingServiceProperties;
import org.springframework.context.ApplicationContext;

/**
 * That the consumer is still bound to the topic, and still by the name the configuration expects.
 *
 * <p>This test exists because of how the failure presents: <b>it does not.</b> The binding name is derived from the
 * {@code @Bean} method name, and {@code spring.cloud.function.definition} names it in YAML. Rename the method, or move
 * it without keeping its name, and Spring Cloud Stream simply has no function to bind — the context starts, the
 * gateway serves every request as before, and every mail in the product stops. Nothing throws. Nothing is logged at a
 * level anybody watches. The first symptom is a patient not being told their record was erased, which is exactly the
 * kind of silence nobody notices.</p>
 *
 * <p>The producer side has had this cover since it was written ({@code hc-patient-service}'s
 * {@code PatientEventBindingIT}); the consumer side had none until 2026-08-31, when the {@code @Bean} moved from
 * {@link CareDelegationMailer} to {@link PatientEventMailRouter} so that two families of mail could share one topic.
 * That move was safe — the bean name comes from the method, not the class — but "was safe" is not something to
 * establish by reasoning twice.</p>
 *
 * <p>Note this asserts against the <em>test</em> configuration, which in this repository <b>replaces</b>
 * {@code src/main/resources/config/application.yml} wholesale rather than merging with it. So the two carrying the
 * same binding is itself part of what is being checked — a binding configured only in main is configured for
 * production and for nothing any test can see.</p>
 */
@IntegrationTest
class PatientEventConsumerBindingIT {

    private static final String BINDING = "patientEventsConsumer-in-0";

    @Autowired
    private BindingServiceProperties bindingServiceProperties;

    @Autowired
    private ApplicationContext context;

    @Test
    void theConsumerFunctionExistsUnderTheNameTheConfigurationNames() {
        // `patientEventsConsumer` is listed in spring.cloud.function.definition. A bean by any other name is not
        // bound, however correct the class is.
        assertThat(context.containsBean("patientEventsConsumer"))
            .as("spring.cloud.function.definition names patientEventsConsumer; the @Bean method must keep that name")
            .isTrue();

        assertThat(context.getBean("patientEventsConsumer")).isInstanceOf(Consumer.class);
    }

    @Test
    void itIsBoundToPatientEvents() {
        assertThat(bindingServiceProperties.getBindingDestination(BINDING))
            .as("the binding in code and the one in application.yml must name the same topic")
            .isEqualTo("patient-events");
    }

    @Test
    void everyInstanceDoesNotGetItsOwnCopy() {
        // Without a consumer group each running gateway receives every event, and a patient is told their record was
        // erased once per instance. The group is what makes that one delivery across the deployment.
        assertThat(bindingServiceProperties.getBindingProperties(BINDING).getGroup())
            .as("a missing group turns every replica into a duplicate notifier")
            .isEqualTo("patient-gateway");
    }

    @Test
    void bothMailersAreReachableFromTheBoundFunction() {
        // The router is the only thing on the binding, so a mailer it does not call is a mailer that never runs —
        // and, like the binding itself, that failure is silent.
        assertThat(context.getBean(PatientEventMailRouter.class)).isNotNull();
        assertThat(context.getBean(CareDelegationMailer.class)).isNotNull();
        assertThat(context.getBean(DeletionRequestMailer.class)).isNotNull();
    }
}
