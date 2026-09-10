package net.jojoaddison.management;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Collection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The meters themselves, without an application around them.
 *
 * <p>Deliberately shaped after {@code SecurityMetersServiceTests}: that the counters exist under the expected names
 * and tags, and that each {@code track…()} moves the one it claims to. {@code LoginMetersIT} covers the harder
 * half — that each cause is actually reached by the code path it names.</p>
 */
class LoginMetersServiceTests {

    private static final String LOGINS_METER_EXPECTED_NAME = "security.authentication.logins";
    private static final String FAILED_LOGINS_METER_EXPECTED_NAME = "security.authentication.failed-logins";

    private MeterRegistry meterRegistry;

    private LoginMetersService loginMetersService;

    @BeforeEach
    public void setup() {
        meterRegistry = new SimpleMeterRegistry();

        loginMetersService = new LoginMetersService(meterRegistry);
    }

    @Test
    void theLoginOutcomeCountersAreCreated() {
        // The names are asserted as literals rather than through the constants, so that renaming a constant cannot
        // silently rename a series a dashboard and an alert rule both refer to by string.
        meterRegistry.get(LOGINS_METER_EXPECTED_NAME).tag("outcome", "success").counter();

        meterRegistry.get(FAILED_LOGINS_METER_EXPECTED_NAME).tag("cause", "bad-credentials").counter();
        meterRegistry.get(FAILED_LOGINS_METER_EXPECTED_NAME).tag("cause", "not-activated").counter();
        meterRegistry.get(FAILED_LOGINS_METER_EXPECTED_NAME).tag("cause", "account-locked").counter();

        Collection<Counter> failures = meterRegistry.find(FAILED_LOGINS_METER_EXPECTED_NAME).counters();

        assertThat(failures).hasSize(3);
    }

    @Test
    void theSuccessAndFailureSignalsAreBothCountersSoOneDashboardCanCompareThem() {
        // Decision 4. A gauge of live sessions would have been the other candidate for "success", and it could not
        // be put beside a counted failure signal: a level and an accumulation do not share an axis, and no window
        // function turns one into the other.
        assertThat(meterRegistry.find(LOGINS_METER_EXPECTED_NAME).counter()).isNotNull();
        assertThat(meterRegistry.find(LOGINS_METER_EXPECTED_NAME).gauge()).isNull();
        assertThat(meterRegistry.find(FAILED_LOGINS_METER_EXPECTED_NAME).gauge()).isNull();
    }

    @Test
    void theCountersCarryTheUnitsTheyAreCountedIn() {
        // The two families count different things — attempts on one side, accounts on the other — which is exactly
        // the ambiguity in the phrase "failed logins: 12". The base unit is where that is written down for anybody
        // reading the exported metric rather than this class.
        assertThat(meterRegistry.get(LOGINS_METER_EXPECTED_NAME).counter().getId().getBaseUnit()).isEqualTo("logins");
        assertThat(
            meterRegistry.get(FAILED_LOGINS_METER_EXPECTED_NAME).tag("cause", "bad-credentials").counter().getId().getBaseUnit()
        ).isEqualTo("failures");
    }

    @Test
    void eachTrackMethodMovesItsOwnCounterAndNoOther() {
        assertThat(successes()).isZero();
        loginMetersService.trackLoginSuccess();
        assertThat(successes()).isEqualTo(1);
        assertThat(failures("bad-credentials")).isZero();

        loginMetersService.trackFailedLoginBadCredentials();
        assertThat(failures("bad-credentials")).isEqualTo(1);
        assertThat(failures("not-activated")).isZero();

        loginMetersService.trackFailedLoginNotActivated();
        assertThat(failures("not-activated")).isEqualTo(1);
        assertThat(failures("account-locked")).isZero();

        loginMetersService.trackFailedLoginAccountLocked();
        assertThat(failures("account-locked")).isEqualTo(1);

        // Nothing leaked sideways while all that happened.
        assertThat(successes()).isEqualTo(1);
        assertThat(failures("bad-credentials")).isEqualTo(1);
        assertThat(failures("not-activated")).isEqualTo(1);
    }

    private double successes() {
        return meterRegistry.get(LOGINS_METER_EXPECTED_NAME).tag("outcome", "success").counter().count();
    }

    private double failures(String cause) {
        return meterRegistry.get(FAILED_LOGINS_METER_EXPECTED_NAME).tag("cause", cause).counter().count();
    }
}
