package net.jojoaddison.management;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;

/**
 * The registration half of the gateway dashboard: how many accounts exist, split by whether they were ever activated.
 *
 * <h2>A population, not a rate — and that was a choice between two different questions</h2>
 *
 * <p>The other candidate was to count {@code AccountCreated}/{@code AccountActivated} as they are published onto
 * {@code patient-events}. That answers "how many people registered this week", which is a rate; it needs the broker
 * to have been up and the consumer to have been running, it starts from zero after every restart, and it can never
 * tell you how many accounts are sitting unactivated <em>right now</em> — which is the number an activation mail
 * that stopped being delivered shows up in. This is sampled from the user store instead, so it is right after a
 * restart, right after a redeploy, and right on a database somebody seeded by hand.</p>
 *
 * <p><strong>"not-activated" here means never activated</strong>, not "not activated within some window".
 * {@code User.activated} is one-way — nothing in this gateway sets it back to false — so the two coincide today.
 * Read it as a backlog rather than as an alert: it only ever grows while registrations arrive that nobody completes,
 * and a threshold on the absolute number would be a threshold on how long the product has been live. The rate of
 * change, or its ratio to the activated count, is the thing worth watching.</p>
 *
 * <h2>Absent rather than zero, and a timestamp so stale is not read as healthy</h2>
 *
 * <p>Nothing is registered until the store has actually been read once, so a gateway that cannot reach Mongo reports
 * <em>no series</em> rather than a confident zero — "there are no accounts" and "nobody has looked" are different
 * facts and must not arrive as the same number. {@code account.registrations.sampled.timestamp} carries when the
 * last successful read happened, because a gauge that stops updating otherwise reads as a permanent last value
 * rather than as a sampler that died. Both rules are {@link MailHealthMetrics}' lesson, applied.</p>
 *
 * @see net.jojoaddison.service.RegistrationMetricsSampler the reactive query that feeds this
 */
@Service
public class RegistrationMetersService {

    public static final String REGISTRATIONS_METER_NAME = "account.registrations";
    public static final String REGISTRATIONS_METER_DESCRIPTION = "Accounts that exist right now, by whether they have ever been activated.";
    public static final String REGISTRATIONS_METER_BASE_UNIT = "accounts";
    public static final String REGISTRATIONS_METER_STATE_DIMENSION = "state";

    /** The account has been activated. Once true it stays true. */
    public static final String STATE_ACTIVATED = "activated";

    /** The account has never been activated — the registration was started and never finished. */
    public static final String STATE_NOT_ACTIVATED = "not-activated";

    /** Epoch seconds of the last successful sample, so a sampler that stopped is distinguishable from a flat number. */
    public static final String SAMPLED_AT_METER_NAME = "account.registrations.sampled.timestamp";
    public static final String SAMPLED_AT_METER_DESCRIPTION = "When the account population was last read from the user store.";
    public static final String SAMPLED_AT_METER_BASE_UNIT = "seconds";

    private final MeterRegistry registry;

    private final AtomicLong activated = new AtomicLong(0);
    private final AtomicLong notActivated = new AtomicLong(0);
    private final AtomicLong sampledAt = new AtomicLong(0);

    /** False until the first successful sample, which is what keeps an unread store off the dashboard entirely. */
    private final AtomicBoolean registered = new AtomicBoolean(false);

    public RegistrationMetersService(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Publishes a fresh reading of the account population.
     *
     * <p>Called from the sampler's own thread, never from a request. The gauges read the {@link AtomicLong}s when
     * the registry is scraped, so nothing queries the database on the scrape path.</p>
     *
     * @param activated accounts with {@code activated == true}.
     * @param notActivated accounts with {@code activated == false}.
     */
    public void recordPopulation(long activated, long notActivated) {
        this.activated.set(activated);
        this.notActivated.set(notActivated);
        this.sampledAt.set(Instant.now().getEpochSecond());
        registerOnce();
    }

    /** The last reading of the activated population, for tests. */
    public long activatedCount() {
        return activated.get();
    }

    /** The last reading of the never-activated population, for tests. */
    public long notActivatedCount() {
        return notActivated.get();
    }

    private void registerOnce() {
        if (!registered.compareAndSet(false, true)) {
            return;
        }
        registrationsGaugeForStateBuilder(STATE_ACTIVATED, activated).register(registry);
        registrationsGaugeForStateBuilder(STATE_NOT_ACTIVATED, notActivated).register(registry);
        Gauge.builder(SAMPLED_AT_METER_NAME, sampledAt, AtomicLong::doubleValue)
            .baseUnit(SAMPLED_AT_METER_BASE_UNIT)
            .description(SAMPLED_AT_METER_DESCRIPTION)
            .register(registry);
    }

    private Gauge.Builder<AtomicLong> registrationsGaugeForStateBuilder(String state, AtomicLong value) {
        return Gauge.builder(REGISTRATIONS_METER_NAME, value, AtomicLong::doubleValue)
            .baseUnit(REGISTRATIONS_METER_BASE_UNIT)
            .description(REGISTRATIONS_METER_DESCRIPTION)
            .tag(REGISTRATIONS_METER_STATE_DIMENSION, state);
    }
}
