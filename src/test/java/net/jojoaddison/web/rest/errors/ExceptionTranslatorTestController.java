package net.jojoaddison.web.rest.errors;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/exception-translator-test")
public class ExceptionTranslatorTestController {

    /** Item 41: the refusal path that must carry alert headers. Item 48 reads its body. */
    @GetMapping("/refused-write")
    public void refusedWrite() {
        throw new BadRequestAlertException("a refused write", "widget", "widgetrefused");
    }

    /**
     * Item 48. The other web-layer refusal built on {@link org.springframework.web.ErrorResponseException} — and the
     * one that matters most, because it carries no {@code message} key at all, so {@code detail} is the only text a
     * client has to show.
     */
    @GetMapping("/invalid-password")
    public void invalidPassword() {
        throw new InvalidPasswordException();
    }

    /**
     * Item 48, the register family. A <em>service</em>-layer exception that {@code ExceptionTranslator} answers by
     * substituting the web-layer twin's body. This is the path {@code POST /api/register} takes for a taken login,
     * and it is here so the fix to the family above can be shown not to have moved it.
     */
    @GetMapping("/login-already-used")
    public void loginAlreadyUsed() {
        throw new net.jojoaddison.service.UsernameAlreadyUsedException();
    }

    @GetMapping("/concurrency-failure")
    public void concurrencyFailure() {
        throw new ConcurrencyFailureException("test concurrency failure");
    }

    @PostMapping("/method-argument")
    public void methodArgument(@Valid @RequestBody TestDTO testDTO) {}

    @GetMapping("/missing-servlet-request-part")
    public void missingServletRequestPartException(@RequestPart("part") String part) {}

    @GetMapping("/missing-servlet-request-parameter")
    public void missingServletRequestParameterException(@RequestParam("param") String param) {}

    @GetMapping("/access-denied")
    public void accessdenied() {
        throw new AccessDeniedException("test access denied!");
    }

    @GetMapping("/unauthorized")
    public void unauthorized() {
        throw new BadCredentialsException("test authentication failed!");
    }

    @GetMapping("/response-status")
    public void exceptionWithResponseStatus() {
        throw new TestResponseStatusException();
    }

    @GetMapping("/internal-server-error")
    public void internalServerError() {
        throw new RuntimeException();
    }

    public static class TestDTO {

        @NotNull(message = "must not be null")
        private String test;

        public String getTest() {
            return test;
        }

        public void setTest(String test) {
            this.test = test;
        }
    }

    @ResponseStatus(value = HttpStatus.BAD_REQUEST, reason = "test response status")
    @SuppressWarnings("serial")
    public static class TestResponseStatusException extends RuntimeException {}
}
