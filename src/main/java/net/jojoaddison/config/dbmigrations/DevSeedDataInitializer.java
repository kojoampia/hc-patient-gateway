package net.jojoaddison.config.dbmigrations;

import net.jojoaddison.domain.Authority;
import net.jojoaddison.security.AuthoritiesConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import tech.jhipster.config.JHipsterConstants;

/**
 * Seeds the {@code admin}, {@code user}, {@code patient}, {@code angel} and {@code doctor} convenience accounts.
 *
 * <p><strong>Runs under {@code dev} and {@code test} only.</strong> Their passwords come from
 * {@link SeedData#defaultPasswordFor(String)}, which derives a publicly known value from the login, so an environment
 * that anyone else can reach must never have them. This used to be a Mongock change unit that ran in every profile,
 * which is how a production deployment came to accept {@code admin} / {@code Admin@01234}; the profile gate is the
 * whole point of this class, so do not remove it to "make production easier to log into" — that is what
 * {@link AdminBootstrapInitializer} is for. {@code doctor} makes that gate matter more than it used to: it is the only
 * account anywhere that holds {@code ROLE_PROFESSIONAL}, which the patient service treats as unrestricted,
 * cross-patient access. Nothing grants it in production, where it is an administrator's to assign.</p>
 *
 * <p>An {@link ApplicationRunner} rather than a change unit because it must be re-runnable: Mongock records a change
 * unit as executed and never runs it again, so a developer who drops a user could not get it back without editing the
 * changelog collection. Seeding is additive and idempotent — an existing login is left untouched, so accounts created
 * through the API survive a restart — and passwords are never logged.</p>
 */
@Component
@Profile({ JHipsterConstants.SPRING_PROFILE_DEVELOPMENT, JHipsterConstants.SPRING_PROFILE_TEST })
public class DevSeedDataInitializer implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(DevSeedDataInitializer.class);

    private final MongoTemplate template;
    private final PasswordEncoder passwordEncoder;

    public DevSeedDataInitializer(MongoTemplate template, PasswordEncoder passwordEncoder) {
        this.template = template;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        // The ids are fixed (user-1 … user-4) because they were fixed when this seeding lived in the change units, and
        // audit fields in existing development databases refer to them.
        Authority userAuthority = SeedData.saveAuthorityIfMissing(template, AuthoritiesConstants.USER);
        Authority adminAuthority = SeedData.saveAuthorityIfMissing(template, AuthoritiesConstants.ADMIN);
        Authority patientAuthority = SeedData.saveAuthorityIfMissing(template, AuthoritiesConstants.PATIENT);
        Authority angelAuthority = SeedData.saveAuthorityIfMissing(template, AuthoritiesConstants.ANGEL);
        Authority professionalAuthority = SeedData.saveAuthorityIfMissing(template, AuthoritiesConstants.PROFESSIONAL);

        SeedData.saveUserIfMissing(
            template,
            SeedData.buildUser("user-1", "admin", "Admin", "Administrator", passwordEncoder, adminAuthority, userAuthority)
        );
        SeedData.saveUserIfMissing(template, SeedData.buildUser("user-2", "user", "User", "User", passwordEncoder, userAuthority));
        SeedData.saveUserIfMissing(
            template,
            SeedData.buildUser("user-3", "patient", "Patient", "Patient", passwordEncoder, patientAuthority, userAuthority)
        );
        SeedData.saveUserIfMissing(
            template,
            SeedData.buildUser("user-4", "angel", "Angel", "Angel", passwordEncoder, angelAuthority, userAuthority)
        );
        // doctor is the login behind professional-doctor in the patient service's demo dataset, which its own
        // DemoDataInitializer seeds under the same two profiles. That record identifies its professional by
        // accountLogin and joins to this account on doctor@localhost, so the two seeds have to agree on the login.
        SeedData.saveUserIfMissing(
            template,
            SeedData.buildUser("user-5", "doctor", "Ama", "Mensah", passwordEncoder, professionalAuthority, userAuthority)
        );
        LOG.debug("Development seed accounts are present");
    }
}
