package net.jojoaddison.service.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.Message;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * That a frame on {@code patient.event} says what changed, never what it now holds, and never on the caller's thread.
 *
 * <p>Backlog item 45's gateway half. Each guard here is pinned separately on purpose: an aggregate "it did not throw"
 * cannot distinguish "all guarded" from "one guarded", and three of these defects are a one-word edit away.</p>
 */
class EntityEventPublisherTest {

    private static final String ENTITY_ID = "68c1f0a2b3c4d5e6f7a8b9c0";

    /**
     * The defect this class exists to prevent, asserted directly rather than left to BlockHound.
     *
     * <p>{@code PatientEventPublisherUnitTest} records why: BlockHound missed a blocking send in this repository once
     * already, because the test mocked the sender and nothing ever touched a socket. It is sharper here — the calling
     * thread in production is the MongoDB driver's IO event loop ({@code multiThreadIoEventLoopGroup-*}, measured
     * 2026-09-25), so a blocking send stalls every query on that connection, and BlockHound does not necessarily police
     * that group at all.</p>
     */
    @Test
    void sendingRunsOffTheCallingThread() throws InterruptedException {
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
        publisher(bridge).publish("User", ENTITY_ID, EntityChangeAction.UPDATED, null);

        assertThat(sent.await(5, TimeUnit.SECONDS)).as("the frame was never sent").isTrue();
        assertThat(sendingThread.get()).isNotEqualTo(callingThread);
        assertThat(sendingThread.get()).as("the send belongs on this publisher's own named thread").isEqualTo("entity-event-publisher");
    }

    /**
     * ⛔ The rejection policy, which is the whole reason the executor is private and bounded.
     *
     * <p>{@code CallerRunsPolicy} is the conventional choice for a bounded queue and would hand the sixty-second send
     * back to the Mongo driver's event loop the moment the queue fills — which is the moment the broker is slowest. It
     * is a one-word edit and no other test in this repository would notice it.</p>
     */
    @Test
    void theRejectionPolicyIsAbortAndNotCallerRuns() {
        ThreadPoolExecutor sender = publisher(mock(StreamBridge.class)).senderForTest();

        assertThat(sender.getRejectedExecutionHandler())
            .as("CallerRunsPolicy would run the blocking send on the Mongo driver's event loop")
            .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
        assertThat(sender.getQueue().remainingCapacity() + sender.getQueue().size())
            .as("the queue must stay bounded — an unbounded one buffers an outage instead of dropping")
            .isEqualTo(512);
        assertThat(sender.getMaximumPoolSize()).as("one thread, so submission order is send order").isEqualTo(1);
    }

    /**
     * A frame that names no document is refused, because no consumer can turn one into an audit row.
     *
     * <p>⚠ {@code after(...).never()} rather than a bare {@code never()}, and the difference is not stylistic: the send
     * happens on another thread, so a bare {@code never()} is evaluated before a violating frame could have been sent and
     * passes against a publisher that sends one a millisecond later. <strong>Measured — removing the blank-id guard left
     * the bare-{@code never()} version of this test green</strong>, which is the whole defect it exists to catch, so the
     * window is what makes it a test at all.</p>
     */
    @ParameterizedTest
    @ValueSource(strings = { "", "   " })
    void aFrameThatNamesNothingIsNotPublished(String blankId) {
        StreamBridge bridge = mock(StreamBridge.class);

        publisher(bridge).publish("User", blankId, EntityChangeAction.UPDATED, null);

        verify(bridge, after(500).never()).send(anyString(), any(Message.class));
    }

    /** See the note above on why this waits rather than checking immediately. */
    @Test
    void aNullEntityIdIsNotPublished() {
        StreamBridge bridge = mock(StreamBridge.class);

        publisher(bridge).publish("User", null, EntityChangeAction.UPDATED, null);

        verify(bridge, after(500).never()).send(anyString(), any(Message.class));
    }

    /**
     * The allowlist half of the payload guard.
     *
     * <p>Pinned separately from the denylist below because <strong>the two fail differently and neither subsumes the
     * other</strong>: this one catches any key that is not one of the two, including a harmless-looking one nobody
     * thought to forbid.
     */
    @Test
    void theAllowlistRefusesAnyKeyButTheActionAndTheActor() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(EntityEvent.ACTION, "UPDATED");
        data.put(EntityEvent.ACTOR_ACCOUNT_ID, null);
        data.put("activated", false);

        assertThatThrownBy(() -> EntityEventPublisher.assertIdentifiersOnly(data))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("activated");
    }

    @Test
    void theAllowlistAcceptsExactlyTheTwoKeys() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(EntityEvent.ACTION, "UPDATED");
        data.put(EntityEvent.ACTOR_ACCOUNT_ID, null);

        assertThatCode(() -> EntityEventPublisher.assertIdentifiersOnly(data)).doesNotThrowAnyException();
    }

    /**
     * The denylist half — and the four keys this gateway is the only place that could leak.
     *
     * <p>A {@code User} holds a login, an email, a password hash and two key fields, so these are not hypothetical the
     * way the clinical keys are: they are the fields on the very document this stream reports. Case-insensitive,
     * because a payload key is written by hand.</p>
     */
    @ParameterizedTest
    @ValueSource(strings = { "login", "email", "Password", "resetKey", "activationKey", "diagnosis", "bloodGroup", "notes" })
    void theDenylistRefusesIdentifyingAndClinicalKeys(String forbidden) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(forbidden, "whatever");

        assertThatThrownBy(() -> EntityEventPublisher.assertNothingClinical(data))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(forbidden);
    }

    /**
     * ⛔ The actor key is OMITTED when there is no actor, not sent as an explicit null.
     *
     * <p>hc-admin item 129, decided 2026-09-24: omit the key everywhere. In this gateway the actor is <em>always</em>
     * unresolvable, so this is the shape of <strong>every</strong> frame it publishes rather than an edge case — which
     * is exactly why emitting the retired shape here would have mattered.</p>
     *
     * <p>Asserted on the serialized frame as well as the map, because those are two different facts: a map without the
     * key and a map with a null value can both look like "no actor" to a Java assertion and are distinguishable on the
     * wire.</p>
     */
    @Test
    void theActorKeyIsOmittedWhenThereIsNoActor() throws InterruptedException {
        EntityEvent event = publishAndCapture("User", ENTITY_ID, EntityChangeAction.UPDATED, null);

        assertThat(event.data()).doesNotContainKey(EntityEvent.ACTOR_ACCOUNT_ID);
        assertThat(event.data()).containsOnlyKeys(EntityEvent.ACTION);

        ObjectMapper om = new ObjectMapper();
        JsonNode data = om.readTree(om.writeValueAsString(event)).path("data");
        assertThat(data.has(EntityEvent.ACTOR_ACCOUNT_ID))
            .as("item 129 retires the explicit null; the key must be absent from the wire, not present and null")
            .isFalse();
    }

    /**
     * And when an actor IS supplied, the key is present and carries it.
     *
     * <p>No call site supplies one today — {@code EntityChangeCallback} always passes null — but the parameter exists
     * and omission must be a property of <em>a null actor</em> rather than of this publisher never sending the key.
     * Without this, deleting the {@code data.put} entirely would still pass.</p>
     */
    @Test
    void theActorKeyIsPresentWhenThereIsAnActor() throws InterruptedException {
        EntityEvent event = publishAndCapture("User", ENTITY_ID, EntityChangeAction.UPDATED, "68c1f0a2b3c4d5e6f7a8b9c1");

        assertThat(event.data()).containsEntry(EntityEvent.ACTOR_ACCOUNT_ID, "68c1f0a2b3c4d5e6f7a8b9c1");
    }

    /**
     * The envelope, component for component, as the api sends it.
     *
     * <p>Two files in two repositories carry this shape by hand with nothing mechanical comparing them, so this asserts
     * the wire names rather than the record's accessors — a rename of a component would otherwise pass.</p>
     */
    @Test
    void theEnvelopeMatchesTheOneTheApiPublishes() throws InterruptedException {
        EntityEvent event = publishAndCapture("User", ENTITY_ID, EntityChangeAction.CREATED, null);

        ObjectMapper om = new ObjectMapper();
        JsonNode frame = om.readTree(om.writeValueAsString(event));

        assertThat(frame.path("type").asString()).isEqualTo("EntityChanged");
        assertThat(frame.path("version").asInt()).isEqualTo(1);
        assertThat(frame.path("subject").path("entityType").asString()).isEqualTo("User");
        assertThat(frame.path("subject").path("entityId").asString()).isEqualTo(ENTITY_ID);
        assertThat(frame.path("data").path("action").asString()).isEqualTo("CREATED");
        assertThat(frame.has("eventId")).isTrue();
        assertThat(frame.has("occurredAt")).isTrue();
        // ⚠ The one component that deliberately differs from the api's `hcPatientService`. A consumer can coalesce two
        // sources into one product; it cannot recover a distinction that was never on the wire.
        assertThat(frame.path("source").asString()).isEqualTo("patientGateway");
    }

    /** The partition key is the entity, not the actor and not the patient — see the class javadoc on KEY_HEADER. */
    @Test
    void theKeyHeaderCarriesTheEntityId() throws InterruptedException {
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

        publisher(bridge).publish("User", ENTITY_ID, EntityChangeAction.DELETED, null);

        assertThat(sent.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(captured.get().getHeaders().get(EntityEventPublisher.KEY_HEADER)).isEqualTo(ENTITY_ID);
    }

    /** A broken broker must not reach the write that provoked the frame. */
    @Test
    void aFailedSendNeverReachesTheCaller() {
        StreamBridge bridge = mock(StreamBridge.class);
        doThrow(new IllegalStateException("broker down")).when(bridge).send(anyString(), any(Message.class));

        assertThatCode(() -> publisher(bridge).publish("User", ENTITY_ID, EntityChangeAction.UPDATED, null)).doesNotThrowAnyException();
    }

    private static EntityEventPublisher publisher(StreamBridge bridge) {
        return new EntityEventPublisher(bridge, registry());
    }

    private static MeterRegistry registry() {
        return new SimpleMeterRegistry();
    }

    private EntityEvent publishAndCapture(String type, String id, EntityChangeAction action, String actor) throws InterruptedException {
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

        publisher(bridge).publish(type, id, action, actor);

        assertThat(sent.await(5, TimeUnit.SECONDS)).as("the frame was never sent").isTrue();
        return (EntityEvent) captured.get().getPayload();
    }
}
