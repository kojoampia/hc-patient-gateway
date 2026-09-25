package net.jojoaddison.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import net.jojoaddison.domain.User;
import net.jojoaddison.service.event.EntityChangeAction;
import net.jojoaddison.service.event.EntityEventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.mapping.event.AfterSaveEvent;

/**
 * That a failing publisher cannot fail the write — except for the one failure where saying so would be a lie.
 *
 * <p>Backlog item 45, and the whole of this class's contract in two tests. The measured defect it exists to prevent:
 * BlockHound's {@code BlockingOperationError} is an {@link Error}, so it sailed through a
 * {@code catch (RuntimeException)} into the save pipeline and killed {@code userRepository.save(...)} — a publisher
 * becoming the reason a registration failed.</p>
 *
 * <p>The two cases are asserted separately because they are opposite requirements on one catch block, and an aggregate
 * "it did not throw" cannot tell them apart.</p>
 */
class EntityChangeCallbackTest {

    private static final String ENTITY_ID = "68c1f0a2b3c4d5e6f7a8b9c0";

    /**
     * An ordinary {@link Error} is swallowed, so the write survives it.
     *
     * <p>{@code NoClassDefFoundError} stands in for the production members of this category;
     * {@code BlockingOperationError} is the test-scope one and is not on the compile classpath here. Both are
     * {@code Error}s and neither is a {@code VirtualMachineError}, which is exactly the distinction being pinned.</p>
     */
    @Test
    void anErrorFromThePublisherDoesNotReachTheWrite() {
        EntityEventPublisher publisher = mock(EntityEventPublisher.class);
        doThrow(new NoClassDefFoundError("some/Missing/Class"))
            .when(publisher)
            .publish(anyString(), anyString(), any(EntityChangeAction.class), any());

        assertThatCode(() -> new EntityChangeCallback(publisher).onAfterSave(save(account())))
            .as("a stream that cannot describe a change must not be able to prevent it")
            .doesNotThrowAnyException();
    }

    /** A {@link RuntimeException} likewise — the case that already worked, kept so a narrowing of the catch shows up. */
    @Test
    void aRuntimeExceptionFromThePublisherDoesNotReachTheWrite() {
        EntityEventPublisher publisher = mock(EntityEventPublisher.class);
        doThrow(new IllegalStateException("broker down")).when(publisher).publish(anyString(), anyString(), any(), any());

        assertThatCode(() -> new EntityChangeCallback(publisher).onAfterSave(save(account()))).doesNotThrowAnyException();
    }

    /**
     * ⛔ A {@link VirtualMachineError} is rethrown, because swallowing it would log a reassurance nobody can honour.
     *
     * <p>"The record is unaffected" is false after an {@link OutOfMemoryError}: the JVM is in an undefined state and
     * every later allocation fails anyway. This is the one place the rule above is deliberately not applied, and it is
     * pinned so that broadening the catch to a bare {@code Throwable} — which is what it was, briefly — cannot pass.</p>
     */
    @Test
    void aVirtualMachineErrorIsRethrownRatherThanReassuredAway() {
        EntityEventPublisher publisher = mock(EntityEventPublisher.class);
        OutOfMemoryError oom = new OutOfMemoryError("Java heap space");
        doThrow(oom).when(publisher).publish(anyString(), anyString(), any(), any());

        assertThatThrownBy(() -> new EntityChangeCallback(publisher).onAfterSave(save(account())))
            .as("an unrecoverable JVM failure must not be reported as 'the record is unaffected'")
            .isSameAs(oom);
    }

    private static AfterSaveEvent<Object> save(User user) {
        return new AfterSaveEvent<>(user, new org.bson.Document("_id", user.getId()), "jhi_user");
    }

    private static User account() {
        User user = new User();
        user.setId(ENTITY_ID);
        user.setLogin("callback-unit");
        user.setEmail("callback-unit@example.test");
        return user;
    }
}
