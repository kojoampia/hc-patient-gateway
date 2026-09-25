package net.jojoaddison.service.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.User;
import net.jojoaddison.repository.UserRepository;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.Message;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * That completing an erasure puts the deactivation on {@code patient.event} — item 45's headline, and the reason it
 * exists.
 *
 * <h2>The fact hc-admin cannot otherwise get</h2>
 *
 * <p>hc-admin needs <em>archived</em> <strong>and</strong> <em>deactivated</em> to honour an erasure, and those two facts
 * live in two different applications. The archived half already arrives: an archive is a save, so the api's callback
 * emits {@code UPDATED} on {@code patient.event}. The deactivated half is set here, by {@link DeletionAccountCloser}, in
 * response to the api's {@code DeletionRequestChanged} — and before this item it reached <strong>no stream hc-admin's
 * consumer will read</strong>.</p>
 *
 * <p><strong>How that failed is why this test is worth more than the publisher's unit tests.</strong> Everything was
 * green: the api published, the topic existed, frames flowed, and the one frame carrying a legal obligation was simply
 * absent. No error, no dropped-frame count, no log line. An erasure looked honoured on every screen and was not. A test
 * that proves "a publisher exists" cannot see that; this one drives the actual erasure path.</p>
 *
 * <p>It spies {@link StreamBridge} rather than the publisher, so what is asserted is <strong>the bytes</strong> — an
 * erasure that produced a frame of the wrong shape would satisfy a verify on {@code publish} and still be unusable.</p>
 */
@IntegrationTest
class ErasureEntityEventIT {

    /**
     * Spied, not mocked, so the real binder still runs — a mock would leave the binding unexercised, which is the half
     * {@link EntityEventBindingIT} owns and this one must not silently substitute for.
     */
    @MockitoSpyBean
    private StreamBridge streamBridge;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DeletionAccountCloser closer;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void completingAnErasureDeactivatesTheAccountAndPublishesTheChange() {
        User stored = userRepository.save(account("erasure-publishes")).block();
        assertThat(stored.isActivated()).as("the account must start active or nothing changes").isTrue();

        closer.handle(deletionCompleted(stored.getEmail()));

        // The erasure's own frame is an UPDATE. Note the account's creation a moment ago publishes a CREATED frame for
        // the SAME entity id, and both sends are asynchronous — so this selects by action rather than taking the first
        // frame for the id, which would be a race the test would lose intermittently.
        EntityEvent frame = awaitFrame(stored.getId(), EntityChangeAction.UPDATED);

        assertThat(frame)
            .as("the deactivation that completes an erasure must reach patient.event — hc-admin cannot get it elsewhere")
            .isNotNull();
        assertThat(frame.subject().entityType()).isEqualTo("User");
        // containsOnlyKeys, not get(...) == null: a map with no such key and a map holding a null both answer null to
        // get, so the weaker assertion would have stayed green across exactly the item 129 change this pins.
        assertThat(frame.data())
            .as("the closer acts on a Kafka frame, so there is no person to name — and item 129 omits the key entirely")
            .containsOnlyKeys(EntityEvent.ACTION);

        assertThat(userRepository.findById(stored.getId()).block().isActivated())
            .as("the erasure must actually have closed the account — deactivated, not deleted (the 2026-08-31 decision)")
            .isFalse();

        userRepository.deleteById(stored.getId()).block();
    }

    /**
     * ⛔ That nothing identifying reaches the wire, asserted on the serialized frame.
     *
     * <p>A {@code User} holds a login, an email, a password hash, an activation key and a reset key — so unlike the api,
     * whose documents are clinical, this gateway's one published entity <em>is</em> the identifying one.</p>
     *
     * <p>⚠ <strong>What this does NOT do is check that the publisher's guards are called</strong>, and an earlier
     * version of this javadoc implied it did. Measured: deleting both {@code assertIdentifiersOnly} and
     * {@code assertNothingClinical} call sites leaves this test green. It cannot be otherwise — the payload is built
     * from a closed set of keys two lines above the guards, so it is clean whether or not they run. This asserts the
     * <em>outcome</em> on a real serialized frame, which is worth having on its own terms; the guards are held to their
     * contract by {@code EntityEventPublisherTest}, which calls them directly.</p>
     *
     * <p>Every frame for the account is checked, not one of them — the creation and the erasure both have to be clean.</p>
     */
    @Test
    void noFrameCarriesALoginAnEmailOrACredential() {
        User stored = userRepository.save(account("erasure-no-identity")).block();

        closer.handle(deletionCompleted(stored.getEmail()));

        // Wait for the erasure's frame, then examine everything captured for this account.
        awaitFrame(stored.getId(), EntityChangeAction.UPDATED);
        List<EntityEvent> frames = framesFor(stored.getId());

        assertThat(frames).as("no frame for the account was published at all").isNotEmpty();

        for (EntityEvent frame : frames) {
            String json = objectMapper.writeValueAsString(frame);

            assertThat(json).as("a login on a cross-product wire is identifying content").doesNotContain(stored.getLogin());
            assertThat(json).as("the email is the one field erasure is supposed to be removing").doesNotContain(stored.getEmail());
            assertThat(json).as("a password hash must never leave this service").doesNotContain(stored.getPassword());

            JsonNode data = objectMapper.readTree(json).path("data");
            // The action alone: this gateway can never name an actor, and hc-admin item 129 retires the explicit null
            // rather than carrying an always-null key. A closed shape of exactly one field.
            assertThat(data.size()).as("the payload is a closed shape — the action, and nothing else, ever").isEqualTo(1);
            assertThat(data.has(EntityEvent.ACTION)).isTrue();
            assertThat(data.has(EntityEvent.ACTOR_ACCOUNT_ID)).as("item 129: omit the key, do not send it as null").isFalse();
        }

        userRepository.deleteById(stored.getId()).block();
    }

    /**
     * Blocks until a frame for this entity with this action has been sent, then returns it.
     *
     * <p>Mockito's {@code timeout} is what waits; the send happens on two hops of asynchrony — the closer subscribes on
     * {@code boundedElastic} and the publisher sends on its own named thread.</p>
     */
    private EntityEvent awaitFrame(String entityId, EntityChangeAction action) {
        ArgumentCaptor<Message<?>> captor = ArgumentCaptor.forClass(Message.class);
        verify(streamBridge, timeout(10000).atLeastOnce()).send(eq(EntityEventPublisher.BINDING), captor.capture());

        // The captor holds everything sent so far; the awaited frame may not be the last one.
        for (int attempt = 0; attempt < 50; attempt++) {
            EntityEvent found = framesFor(entityId)
                .stream()
                .filter(f -> action.name().equals(f.data().get(EntityEvent.ACTION)))
                .findFirst()
                .orElse(null);
            if (found != null) {
                return found;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return null;
    }

    /** Every entity-change frame captured so far that is about this document. */
    private List<EntityEvent> framesFor(String entityId) {
        ArgumentCaptor<Message<?>> captor = ArgumentCaptor.forClass(Message.class);
        verify(streamBridge, org.mockito.Mockito.atLeastOnce()).send(eq(EntityEventPublisher.BINDING), captor.capture());

        return captor
            .getAllValues()
            .stream()
            .map(Message::getPayload)
            .filter(EntityEvent.class::isInstance)
            .map(EntityEvent.class::cast)
            .filter(event -> entityId.equals(event.subject().entityId()))
            .toList();
    }

    /**
     * The api's completion frame.
     *
     * <p>{@code occurredAt} is anchored to a fixed instant no real clock can equal, so a frame built by anything else
     * during the test cannot be mistaken for this one.</p>
     */
    private static PatientEvent deletionCompleted(String email) {
        return new PatientEvent(
            UUID.randomUUID().toString(),
            PatientEventType.DELETION_REQUEST_CHANGED,
            PatientEvent.VERSION,
            Instant.parse("2001-02-03T04:05:06Z"),
            "hcPatientService",
            new PatientEvent.Subject(email, "irrelevant", "irrelevant"),
            Map.of("change", "COMPLETED")
        );
    }

    private static User account(String login) {
        User user = new User();
        user.setLogin(login);
        user.setPassword(RandomStringUtils.randomAlphanumeric(60));
        user.setEmail(login + "@example.test");
        user.setActivated(true);
        return user;
    }
}
