package net.jojoaddison.service.event;

import java.util.function.Consumer;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/**
 * The one consumer bound to {@code patient-events}, fanning out to whoever cares.
 *
 * <p>There can be exactly one function on this binding — {@code spring.cloud.function.definition} names
 * {@code patientEventsConsumer} and {@code patientEventsConsumer-in-0} points it at the topic — so a second family of
 * events could not simply bring a second consumer. This class is that seam: it exists so that adding one is adding a
 * line here rather than reopening whichever mailer happened to own the binding first.</p>
 *
 * <p><b>The method name IS the binding name.</b> Spring Cloud Stream derives {@code patientEventsConsumer-in-0} from
 * it, so renaming this method silently unbinds the consumer and every mail in the product stops — with nothing
 * failing anywhere. It moved here from {@code CareDelegationMailer} on 2026-08-31 and kept its name for exactly that
 * reason; the bean name comes from the method, never from the class, so the move is invisible to the binding.</p>
 *
 * <p>And this class is deliberately not called {@code PatientEventsConsumer}: Spring would derive that same bean name
 * for the component itself, and a {@code @Bean} method sharing its own class's bean name is a factory-bean reference
 * pointing at itself. The context refuses to start.</p>
 *
 * <p>Each handler filters on {@code type} and ignores what it does not know, which is what lets the patient service
 * add an event without this service being redeployed. A handler that throws would take the whole delivery down with
 * it, so each is called on its own — one family of mail failing must not stop another.</p>
 */
@Component
public class PatientEventMailRouter {

    private final CareDelegationMailer careDelegationMailer;
    private final DeletionRequestMailer deletionRequestMailer;

    public PatientEventMailRouter(CareDelegationMailer careDelegationMailer, DeletionRequestMailer deletionRequestMailer) {
        this.careDelegationMailer = careDelegationMailer;
        this.deletionRequestMailer = deletionRequestMailer;
    }

    @Bean
    public Consumer<PatientEvent> patientEventsConsumer() {
        return this::route;
    }

    void route(PatientEvent event) {
        careDelegationMailer.handle(event);
        deletionRequestMailer.handle(event);
    }
}
