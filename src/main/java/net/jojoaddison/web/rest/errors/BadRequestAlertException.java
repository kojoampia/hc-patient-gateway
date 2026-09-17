package net.jojoaddison.web.rest.errors;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;
import tech.jhipster.web.rest.errors.ProblemDetailWithCause;
import tech.jhipster.web.rest.errors.ProblemDetailWithCause.ProblemDetailWithCauseBuilder;

@SuppressWarnings("java:S110") // Inheritance tree of classes should not be too deep
public class BadRequestAlertException extends ErrorResponseException {

    private static final long serialVersionUID = 1L;

    private final String entityName;

    private final String errorKey;

    public BadRequestAlertException(String defaultMessage, String entityName, String errorKey) {
        this(ErrorConstants.DEFAULT_TYPE, defaultMessage, entityName, errorKey);
    }

    /**
     * @param defaultMessage the human-readable reason for the refusal. It fills <b>both</b> {@code title} and
     *     {@code detail} — see the note below on why {@code detail} is set here rather than left to the translator.
     */
    public BadRequestAlertException(URI type, String defaultMessage, String entityName, String errorKey) {
        super(
            HttpStatus.BAD_REQUEST,
            ProblemDetailWithCauseBuilder.instance()
                .withStatus(HttpStatus.BAD_REQUEST.value())
                .withType(type)
                .withTitle(defaultMessage)
                // Backlog item 48. WITHOUT THIS LINE THE THROWN MESSAGE REACHES THE CLIENT NOWHERE AT ALL.
                //
                // ExceptionTranslator.customizeProblem fills a null detail from getCustomizedErrorDetails, which
                // ends at err.getMessage() -- and ErrorResponseException.getMessage() is "<status>, <body>", so the
                // detail became a rendering of the very object it was meant to fill, nested detail='null' and all.
                // The title set on the line above does not survive either: customizeProblem overwrites it with the
                // status reason phrase ("Bad Request") a few statements before it looks at the detail.
                //
                // detail is the RFC 7807 field that is meant to be human-readable, and it is what a generic error
                // component falls back to when it has no translation for the message key -- so this surfaced
                // precisely when something else had already gone wrong, and showed a patient a Java type name.
                //
                // Set at construction rather than in the translator because this is where defaultMessage is known.
                // By the time customizeProblem runs, the only copy of it left is the title it is about to replace.
                .withDetail(defaultMessage)
                .withProperty("message", "error." + errorKey)
                .withProperty("params", entityName)
                .build(),
            null
        );
        this.entityName = entityName;
        this.errorKey = errorKey;
    }

    public String getEntityName() {
        return entityName;
    }

    public String getErrorKey() {
        return errorKey;
    }

    public ProblemDetailWithCause getProblemDetailWithCause() {
        return (ProblemDetailWithCause) this.getBody();
    }
}
