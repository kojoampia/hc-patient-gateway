package net.jojoaddison.service;

import java.time.Duration;
import java.time.Instant;
import net.jojoaddison.domain.User;
import net.jojoaddison.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Per-account lockout with exponential backoff.
 *
 * <p>The application half of the rate limiting added on 2026-08-05. The nginx layer added at the same time keys on
 * {@code $binary_remote_addr}, so it stops one host hammering one account and is blind to the same guess arriving
 * from a thousand addresses — which is the shape credential stuffing actually takes, and the reason a per-IP limit
 * alone is not an answer. This counter follows the <em>account</em>, so it sees the pattern the network layer
 * cannot.</p>
 *
 * <h2>Backoff rather than a hard lock</h2>
 *
 * <p>A permanent lock after N failures hands anybody a denial-of-service lever against a named user: learn a login,
 * fail against it five times, and its owner is locked out until an administrator intervenes. Doubling delays instead
 * make guessing quickly worthless while a real person who mistyped their password waits seconds, and one who comes
 * back tomorrow waits not at all — the counter only matters while the lock is live.</p>
 *
 * <p>The first {@link #FREE_ATTEMPTS} failures cost nothing, because that is the range real people occupy: an old
 * password, a stale password manager entry, caps lock.</p>
 *
 * <h2>What it deliberately does not do</h2>
 *
 * <p>It does not tell the caller that an account is locked, or that it exists. {@code AuthenticateController} answers
 * 401 either way. A "this account is locked" response is an account-existence oracle, and enumeration is precisely
 * what the attacker doing this is trying to build.</p>
 */
@Service
public class LoginAttemptService {

    private static final Logger LOG = LoggerFactory.getLogger(LoginAttemptService.class);

    /** Failures tolerated before any delay. Sized for humans, not for attackers. */
    public static final int FREE_ATTEMPTS = 4;

    /** The delay after the first penalised failure; each subsequent one doubles it. */
    static final Duration BASE_LOCK = Duration.ofSeconds(15);

    /** The ceiling, so an account cannot be locked out of reach by sheer persistence. */
    public static final Duration MAX_LOCK = Duration.ofMinutes(30);

    private final UserRepository userRepository;

    public LoginAttemptService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Whether sign-in is currently barred for this login.
     *
     * @param login the login being attempted.
     * @return true if the account is locked right now.
     */
    public Mono<Boolean> isLocked(String login) {
        return userRepository
            .findOneByLogin(login.toLowerCase())
            .map(user -> user.getLockedUntil() != null && user.getLockedUntil().isAfter(Instant.now()))
            // An unknown login is not locked. It also cannot succeed, and answering identically for both is what
            // keeps this from becoming an account-existence oracle.
            .defaultIfEmpty(false);
    }

    /**
     * Records a failed attempt and extends the lock if the account has run out of free ones.
     *
     * @param login the login that failed.
     * @return completion.
     */
    public Mono<Void> recordFailure(String login) {
        return userRepository
            .findOneByLogin(login.toLowerCase())
            .flatMap(user -> {
                int attempts = user.getFailedLoginAttempts() + 1;
                user.setFailedLoginAttempts(attempts);
                if (attempts > FREE_ATTEMPTS) {
                    Duration lock = lockDurationFor(attempts);
                    user.setLockedUntil(Instant.now().plus(lock));
                    // The login is deliberately absent from this line. It is a valid credential-stuffing target by
                    // the time it appears here, and the log is read by more people than the account belongs to.
                    LOG.warn("Locking an account for {} after {} consecutive failed sign-ins", lock, attempts);
                }
                return userRepository.save(user);
            })
            .then();
    }

    /**
     * Clears the counter after a successful sign-in.
     *
     * @param login the login that succeeded.
     * @return completion.
     */
    public Mono<Void> recordSuccess(String login) {
        return userRepository
            .findOneByLogin(login.toLowerCase())
            .filter(user -> user.getFailedLoginAttempts() != 0 || user.getLockedUntil() != null)
            // Only write when there is something to clear: the overwhelmingly common case is a clean login, and it
            // should not cost a database write.
            .flatMap(user -> {
                user.setFailedLoginAttempts(0);
                user.setLockedUntil(null);
                return userRepository.save(user);
            })
            .then();
    }

    /**
     * The lock length for a given number of consecutive failures: 15s, 30s, 60s, … capped at 30 minutes.
     *
     * @param attempts consecutive failures, including this one.
     * @return how long to bar sign-in for.
     */
    static Duration lockDurationFor(int attempts) {
        int penalised = attempts - FREE_ATTEMPTS; // 1 for the first penalised failure
        // Shift rather than Math.pow, and capped at 20 places before shifting so a very persistent attacker cannot
        // overflow the exponent into a negative — which would produce a lock in the past.
        long multiplier = 1L << Math.min(penalised - 1, 20);
        Duration lock = BASE_LOCK.multipliedBy(multiplier);
        return lock.compareTo(MAX_LOCK) > 0 ? MAX_LOCK : lock;
    }
}
