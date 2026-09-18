package net.jojoaddison.service.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.jojoaddison.domain.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.Message;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * That publishing an account event never runs on the thread that asked for it, and that it names the account.
 *
 * <p>This is the test the subsystem learned to write the hard way. {@code MailService} once wrapped a blocking send in
 * {@code Mono.defer(...).subscribe()} with no scheduler, which runs the work inline — so the SMTP conversation ran on
 * a Netty event loop, 2.8s at a time, stalling every other request on that thread. It read as asynchronous, and
 * BlockHound did not catch it because the test mocked the sender. Asserting the thread directly is what catches it.</p>
 */
class PatientEventPublisherUnitTest {

    private static final String ACCOUNT_ID = "68c1f0a2b3c4d5e6f7a8b9c0";

    @Test
    void publishingRunsOffTheCallingThread() throws InterruptedException {
        StreamBridge bridge = mock(StreamBridge.class);
        AtomicReference<String> sendingThread = new AtomicReference<>();
        CountDownLatch sent = new CountDownLatch(1);
        doAnswer(invocation -> {
            sendingThread.set(Thread.currentThread().getName());
            sent.countDown();
            return true;
        })
            .when(bridge)
            .send(anyString(), any(Message.class));

        String callingThread = Thread.currentThread().getName();
        new PatientEventPublisher(bridge).publish(PatientEventType.ACCOUNT_CREATED, account(), Map.of());

        assertThat(sent.await(5, TimeUnit.SECONDS)).as("the event was never published").isTrue();
        assertThat(sendingThread.get()).isNotEqualTo(callingThread);
        assertThat(sendingThread.get()).startsWith("boundedElastic-");
    }

    @Test
    void aFailedPublishNeverReachesTheCaller() {
        StreamBridge bridge = mock(StreamBridge.class);
        doThrow(new IllegalStateException("broker down")).when(bridge).send(anyString(), any(Message.class));

        // The account already exists by now. Losing the event costs observability; propagating the failure would cost
        // somebody their registration.
        assertThatCode(
            () -> new PatientEventPublisher(bridge).publish(PatientEventType.ACCOUNT_CREATED, account(), Map.of())
        ).doesNotThrowAnyException();
    }

    /**
     * Both account events, not one of them.
     *
     * <p>Backlog item 56. The subject's third component was a hardcoded {@code null}, so hc-admin's
     * {@code Patient.accountId} — which is {@code @NotNull} — had nothing to be sourced from and every registration
     * would have dead-lettered on their side. The item is explicit that one event is not enough: carrying the id on
     * {@code AccountCreated} and not on {@code AccountActivated} makes a consumer's join depend on which frame
     * happened to arrive first.</p>
     */
    @ParameterizedTest
    @ValueSource(strings = { PatientEventType.ACCOUNT_CREATED, PatientEventType.ACCOUNT_ACTIVATED })
    void everyAccountEventNamesTheAccountItIsAbout(String type) throws InterruptedException {
        PatientEvent event = publishAndCapture(type, account());

        assertThat(event.subject().accountId())
            .as("%s must carry the gateway's own User.id in subject.accountId — hc-admin joins Patient.accountId on it", type)
            .isEqualTo(ACCOUNT_ID);
        assertThat(event.subject().email()).isEqualTo("ama@example.test");
        assertThat(event.subject().login()).isEqualTo("ama");
    }

    /**
     * The wire name, which is the only thing telling the two identifiers on this topic apart.
     *
     * <p>{@code hc-patient-service} publishes {@code subject.patientId} onto the same stream. A consumer reads one or
     * the other by name, so renaming this component is a cross-product break that no compiler on either side can
     * see.</p>
     */
    @Test
    void theSubjectNamesTheIdentifierOnTheWire() throws InterruptedException {
        PatientEvent event = publishAndCapture(PatientEventType.ACCOUNT_CREATED, account());

        ObjectMapper om = new ObjectMapper();
        JsonNode subject = om.readTree(om.writeValueAsString(event)).path("subject");

        assertThat(subject.path("accountId").asString()).as("the wire field hc-admin reads is subject.accountId").isEqualTo(ACCOUNT_ID);
        assertThat(subject.has("patientId"))
            .as("subject.patientId belongs to hc-patient-service's producer; this one must not also claim the name")
            .isFalse();
    }

    /**
     * That the account cannot be left out by a call site written after this one.
     *
     * <p>The defect item 56 fixed was not a forgotten argument, it was that there was no argument: four parameters,
     * and the id hardcoded {@code null} inside. "Every event carries the account id" is therefore derived from the
     * signature rather than from the four call sites that existed on the day — a fifth one cannot compile without an
     * account in hand, and an overload taking loose strings would put the hole straight back.</p>
     */
    @Test
    void theOnlyWayToPublishIsWithAnAccount() {
        List<Method> published = Arrays.stream(PatientEventPublisher.class.getDeclaredMethods())
            .filter(method -> "publish".equals(method.getName()))
            .toList();

        assertThat(published).as("one way in, so no call site can choose an id-less one").hasSize(1);
        assertThat(published.get(0).getParameterTypes())
            .as("publish(type, account, data) — the account is what makes the id unskippable")
            .containsExactly(String.class, User.class, Map.class);
    }

    private PatientEvent publishAndCapture(String type, User account) throws InterruptedException {
        StreamBridge bridge = mock(StreamBridge.class);
        AtomicReference<Message<?>> captured = new AtomicReference<>();
        CountDownLatch sent = new CountDownLatch(1);
        doAnswer(invocation -> {
            captured.set(invocation.getArgument(1));
            sent.countDown();
            return true;
        })
            .when(bridge)
            .send(anyString(), any(Message.class));

        new PatientEventPublisher(bridge).publish(type, account, Map.of());

        assertThat(sent.await(5, TimeUnit.SECONDS)).as("%s was never published", type).isTrue();
        return (PatientEvent) captured.get().getPayload();
    }

    private static User account() {
        User user = new User();
        user.setId(ACCOUNT_ID);
        user.setLogin("ama");
        user.setEmail("ama@example.test");
        return user;
    }
}
