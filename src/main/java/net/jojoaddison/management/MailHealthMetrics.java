package net.jojoaddison.management;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Makes a broken outbound-mail relay visible to something other than a person reading a health endpoint.
 *
 * <h2>The failure this exists for</h2>
 *
 * <p>Outbound mail was refused by the relay from an unknown date until it was noticed on 2026-08-07, and it was
 * noticed only because somebody happened to look at {@code /management/health}. Nothing else could have told them:
 * Spring's health indicators fail <em>quietly</em>, so {@code docker logs | grep -i mail} finds nothing, no alert
 * exists, and every other check on the stack stays green throughout. What is actually failing during that period is
 * account activation and password reset — for real users, silently, with no error they or anyone else can see.</p>
 *
 * <p>A gauge is the smallest thing that turns that into something a rule can fire on.</p>
 *
 * <h2>Why it tests the connection rather than reading the health indicator</h2>
 *
 * <p>{@code MailHealthIndicator} does exactly this test internally, and reaching it through Actuator's registry
 * means depending on the shape of that registry in a reactive application, which differs from the servlet case and
 * has moved between Boot versions. Calling {@link JavaMailSenderImpl#testConnection()} is the same check, stated
 * plainly, and it is what the indicator would have reported.</p>
 *
 * <h2>Two metrics, for the reason the backup script has two</h2>
 *
 * <p>{@code hc.patient.mail.up} answers "is the relay accepting us". {@code hc.patient.mail.checked.timestamp}
 * answers "did anybody ask" — and without the second, a gauge that stops updating reads as a permanent last value
 * rather than as a check that died. <strong>Absent is not the same as healthy and neither is stale</strong>, which
 * is the mistake that made hc-admin's backup rule unable to ever fire.</p>
 *
 * <h2>What this still does not prove</h2>
 *
 * <p>That mail is <em>delivered</em>. A successful connection means the relay accepted our credentials, which was
 * equally true on 2026-08-02 before the credential rotted. Mail that authenticates and is then dropped downstream
 * reports {@code up=1} here. Closing that needs a canary whose arrival is asserted; this closes the narrower and
 * more common failure, which is the one that actually happened.</p>
 */
@Component
public class MailHealthMetrics {

    private static final Logger LOG = LoggerFactory.getLogger(MailHealthMetrics.class);

    /** 1 when the relay accepted us on the last check, 0 when it refused or was unreachable. */
    private final AtomicInteger up = new AtomicInteger(0);

    /** When that check last ran, so a check that stopped running is distinguishable from a relay that is fine. */
    private final AtomicLong checkedAt = new AtomicLong(0);

    /**
     * Null when no mail sender is configured at all.
     *
     * <p>Optional rather than required, because Spring only creates the sender when {@code spring.mail.host} is
     * set. A hard dependency would turn "SMTP is not configured on my laptop" into "the gateway will not start",
     * which is a worse failure than the one this class exists to catch. Where it is absent nothing is registered,
     * so the metric is <em>missing</em> rather than reporting a confident zero — an unconfigured relay and a
     * refusing relay are different facts and should not arrive as the same number.</p>
     */
    private final JavaMailSenderImpl mailSender;

    public MailHealthMetrics(ObjectProvider<JavaMailSenderImpl> mailSenderProvider, MeterRegistry registry) {
        this.mailSender = mailSenderProvider.getIfAvailable();
        if (this.mailSender == null) {
            LOG.info("No mail sender is configured, so outbound-mail health will not be reported");
            return;
        }
        registry.gauge("hc.patient.mail.up", up);
        registry.gauge("hc.patient.mail.checked.timestamp", checkedAt);
    }

    /**
     * Five minutes, and a minute of grace at startup.
     *
     * <p>Frequent enough that a broken relay is caught the same morning rather than the same fortnight, and rare
     * enough to be nothing against a relay: 288 connections a day. Each opens a fresh session and is authenticated
     * anew — repeated checks are not cached, which the 2026-08-07 diagnosis established the hard way.</p>
     *
     * <p>{@code @Scheduled} runs on the task scheduler rather than a Netty event loop, so the blocking SMTP call is
     * safe here. It must not be moved onto a reactive thread; BlockHound would fail the build, and rightly.</p>
     */
    @Scheduled(initialDelay = 60_000, fixedDelay = 300_000)
    public void checkRelay() {
        if (mailSender == null) {
            return;
        }
        try {
            mailSender.testConnection();
            if (up.getAndSet(1) == 0) {
                LOG.info("Outbound mail relay is accepting connections again at {}:{}", mailSender.getHost(), mailSender.getPort());
            }
        } catch (Exception e) {
            // Logged at WARN on every failed check rather than only on transition. The whole defect this class
            // addresses is a failure that left no trace anywhere; a log line every five minutes is the point.
            LOG.warn(
                "Outbound mail relay refused us at {}:{} — account activation and password reset are failing " +
                "silently for users. Cause: {}",
                mailSender.getHost(),
                mailSender.getPort(),
                e.toString()
            );
            up.set(0);
        } finally {
            checkedAt.set(System.currentTimeMillis() / 1000);
        }
    }
}
