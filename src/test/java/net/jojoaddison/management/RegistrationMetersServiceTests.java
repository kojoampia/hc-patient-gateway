package net.jojoaddison.management;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The registration gauges, without a database behind them.
 *
 * <p>The interesting assertion here is the first one. A population gauge that reports zero before anybody has read
 * the store is worse than one that reports nothing at all: "there are no accounts" and "the gateway cannot reach
 * Mongo" are different facts, and a dashboard cannot tell them apart once they arrive as the same number.</p>
 */
class RegistrationMetersServiceTests {

    private static final String REGISTRATIONS_METER_EXPECTED_NAME = "account.registrations";
    private static final String SAMPLED_AT_METER_EXPECTED_NAME = "account.registrations.sampled.timestamp";

    private MeterRegistry meterRegistry;

    private RegistrationMetersService registrationMetersService;

    @BeforeEach
    public void setup() {
        meterRegistry = new SimpleMeterRegistry();

        registrationMetersService = new RegistrationMetersService(meterRegistry);
    }

    @Test
    void noSeriesExistUntilTheStoreHasActuallyBeenRead() {
        assertThat(meterRegistry.find(REGISTRATIONS_METER_EXPECTED_NAME).gauges()).isEmpty();
        assertThat(meterRegistry.find(SAMPLED_AT_METER_EXPECTED_NAME).gauge()).isNull();
    }

    @Test
    void aReadingRegistersOneGaugePerActivationState() {
        registrationMetersService.recordPopulation(7, 3);

        assertThat(meterRegistry.find(REGISTRATIONS_METER_EXPECTED_NAME).gauges()).hasSize(2);
        assertThat(population("activated")).isEqualTo(7);
        assertThat(population("not-activated")).isEqualTo(3);
        assertThat(meterRegistry.get(REGISTRATIONS_METER_EXPECTED_NAME).tag("state", "activated").gauge().getId().getBaseUnit()).isEqualTo(
            "accounts"
        );
    }

    @Test
    void laterReadingsMoveTheSameGaugesRatherThanAddingMore() {
        // A gauge is a level, so a second reading must replace the first. Registering per sample would leave the
        // dashboard summing every reading the process has ever taken.
        registrationMetersService.recordPopulation(7, 3);
        registrationMetersService.recordPopulation(9, 1);

        assertThat(meterRegistry.find(REGISTRATIONS_METER_EXPECTED_NAME).gauges()).hasSize(2);
        assertThat(population("activated")).isEqualTo(9);
        assertThat(population("not-activated")).isEqualTo(1);
    }

    @Test
    void theSampledAtGaugeSaysWhenTheReadingWasTaken() {
        // Without this, a sampler that has died reads as a population that has stopped changing — and those look
        // identical on a graph. It is the same pairing MailHealthMetrics carries, for the same reason.
        long before = Instant.now().getEpochSecond();

        registrationMetersService.recordPopulation(1, 1);

        assertThat(meterRegistry.get(SAMPLED_AT_METER_EXPECTED_NAME).gauge().value()).isGreaterThanOrEqualTo(before);
    }

    @Test
    void theLastReadingIsReadableWithoutGoingThroughTheRegistry() {
        registrationMetersService.recordPopulation(4, 6);

        assertThat(registrationMetersService.activatedCount()).isEqualTo(4);
        assertThat(registrationMetersService.notActivatedCount()).isEqualTo(6);
    }

    private double population(String state) {
        return meterRegistry.get(REGISTRATIONS_METER_EXPECTED_NAME).tag("state", state).gauge().value();
    }
}
