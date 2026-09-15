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
