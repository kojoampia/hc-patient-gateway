package net.jojoaddison.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.jojoaddison.management.RegistrationMetersService;
import net.jojoaddison.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * The sampler's contract with the store, on its own.
 *
 * <p>Two properties matter here and neither needs a database: that both activation states are asked for and handed
 * over in the right order, and that a store that cannot answer leaves the last good reading standing rather than
 * replacing it with a zero or taking the scheduler down.</p>
 */
class RegistrationMetricsSamplerUnitTest {

    private UserRepository userRepository;

    private MeterRegistry meterRegistry;

    private RegistrationMetersService registrationMetersService;

    private RegistrationMetricsSampler sampler;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        meterRegistry = new SimpleMeterRegistry();
        registrationMetersService = new RegistrationMetersService(meterRegistry);
        sampler = new RegistrationMetricsSampler(userRepository, registrationMetersService);
    }

    @Test
    void aSampleReadsBothActivationStatesAndPublishesThem() {
        when(userRepository.countByActivated(true)).thenReturn(Mono.just(12L));
        when(userRepository.countByActivated(false)).thenReturn(Mono.just(5L));

        sampler.sampleOnce().block();

        assertThat(registrationMetersService.activatedCount()).isEqualTo(12);
        assertThat(registrationMetersService.notActivatedCount()).isEqualTo(5);
        assertThat(meterRegistry.get("account.registrations").tag("state", "activated").gauge().value()).isEqualTo(12);
        assertThat(meterRegistry.get("account.registrations").tag("state", "not-activated").gauge().value()).isEqualTo(5);
    }

    @Test
    void aStoreThatCannotAnswerLeavesTheLastGoodReadingStanding() {
        when(userRepository.countByActivated(true)).thenReturn(Mono.just(12L));
        when(userRepository.countByActivated(false)).thenReturn(Mono.just(5L));
        sampler.sampleOnce().block();

        when(userRepository.countByActivated(anyBoolean())).thenReturn(Mono.error(new IllegalStateException("mongo is away")));

        // Completes rather than errors — block() would rethrow otherwise. An error escaping here would be
        // swallowed by the scheduler in a way that leaves no trace, and a repeatedly failing task can be dropped
        // from the schedule entirely.
        sampler.sampleOnce().block();

        // Not zeroed. A gauge that falls to zero because a read failed is indistinguishable from every account
        // having been deleted; the sampled-at gauge is what says the reading is stale.
        assertThat(registrationMetersService.activatedCount()).isEqualTo(12);
        assertThat(registrationMetersService.notActivatedCount()).isEqualTo(5);
    }

    @Test
    void nothingIsPublishedIfTheFirstReadEverFails() {
        when(userRepository.countByActivated(anyBoolean())).thenReturn(Mono.error(new IllegalStateException("mongo is away")));

        sampler.sampleOnce().block();

        assertThat(meterRegistry.find("account.registrations").gauges()).isEmpty();
    }
}
