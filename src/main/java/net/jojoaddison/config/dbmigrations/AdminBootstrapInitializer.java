package net.jojoaddison.config.dbmigrations;

import java.time.Instant;
import java.util.HashSet;
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
 * (default {@code admin@localhost}). The password is never logged.</p>
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
        @Value("${gateway.admin.email:admin@localhost}") String email,
        @Value("${gateway.admin.password:}") String password
    ) {
        this.template = template;
        this.passwordEncoder = passwordEncoder;
        this.login = login;
        this.email = email;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean adminExists = template.exists(Query.query(Criteria.where("login").is(login)), User.class);

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
        admin.setEmail(email);
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
}
