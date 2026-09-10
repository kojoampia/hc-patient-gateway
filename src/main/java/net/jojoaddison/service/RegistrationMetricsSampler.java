package net.jojoaddison.service;

import net.jojoaddison.management.RegistrationMetersService;
import net.jojoaddison.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Reads the account population off the user store on a timer and hands it to {@link RegistrationMetersService}.
 *
 * <h2>Why this is a sampler and not a gauge closure</h2>
 *
 * <p>The obvious shape — {@code Gauge.builder(name, () -> userRepository.countByActivated(true).block())} — is a
 * defect twice over in this application. Micrometer polls a gauge's function on whatever thread is exporting, so
 * the count would run on the scrape path and every scrape would pay for two database round trips; and
 * {@code block()} anywhere in a WebFlux gateway is one refactor away from a Netty event loop, which BlockHound
 * fails the build for and rightly. Sampling on a scheduled thread and letting the gauges read a plain
 * {@code AtomicLong} keeps the query off both.</p>
 *
 * <h2>Why it lives in {@code service} and the meters live in {@code management}</h2>
 *
 * <p>The ArchUnit layer rule in {@code TechnicalStructureTest} lets {@code Service} reach {@code Persistence} and
 * lets nothing outside the declared layers reach it at all — so a metrics class that queries {@code UserRepository}
 * cannot sit next to {@code SecurityMetersService}. Splitting it this way is not tidiness: the query is a read of
 * domain data and belongs where this codebase puts reads, and the meter definitions stay where every other meter
 * definition in the repository is.</p>
 *
 * <h2>Cost</h2>
 *
 * <p>Two counted queries a minute against the user collection. A minute is well inside the resolution anybody looks
 * at this dashboard on — the population moves by single registrations — and it is 2,880 queries a day rather than
 * two per scrape, which on a Prometheus-shaped scrape interval would be several times more.</p>
 */
@Component
public class RegistrationMetricsSampler {

    private static final Logger LOG = LoggerFactory.getLogger(RegistrationMetricsSampler.class);

    /** Long enough for Mongock's change units to have run, short enough that a restart is not a visible gap. */
    static final long INITIAL_DELAY_MS = 20_000;

    static final long INTERVAL_MS = 60_000;

    private final UserRepository userRepository;

    private final RegistrationMetersService registrationMetersService;

    public RegistrationMetricsSampler(UserRepository userRepository, RegistrationMetersService registrationMetersService) {
        this.userRepository = userRepository;
        this.registrationMetersService = registrationMetersService;
    }

    /**
     * The scheduled tick. Subscribes and returns; the counts run on the driver's own threads.
     *
     * <p>Declared {@code void} on purpose. Spring subscribes to a reactive return value from {@code @Scheduled}
     * itself, so returning the {@link Mono} <em>and</em> subscribing to it here would run every tick twice — once
     * per subscriber, since this pipeline is cold.</p>
     */
    @Scheduled(initialDelay = INITIAL_DELAY_MS, fixedDelay = INTERVAL_MS)
    public void sample() {
        sampleOnce().subscribe();
    }

    /**
     * One reading of the account population, as a cold {@link Mono} a test can await rather than sleep on.
     *
     * <p>A failure updates nothing, which leaves the previous reading standing and the timestamp gauge falling
     * behind — that pairing is what makes a dead sampler visible instead of making it look like a flat number.</p>
     *
     * @return completion, whether the read succeeded or not.
     */
    Mono<Void> sampleOnce() {
        return userRepository
            .countByActivated(true)
            .zipWith(userRepository.countByActivated(false))
            .doOnNext(counts -> registrationMetersService.recordPopulation(counts.getT1(), counts.getT2()))
            .doOnError(error -> LOG.warn("Could not read the account population for the registration gauges: {}", error.toString()))
            .onErrorComplete()
            .then();
    }
}
