package net.jojoaddison.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.Authority;
import net.jojoaddison.domain.User;
import net.jojoaddison.repository.AuthorityRepository;
import net.jojoaddison.repository.UserRepository;
import net.jojoaddison.service.event.EntityChangeAction;
import net.jojoaddison.service.event.EntityEventPublisher;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * That a Mongo lifecycle listener fires at all in this reactive application, and that the account is covered.
 *
 * <h2>This is the positive control for item 45's deciding unknown</h2>
 *
 * <p>The item turned on whether {@code AbstractMongoEventListener} works in a WebFlux application built on
 * {@code ReactiveMongoTemplate}. A constant-pool read of that class shows {@code maybeEmitEvent} and all five lifecycle
 * events — but <strong>a reference is not an invocation</strong>, and the whole design collapses if it is not called.
 * This test is that invocation, through {@code UserRepository}, which is what production code uses.</p>
 *
 * <p>⚠ <strong>The first probe of this reported the opposite and was wrong about its own instrument.</strong> It
 * registered a listener as a nested {@code @TestConfiguration}, which was never picked up, observed nothing, and was one
 * assertion away from concluding that reactive templates emit no events. Nothing about the silence distinguished "the
 * listener is not called" from "the listener does not exist". That is why this test asserts against the real
 * application-wired {@link EntityChangeCallback} bean rather than a listener of its own — there is no instrument here to
 * be broken separately from the thing under test.</p>
 *
 * <h2>⛔ Why it must assert {@code User} specifically</h2>
 *
 * <p>Because a test that saved <em>some</em> entity and concluded "the listener works" would pass against an
 * implementation that publishes the wrong set — and in this gateway the set is nearly all wrong either way round: all
 * three domain classes are {@code @Document}, so an annotation-driven implementation publishes a frame per logout and a
 * burst of role definitions per boot, while getting the account right by accident.
 * {@code AuditedEntitiesTest} guards the mechanism and the suppressions; this guards that the account itself, the one
 * entity hc-admin needs, actually produces frames.</p>
 */
@IntegrationTest
class EntityChangeCallbackIT {

    /**
     * Spied rather than mocked so the real publisher still runs — a mock would leave the allowlist and the denylist
     * unexercised on this path, which is where a real payload is built.
     */
    @MockitoSpyBean
    private EntityEventPublisher publisher;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuthorityRepository authorityRepository;

    /**
     * The headline: a reactive save through the repository produces a frame naming the account.
     *
     * <p>{@code CREATED}, because the document had no id before the save — the distinction the listener has to carry
     * across two events that, in this application, arrive on two different threads.</p>
     */
    @Test
    void savingAnAccountThroughTheRepositoryPublishesACreatedFrame() {
        User saved = userRepository.save(account("callback-created")).block();

        assertThat(saved.getId()).isNotNull();
        verify(publisher, timeout(5000)).publish(eq("User"), eq(saved.getId()), eq(EntityChangeAction.CREATED), any());

        userRepository.delete(saved).block();
    }

    /**
     * An update of an existing document reports {@code UPDATED}, not {@code CREATED}.
     *
     * <p>⛔ This is the assertion that a copy of the api's {@code ThreadLocal} implementation would fail — and the only
     * one that would. In this gateway {@code BeforeConvertEvent} arrives on the subscribing thread and
     * {@code AfterSaveEvent} on the Mongo driver's event loop, so a thread-confined pending-insert set is written by one
     * and read by the other, and <em>every</em> save would report {@code CREATED}. Asserting the action is what catches
     * it; asserting only that a frame was published would not.</p>
     */
    @Test
    void updatingAnAccountPublishesAnUpdatedFrame() {
        User saved = userRepository.save(account("callback-updated")).block();
        saved.setActivated(false);

        User updated = userRepository.save(saved).block();

        verify(publisher, timeout(5000)).publish(eq("User"), eq(updated.getId()), eq(EntityChangeAction.UPDATED), any());

        userRepository.delete(updated).block();
    }

    /** A delete by entity names the document, so the frame can be attributed. */
    @Test
    void deletingAnAccountPublishesADeletedFrame() {
        User saved = userRepository.save(account("callback-deleted")).block();
        String id = saved.getId();

        userRepository.delete(saved).block();

        verify(publisher, timeout(5000)).publish(eq("User"), eq(id), eq(EntityChangeAction.DELETED), any());
    }

    /**
     * The actor is null on every frame from this listener, and that is the measured answer rather than a placeholder.
     *
     * <p>The reactive security context is not visible inside a Mongo lifecycle listener — probed 2026-09-25, empty 15
     * times out of 15, including for a save wrapped in {@code contextWrite(withAuthentication(...))}. Pinned so that a
     * later change claiming to resolve an actor has to prove it here rather than in prose.</p>
     *
     * <p>This pins the <em>call</em>: the listener hands {@code null} to the publisher. What the wire then carries is a
     * separate question with a separate answer — the key is omitted rather than sent as null (hc-admin item 129) — and
     * it is pinned in {@code EntityEventPublisherTest} and {@code ErasureEntityEventIT}, where a serialized frame
     * exists to look at.</p>
     */
    @Test
    void everyFrameFromTheListenerCarriesANullActor() {
        User saved = userRepository.save(account("callback-actor")).block();

        verify(publisher, timeout(5000)).publish(eq("User"), eq(saved.getId()), eq(EntityChangeAction.CREATED), eq(null));

        userRepository.delete(saved).block();
    }

    /**
     * A suppressed entity produces nothing, and {@code Authority} is the one that would flood.
     *
     * <p>Role definitions are seeded by four Mongock change units on every boot — a probe recorded 13 {@code Authority}
     * saves in one context start. Publishing them would describe the existence of {@code ROLE_NURSE} on every start of
     * every replica.</p>
     */
    @Test
    void savingASuppressedEntityPublishesNothing() {
        Authority authority = new Authority();
        authority.setName("ROLE_CALLBACK_PROBE");

        authorityRepository.save(authority).block();

        // `after` rather than `never` alone: the publish would happen asynchronously, so an immediate check would pass
        // even against an implementation that publishes.
        verify(publisher, after(1000).never()).publish(eq("Authority"), any(), any(), any());

        authorityRepository.delete(authority).block();
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
