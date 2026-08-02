package net.jojoaddison.config.dbmigrations;

import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import net.jojoaddison.security.AuthoritiesConstants;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Adds the patient-subsystem authorities: {@code ROLE_PATIENT} and {@code ROLE_ANGEL}.
 *
 * <p>This is a separate change unit from {@link InitialSetupMigration} on purpose: databases where change unit
 * {@code 001} has already run would otherwise never receive the new roles, because Mongock records each change unit as
 * executed and will not run it again.</p>
 *
 * <p>Like {@code 001} it seeds authorities only, in every profile, and creates no accounts — see that class for why.
 * Every step is idempotent (see {@link SeedData}).</p>
 */
@ChangeUnit(id = "patient-angel-roles", order = "002")
public class PatientRolesMigration {

    private final MongoTemplate template;

    public PatientRolesMigration(MongoTemplate template) {
        this.template = template;
    }

    @Execution
    public void changeSet() {
        SeedData.saveAuthorityIfMissing(template, AuthoritiesConstants.PATIENT);
        SeedData.saveAuthorityIfMissing(template, AuthoritiesConstants.ANGEL);
        SeedData.saveAuthorityIfMissing(template, AuthoritiesConstants.USER);
    }

    @RollbackExecution
    public void rollback() {}
}
