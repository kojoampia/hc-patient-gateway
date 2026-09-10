package net.jojoaddison.management;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

/**
 * The sign-in half of the gateway dashboard: how many sign-ins succeed, and how many accounts are failing and why.
 *
 * <h2>Why a sibling of {@link SecurityMetersService} rather than four more methods on it</h2>
 *
 * <p>{@code SecurityMetersService} is generator output, wired to the JWT decoder, and every constant on it names one
 * meter — {@code security.authentication.invalid-tokens}, base unit {@code errors}. These meters have different base
 * units and a different call graph (the sign-in endpoint, not the token filter), so folding them in would give that
 * class two meter families and leave a generated file further from its generated shape. The <em>shape</em> is copied
 * deliberately: name constants, a builder helper, one {@code track…()} per case, {@code .tag("cause", …)}.</p>
 *
 * <h2>What the two numbers mean, because "failed logins: 12" is ambiguous</h2>
 *
 * <p>They are counted on different units and the dashboard legend has to say so.</p>
 *
 * <ul>
 *   <li><strong>{@code security.authentication.logins}{@code {outcome="success"}}</strong> — one increment per
 *       authentication that minted a token. Per <em>attempt</em>: a person who signs in three times is three.</li>
 *   <li><strong>{@code security.authentication.failed-logins}{@code {cause="bad-credentials"|"not-activated"}}</strong>
 *       — one increment per <em>account</em>, when its run of consecutive failures starts (0 → 1). Ten wrong
 *       passwords in a row against one account is one, not ten. It answers "how many people are stuck", which is
 *       the question a rate of attempts cannot answer, because one script drowns out every real user in it.</li>
 *   <li><strong>{@code security.authentication.failed-logins}{@code {cause="account-locked"}}</strong> — one
 *       increment per sign-in <em>refused at the gate</em> because the account was already locked. This one is per
 *       attempt, and deliberately so: there is no account-level transition left to hang it on (the account is
 *       already failing), and the volume <em>is</em> the signal — it is what a credential-stuffing run looks like
 *       while it is happening.</li>
 * </ul>
 *
 * <h2>Both are counters, and that is decision 4</h2>
 *
 * <p>Success could have been a gauge of live sessions, and that would have been useless next to this: a gauge is a
 * level and a counter is an accumulation, they do not share an axis, and {@code rate()} of one against the last
 * value of the other compares nothing. Two monotonic counters put {@code increase(...[5m])} of success beside
 * {@code increase(...[5m])} of failure on one panel over one window, which is the comparison the dashboard was
 * asked for.</p>
 *
 * <h2>Where they are incremented, and why not in {@code LoginAttemptService}</h2>
 *
 * <p>All four live at one seam, {@code AuthenticateController#authorize}, because that is the only place all three
 * causes are visible. {@code LoginAttemptService#recordFailure} is the tempting seam and it is the wrong one on its
 * own: a request refused because the account is locked short-circuits before the authentication manager runs and
 * never reaches that method, so instrumenting only there goes quiet exactly while an account is being hammered —
 * the moment the dashboard exists for. {@code LoginMetersIT} holds a test that fails if anybody moves it.</p>
 */
@Service
public class LoginMetersService {

    public static final String LOGINS_METER_NAME = "security.authentication.logins";
    public static final String LOGINS_METER_DESCRIPTION = "Counts sign-ins that minted a token, one per successful attempt.";
    public static final String LOGINS_METER_BASE_UNIT = "logins";
    public static final String LOGINS_METER_OUTCOME_DIMENSION = "outcome";

    public static final String FAILED_LOGINS_METER_NAME = "security.authentication.failed-logins";
    public static final String FAILED_LOGINS_METER_DESCRIPTION =
        "Counts failing sign-ins by cause: one per account entering a run of failures, and one per attempt refused " +
        "against an already-locked account.";
    public static final String FAILED_LOGINS_METER_BASE_UNIT = "failures";
    public static final String FAILED_LOGINS_METER_CAUSE_DIMENSION = "cause";

    /** The password did not match, or the login is not one we know. */
    public static final String CAUSE_BAD_CREDENTIALS = "bad-credentials";

    /** The account exists and has never been activated, so {@code DomainUserDetailsService} refuses it. */
    public static final String CAUSE_NOT_ACTIVATED = "not-activated";

    /** The account is inside a lockout window, so the attempt was refused without checking the password. */
    public static final String CAUSE_ACCOUNT_LOCKED = "account-locked";

    private final Counter loginSuccessCounter;
    private final Counter badCredentialsCounter;
    private final Counter notActivatedCounter;
    private final Counter accountLockedCounter;

    public LoginMetersService(MeterRegistry registry) {
        this.loginSuccessCounter = Counter.builder(LOGINS_METER_NAME)
            .baseUnit(LOGINS_METER_BASE_UNIT)
            .description(LOGINS_METER_DESCRIPTION)
            // One value today. The dimension is here anyway so the dashboard's query is `sum by (outcome)` rather
            // than a bare series name, and so a second outcome does not force every panel to be rewritten.
            .tag(LOGINS_METER_OUTCOME_DIMENSION, "success")
            .register(registry);
        this.badCredentialsCounter = failedLoginsCounterForCauseBuilder(CAUSE_BAD_CREDENTIALS).register(registry);
        this.notActivatedCounter = failedLoginsCounterForCauseBuilder(CAUSE_NOT_ACTIVATED).register(registry);
        this.accountLockedCounter = failedLoginsCounterForCauseBuilder(CAUSE_ACCOUNT_LOCKED).register(registry);
    }

    private Counter.Builder failedLoginsCounterForCauseBuilder(String cause) {
        return Counter.builder(FAILED_LOGINS_METER_NAME)
            .baseUnit(FAILED_LOGINS_METER_BASE_UNIT)
            .description(FAILED_LOGINS_METER_DESCRIPTION)
            .tag(FAILED_LOGINS_METER_CAUSE_DIMENSION, cause);
    }

    /** A token was minted. Per attempt. */
    public void trackLoginSuccess() {
        this.loginSuccessCounter.increment();
    }

    /** A known account began a run of failures against a wrong password. Per account, on the 0 &rarr; 1 transition. */
    public void trackFailedLoginBadCredentials() {
        this.badCredentialsCounter.increment();
    }

    /** A never-activated account began a run of failures. Per account, on the 0 &rarr; 1 transition. */
    public void trackFailedLoginNotActivated() {
        this.notActivatedCounter.increment();
    }

    /** A sign-in was refused because the account is locked. Per attempt — see the class javadoc. */
    public void trackFailedLoginAccountLocked() {
        this.accountLockedCounter.increment();
    }
}
