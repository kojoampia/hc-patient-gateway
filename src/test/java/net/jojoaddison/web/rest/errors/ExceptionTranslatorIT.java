package net.jojoaddison.web.rest.errors;

import static org.assertj.core.api.Assertions.assertThat;

import net.jojoaddison.IntegrationTest;
import org.hamcrest.core.AnyOf;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Integration tests {@link ExceptionTranslator} controller advice.
 */
@WithMockUser
@AutoConfigureWebTestClient(timeout = IntegrationTest.DEFAULT_TIMEOUT)
@IntegrationTest
class ExceptionTranslatorIT {

    @Autowired
    private WebTestClient webTestClient;

    /**
     * Item 41, reactive half. A refused write must carry the failure-alert headers; until 2026-09-16 it
     * carried none, here and in the api alike.
     *
     * <p>This asserts the HEADERS, not the status or the body — both of which were always correct, which is
     * why every other test in this class passed throughout. It matches by SUFFIX, mirroring what
     * {@code notification.interceptor.ts} actually does, so it cannot pass while the console still sees
     * nothing because a {@code clientApp.name} drifted.</p>
     */
    @Test
    void aRefusedWriteCarriesTheFailureAlertHeaders() {
        var response = webTestClient
            .get()
            .uri("/api/exception-translator-test/refused-write")
            .exchange()
            .expectStatus()
            .isBadRequest()
            .returnResult(String.class);

        var names = response.getResponseHeaders().headerNames().stream().map(String::toLowerCase).toList();
        assertThat(names)
            .as("the console matches on this suffix; a refusal that sets none is invisible to it")
            .anyMatch(n -> n.endsWith("app-error"));
        assertThat(names).anyMatch(n -> n.endsWith("app-params"));
        assertThat(
            response
                .getResponseHeaders()
                .getOrEmpty(
                    response
                        .getResponseHeaders()
                        .headerNames()
                        .stream()
                        .filter(n -> n.toLowerCase().endsWith("app-params"))
                        .findFirst()
                        .orElse("")
                )
        )
            .as("the params header must name the entity the write was refused for")
            .containsExactly("widget");
    }

    /**
     * Item 48. {@code detail} is the RFC 7807 field meant to be human-readable, and it is the one a generic error
     * component falls back to when it has no translation for {@code message}. Until 2026-09-17 it carried the
     * exception's {@code toString()} — {@code "400 BAD_REQUEST, ProblemDetailWithCause[…detail='null'…]"}, a Java
     * type name rendered at a patient, on every refused write in the product.
     *
     * <p>Asserted by equality rather than by "does not start with 400", deliberately: the defect is not that the
     * old string was ugly, it is that the thrown message was nowhere in the response at all.</p>
     *
     * <p>It also asserts the alert header's <b>value</b>, which item 41's test above does not. The fix fills the
     * body's {@code detail}, and {@code ErrorResponseException.getMessage()} renders the body — which is the
     * string {@code buildHeaders} hands to {@code HeaderUtil.createFailureAlert} as its {@code defaultMessage}.
     * That argument is discarded today because translation is enabled (verified in
     * {@code jhipster-framework-9.0.0-sources.jar}: {@code enableTranslation ? "error." + errorKey :
     * defaultMessage}), so the two items do not interact. Flip that flag and they would, silently, putting a
     * stringified Java object in a response header — hence pinning it here rather than in a report.</p>
     */
    @Test
    void aRefusedWriteDetailCarriesTheThrownMessage() {
        var response = webTestClient
            .get()
            .uri("/api/exception-translator-test/refused-write")
            .exchange()
            .expectStatus()
            .isBadRequest()
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("a refused write")
            .returnResult();

        var errorHeader = response
            .getResponseHeaders()
            .headerNames()
            .stream()
            .filter(n -> n.toLowerCase().endsWith("app-error"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("item 41's alert header is gone"));
        assertThat(response.getResponseHeaders().getOrEmpty(errorHeader))
            .as("filling the body's detail must not leak the rendered body into the header item 41 put back")
            .containsExactly("error.widgetrefused");
    }

    /**
     * Item 48, the same defect on the refusal that has no {@code message} key to fall back from. A password too
     * short for the policy answered with {@code message: "error.http.400"} and a stringified
     * {@code ProblemDetailWithCause} as its only prose — on {@code POST /api/account/reset-password/finish}, a
     * screen a patient reaches alone and unauthenticated.
     */
    @Test
    void anInvalidPasswordDetailCarriesTheThrownMessage() {
        webTestClient
            .get()
            .uri("/api/exception-translator-test/invalid-password")
            .exchange()
            .expectStatus()
            .isBadRequest()
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("Incorrect password");
    }

    /**
     * Item 48, the register family — <b>a pin, not a repair</b>. This passed before the fix and passes after it,
     * and that is the whole point: {@code POST /api/register} already answered a taken login with a readable
     * {@code detail}, by a different route (the translator substitutes {@link LoginAlreadyUsedException}'s body for
     * the service-layer exception's). The two families' {@code defaultMessage} and service message are
     * byte-identical, so pre-filling the body's {@code detail} leaves this response unchanged — this asserts that
     * rather than assuming it.
     *
     * <p>What this test does <em>not</em> pin is the other half of the inversion: this family still carries no
     * alert headers, because the object reaching {@code buildHeaders} is the service exception. That is item 48's
     * undecided question and is deliberately untouched here.</p>
     */
    @Test
    void theRegisterFamilyKeepsTheDetailItAlreadyHad() {
        webTestClient
            .get()
            .uri("/api/exception-translator-test/login-already-used")
            .exchange()
            .expectStatus()
            .isBadRequest()
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("Login name already used!")
            .jsonPath("$.message")
            .isEqualTo("error.userexists")
            .jsonPath("$.type")
            .isEqualTo(ErrorConstants.LOGIN_ALREADY_USED_TYPE.toString());
    }

    @Test
    void testConcurrencyFailure() {
        webTestClient
            .get()
            .uri("/api/exception-translator-test/concurrency-failure")
            .exchange()
            .expectStatus()
            .isEqualTo(HttpStatus.CONFLICT)
            .expectHeader()
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.message")
            .isEqualTo(ErrorConstants.ERR_CONCURRENCY_FAILURE);
    }

    @Test
    void testMethodArgumentNotValid() {
        webTestClient
            .post()
            .uri("/api/exception-translator-test/method-argument")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{}")
            .exchange()
            .expectHeader()
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.message")
            .isEqualTo(ErrorConstants.ERR_VALIDATION)
            .jsonPath("$.fieldErrors.[0].objectName")
            .isEqualTo("test")
            .jsonPath("$.fieldErrors.[0].field")
            .isEqualTo("test")
            .jsonPath("$.fieldErrors.[0].message")
            .isEqualTo("must not be null");
    }

    @Test
    void testMissingRequestPart() {
        webTestClient
            .get()
            .uri("/api/exception-translator-test/missing-servlet-request-part")
            .exchange()
            .expectStatus()
            .isBadRequest()
            .expectHeader()
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.message")
            .isEqualTo("error.http.400");
    }

    @Test
    void testMissingRequestParameter() {
        webTestClient
            .get()
            .uri("/api/exception-translator-test/missing-servlet-request-parameter")
            .exchange()
            .expectStatus()
            .isBadRequest()
            .expectHeader()
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.message")
            .isEqualTo("error.http.400");
    }

    @Test
    void testAccessDenied() {
        webTestClient
            .get()
            .uri("/api/exception-translator-test/access-denied")
            .exchange()
            .expectStatus()
            .isForbidden()
            .expectHeader()
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.message")
            .isEqualTo("error.http.403")
            .jsonPath("$.detail")
            .isEqualTo("test access denied!");
    }

    @Test
    void testUnauthorized() {
        webTestClient
            .get()
            .uri("/api/exception-translator-test/unauthorized")
            .exchange()
            .expectStatus()
            .isUnauthorized()
            .expectHeader()
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.message")
            .isEqualTo("error.http.401")
            .jsonPath("$.path")
            .isEqualTo("/api/exception-translator-test/unauthorized")
            .jsonPath("$.detail")
            .value(AnyOf.anyOf(IsEqual.equalTo("test authentication failed!"), IsEqual.equalTo("Invalid credentials")));
    }

    @Test
    void testMethodNotSupported() {
        webTestClient
            .post()
            .uri("/api/exception-translator-test/access-denied")
            .exchange()
            .expectStatus()
            .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED)
            .expectHeader()
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.message")
            .isEqualTo("error.http.405")
            .jsonPath("$.detail")
            .isEqualTo("405 METHOD_NOT_ALLOWED \"Request method 'POST' is not supported.\"");
    }

    @Test
    void testExceptionWithResponseStatus() {
        webTestClient
            .get()
            .uri("/api/exception-translator-test/response-status")
            .exchange()
            .expectStatus()
            .isBadRequest()
            .expectHeader()
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.message")
            .isEqualTo("error.http.400")
            .jsonPath("$.title")
            .isEqualTo("test response status");
    }

    @Test
    void testInternalServerError() {
        webTestClient
            .get()
            .uri("/api/exception-translator-test/internal-server-error")
            .exchange()
            .expectHeader()
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.message")
            .isEqualTo("error.http.500")
            .jsonPath("$.title")
            .isEqualTo("Internal Server Error");
    }
}
