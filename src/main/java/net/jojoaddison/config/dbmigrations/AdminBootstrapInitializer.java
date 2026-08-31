package net.jojoaddison.config.dbmigrations;

import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import net.jojoaddison.config.Constants;
import net.jojoaddison.domain.Authority;
import net.jojoaddison.domain.User;
import net.jojoaddison.security.AuthoritiesConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the first administrator on an empty database, in any profile.
 *
 * <p>A gateway deployed against a fresh production database would otherwise have no account to log in with:
 * {@link DevSeedDataInitializer} runs under {@code dev} and {@code test} only, and deliberately so, because its
 * credentials are derived from the login and therefore public.</p>
 *
 * <p>This bootstrap ships <strong>no default credentials</strong>. It does nothing unless
 * {@code gateway.admin.password} is set — most naturally through the {@code GATEWAY_ADMIN_PASSWORD} environment
 * variable — and it does nothing when an account with the configured login already exists, so it is safe to leave
 * enabled and safe to re-run. It never rotates an existing password: an operator who has changed the administrator's
 * password does not want the next restart to put it back.</p>
 *
 * <pre>
 * GATEWAY_ADMIN_PASSWORD='&lt;a real secret&gt;'   # openssl rand -base64 24
 * </pre>
 *
 * <p>Optional overrides: {@code gateway.admin.login} (default {@code admin}) and {@code gateway.admin.email}
 * (no default — see below). The password is never logged.</p>
 *
 * <h2>The email is reconciled on every start; the password is not — 2026-08-31</h2>
 *
 * <p>Until this changed, everything here ran only when the account was absent, so
 * {@code gateway.admin.email} was write-once: set it after the first boot and nothing happened, ever. Production
 * ran for months on {@code admin@localhost} for exactly that reason — <b>an address that can never be delivered
 * to, which means the one privileged account on the system had no mail-based recovery path at all.</b> If
 * {@code GATEWAY_ADMIN_PASSWORD} were lost, a password reset could not help, because the link had nowhere to go.
 * Correcting it needed an out-of-band {@code PUT /api/admin/users}, and a fix nobody can perform from
 * configuration is a fix that does not happen.</p>
 *
 * <p><b>The password deliberately still is not reconciled.</b> An operator who has rotated the administrator's
 * password does not want the next restart to put the old one back — that would turn a routine deploy into a
 * silent credential rollback. The email carries no such risk: it is an address, not a secret, and the failure it
 * prevents is being locked out.</p>
 *
 * <p><b>The default is now empty rather than {@code admin@localhost}, and that is what makes reconciliation
 * safe.</b> With a non-empty default, a gateway started without the variable set would "reconcile" a correct
 * address back to {@code admin@localhost} — the change would actively undo the thing it exists to fix, and would
 * do it on every restart. Unset means "leave it alone"; set means "make it so".</p>
 */
@Component
public class AdminBootstrapInitializer implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(AdminBootstrapInitializer.class);

    private final MongoTemplate template;
    private final PasswordEncoder passwordEncoder;
    private final String login;
    private final String email;
    private final String password;

    public AdminBootstrapInitializer(
        MongoTemplate template,
        PasswordEncoder passwordEncoder,
        @Value("${gateway.admin.login:admin}") String login,
        @Value("${gateway.admin.email:}") String email,
        @Value("${gateway.admin.password:}") String password
    ) {
        this.template = template;
        this.passwordEncoder = passwordEncoder;
        this.login = login;
        this.email = email;
        this.password = password;
    }

    /** Empty unless explicitly configured — see the class javadoc on why an empty default is what makes this safe. */
    private String configuredEmail() {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public void run(ApplicationArguments args) {
        User existing = template.findOne(Query.query(Criteria.where("login").is(login)), User.class);
        boolean adminExists = existing != null;

        if (adminExists) {
            reconcileEmail(existing);
        }

        if (password == null || password.isBlank()) {
            if (!adminExists) {
                // Worth a warning rather than a debug line: this is a running gateway that nobody can administer, and
                // the only symptom otherwise is a login that fails for reasons the operator cannot see.
                LOG.warn(
                    "No administrator '{}' exists and gateway.admin.password is not set — nobody can log in as an " +
                    "administrator. Set the GATEWAY_ADMIN_PASSWORD environment variable and restart.",
                    login
                );
            }
            return;
        }

        if (adminExists) {
            LOG.debug("Administrator '{}' already exists; leaving its password untouched", login);
            return;
        }

        User admin = new User();
        admin.setLogin(login);
        admin.setPassword(passwordEncoder.encode(password));
        admin.setFirstName("Administrator");
        admin.setLastName("Account");
        // Falls back to the historical default only at CREATION. Reconciliation treats empty as "leave alone",
        // so the two paths read the empty string differently on purpose.
        admin.setEmail(configuredEmail().isEmpty() ? "admin@localhost" : configuredEmail());
        admin.setActivated(true);
        admin.setLangKey(Constants.DEFAULT_LANGUAGE);
        admin.setCreatedBy(Constants.SYSTEM);
        admin.setCreatedDate(Instant.now());

        Set<Authority> authorities = new HashSet<>();
        authorities.add(SeedData.saveAuthorityIfMissing(template, AuthoritiesConstants.ADMIN));
        authorities.add(SeedData.saveAuthorityIfMissing(template, AuthoritiesConstants.USER));
        admin.setAuthorities(authorities);

        template.save(admin);
        // Never log the password.
        LOG.info("Bootstrapped administrator '{}' from gateway.admin.password", login);
    }

    /**
     * Brings an existing administrator's email in line with {@code gateway.admin.email}.
     *
     * <p>Three things it deliberately will not do.</p>
     *
     * <p><b>Nothing when the property is unset.</b> Empty means "leave it alone", never "reset it to the
     * default" — otherwise a gateway started without the variable would undo a correction on every restart.</p>
     *
     * <p><b>Nothing when another account already holds the address.</b> {@code User.email} is unique, so saving
     * would throw; and the right answer is not to steal the address from whoever has it. It warns instead,
     * because a silent no-op here looks exactly like success.</p>
     *
     * <p><b>Never fail startup.</b> This runs in an {@link ApplicationRunner}: an exception escaping it would
     * take the gateway down, and a wrong administrator email is not worth trading a running gateway for. The
     * failure is logged at WARN and the gateway comes up with the old address — bad, and better than refusing
     * to serve anybody.</p>
     */
    private void reconcileEmail(User admin) {
        String wanted = configuredEmail();
        if (wanted.isEmpty()) {
            return;
        }

        String current = admin.getEmail() == null ? "" : admin.getEmail().trim().toLowerCase(Locale.ROOT);
        if (wanted.equals(current)) {
            return;
        }

        try {
            boolean takenByAnother = template.exists(Query.query(Criteria.where("email").is(wanted).and("login").ne(login)), User.class);
            if (takenByAnother) {
                LOG.warn(
                    "Not changing administrator '{}' to {} — another account already uses that address. " +
                    "The administrator still has {}.",
                    login,
                    wanted,
                    current.isEmpty() ? "no address" : current
                );
                return;
            }

            admin.setEmail(wanted);
            template.save(admin);
            LOG.info("Reconciled administrator '{}' email from {} to {}", login, current.isEmpty() ? "(unset)" : current, wanted);
        } catch (RuntimeException e) {
            LOG.warn(
                "Could not reconcile administrator '{}' email to {} — it is still {}. The gateway is running.",
                login,
                wanted,
                current.isEmpty() ? "(unset)" : current,
                e
            );
        }
    }
}
