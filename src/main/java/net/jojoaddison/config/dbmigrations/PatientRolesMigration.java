package net.jojoaddison.config.dbmigrations;

import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import net.jojoaddison.domain.Authority;
import net.jojoaddison.security.AuthoritiesConstants;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Adds the patient-subsystem authorities ({@code ROLE_PATIENT}, {@code ROLE_ANGEL}) and their bootstrap accounts.
 *
 * <p>This is a separate change unit from {@link InitialSetupMigration} on purpose: databases where change unit
 * {@code 001} has already run would otherwise never receive the new roles, because Mongock records each change unit as
 * executed and will not run it again.</p>
 *
 * <p>Every step is idempotent (see {@link SeedData}).</p>
 */
@ChangeUnit(id = "patient-angel-roles", order = "002")
public class PatientRolesMigration {

    private final MongoTemplate template;
    private final PasswordEncoder passwordEncoder;

    public PatientRolesMigration(MongoTemplate template, PasswordEncoder passwordEncoder) {
        this.template = template;
        this.passwordEncoder = passwordEncoder;
    }

    @Execution
    public void changeSet() {
        Authority patientAuthority = SeedData.saveAuthorityIfMissing(template, AuthoritiesConstants.PATIENT);
        Authority angelAuthority = SeedData.saveAuthorityIfMissing(template, AuthoritiesConstants.ANGEL);
        Authority userAuthority = SeedData.saveAuthorityIfMissing(template, AuthoritiesConstants.USER);
        SeedData.saveUserIfMissing(
            template,
            SeedData.buildUser("user-3", "patient", "Patient", "Patient", passwordEncoder, patientAuthority, userAuthority)
        );
        SeedData.saveUserIfMissing(
            template,
            SeedData.buildUser("user-4", "angel", "Angel", "Angel", passwordEncoder, angelAuthority, userAuthority)
        );
    }

    @RollbackExecution
    public void rollback() {}
}
