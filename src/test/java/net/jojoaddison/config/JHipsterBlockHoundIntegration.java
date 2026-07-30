package net.jojoaddison.config;

import reactor.blockhound.BlockHound;
import reactor.blockhound.integration.BlockHoundIntegration;

public class JHipsterBlockHoundIntegration implements BlockHoundIntegration {

    @Override
    public void applyTo(BlockHound.Builder builder) {
        builder.allowBlockingCallsInside("org.springframework.validation.beanvalidation.SpringValidatorAdapter", "validate");
        builder.allowBlockingCallsInside("net.jojoaddison.service.MailService", "sendEmailFromTemplate");
        builder.allowBlockingCallsInside("net.jojoaddison.security.DomainUserDetailsService", "createSpringSecurityUser");
        builder.allowBlockingCallsInside("org.springframework.web.reactive.result.method.InvocableHandlerMethod", "invoke");
        builder.allowBlockingCallsInside("org.springdoc.core.service.OpenAPIService", "build");
        builder.allowBlockingCallsInside("org.springdoc.core.service.AbstractRequestService", "build");
        // Generating the OpenAPI document reads jar entries: it scans the classpath for webhook classes, and the
        // Kotlin customizers make kotlin-reflect load its built-ins. Allowing the whole generation frame rather than
        // each internal is deliberate — springdoc caches the document, so this happens once, not per request.
        builder.allowBlockingCallsInside("org.springdoc.api.AbstractOpenApiResource", "getOpenApi");
        builder.allowBlockingCallsInside("com.mongodb.internal.Locks", "checkedWithLock");
        // jhipster-needle-blockhound-integration - JHipster will add additional gradle plugins here
    }
}
