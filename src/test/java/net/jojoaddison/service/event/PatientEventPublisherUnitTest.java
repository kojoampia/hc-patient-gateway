package net.jojoaddison.service.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.Message;

/**
 * That publishing an account event never runs on the thread that asked for it.
 *
 * <p>This is the test the subsystem learned to write the hard way. {@code MailService} once wrapped a blocking send in
 * {@code Mono.defer(...).subscribe()} with no scheduler, which runs the work inline — so the SMTP conversation ran on
 * a Netty event loop, 2.8s at a time, stalling every other request on that thread. It read as asynchronous, and
 * BlockHound did not catch it because the test mocked the sender. Asserting the thread directly is what catches it.</p>
 */
class PatientEventPublisherUnitTest {

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
        new PatientEventPublisher(bridge).publish(PatientEventType.ACCOUNT_CREATED, "ama@example.test", "ama", Map.of());

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
            () -> new PatientEventPublisher(bridge).publish(PatientEventType.ACCOUNT_CREATED, "ama@example.test", "ama", Map.of())
        ).doesNotThrowAnyException();
    }
}
