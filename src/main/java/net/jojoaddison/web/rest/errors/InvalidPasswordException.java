package net.jojoaddison.web.rest.errors;

import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;
import tech.jhipster.web.rest.errors.ProblemDetailWithCause.ProblemDetailWithCauseBuilder;

@SuppressWarnings("java:S110") // Inheritance tree of classes should not be too deep
public class InvalidPasswordException extends ErrorResponseException {

    private static final long serialVersionUID = 1L;

    public InvalidPasswordException() {
        super(
            HttpStatus.BAD_REQUEST,
            ProblemDetailWithCauseBuilder.instance()
                .withStatus(HttpStatus.BAD_REQUEST.value())
                .withType(ErrorConstants.INVALID_PASSWORD_TYPE)
                .withTitle("Incorrect password")
                // Backlog item 48, and this is the instance of it that reaches a patient soonest. The mechanism is
                // the one documented at length in BadRequestAlertException -- this class is not one of those, but it
                // is built the same way on ErrorResponseException and went the same way through the translator.
                //
                // It is the worse of the two because it sets no "message" property, so the body carries
                // message: "error.http.400" and there is no key to translate. detail was the only prose in the
                // response, and it was a rendering of a ProblemDetailWithCause. Measured on quality at 81277b9 via
                // POST /api/account/reset-password/finish -- public, unauthenticated, and reached by somebody who
                // is already locked out of their account.
                //
                // The string matches net.jojoaddison.service.InvalidPasswordException's message exactly, so the
                // two routes to this refusal -- thrown here, or thrown in the service and substituted by the
                // translator -- now answer identically rather than by which layer happened to throw.
                .withDetail("Incorrect password")
                .build(),
            null
        );
    }
}
