package net.jojoaddison.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The backoff curve, on its own.
 *
 * <p>Worth pinning separately from the integration test: the shape is what decides whether this is a control or a
 * denial-of-service lever aimed at whoever's login an attacker happens to know, and it is easy to change by accident
 * while adjusting a constant.</p>
 */
class LoginAttemptServiceUnitTest {

    @ParameterizedTest(name = "{0} consecutive failures locks for {1}s")
    @CsvSource({ "5, 15", "6, 30", "7, 60", "8, 120", "9, 240" })
    void backsOffExponentially(int attempts, long expectedSeconds) {
        assertThat(LoginAttemptService.lockDurationFor(attempts)).isEqualTo(Duration.ofSeconds(expectedSeconds));
    }

    @Test
    void capsTheLockSoAnAccountCannotBeLockedOutOfReach() {
        // Without a ceiling, sustained guessing against a known login becomes a way to keep its owner out
        // indefinitely — the attack this is supposed to prevent, aimed the other way round.
        assertThat(LoginAttemptService.lockDurationFor(50)).isEqualTo(LoginAttemptService.MAX_LOCK);
    }

    @Test
    void neverProducesALockInThePast() {
        // The exponent is clamped before shifting. Left unclamped, a persistent enough attacker overflows it, and a
        // negative multiplier yields a lock that has already expired — an account that cannot be locked at all.
        for (int attempts = LoginAttemptService.FREE_ATTEMPTS + 1; attempts < 200; attempts++) {
            assertThat(LoginAttemptService.lockDurationFor(attempts))
                .as("lock for %s attempts", attempts)
                .isPositive()
                .isLessThanOrEqualTo(LoginAttemptService.MAX_LOCK);
        }
    }

    @Test
    void theFirstFailuresAreFree() {
        // Real people mistype passwords and carry stale password-manager entries. Penalising the first attempt would
        // make this indistinguishable from an outage to them.
        assertThat(LoginAttemptService.FREE_ATTEMPTS).isGreaterThanOrEqualTo(3);
    }
}
