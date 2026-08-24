package net.jojoaddison.config.dbmigrations;

import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import net.jojoaddison.security.AuthoritiesConstants;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Adds the clinical-staff authority: {@code ROLE_PROFESSIONAL}.
 *
 * <p><strong>Superseded on 2026-08-24 by {@link ClinicalDisciplineRolesMigration} (004), which removes this
 * authority again and replaces it with the eight clinical disciplines.</strong> This class is kept rather than
 * deleted because Mongock records a change unit as executed and never re-runs it: deleting it would have left the
 * authority in every existing database with nothing to take it out. Read it as the record of what was applied on
 * 2026-08-11, not as a description of what the schema holds today.</p>
 *
 * <p>A separate change unit from {@link PatientRolesMigration} for the same reason that one is separate from
 * {@link InitialSetupMigration}: Mongock records each change unit as executed and will not run it again, so a database
 * where {@code 002} has already run would otherwise never receive the new role.</p>
 *
 * <p>Like the change units before it, this seeds an <strong>authority only, in every profile</strong>, and grants it
 * to nobody. The authority is structural — the patient service gates cross-patient reads and staff-only writes on it,
 * so a database without it cannot express "clinical staff" at all. Who holds it is a separate decision:
 * {@link DevSeedDataInitializer} grants it to the {@code doctor} account under {@code dev} and {@code test}, and
 * nothing grants it in production, where it is an administrator's to assign. Registration hardcodes
 * {@code ROLE_USER}, so it cannot be self-granted.</p>
 *
 * <p>Idempotent (see {@link SeedData}), so re-running it against a populated database leaves existing records
 * untouched.</p>
 */
@ChangeUnit(id = "professional-role", order = "003")
public class ProfessionalRoleMigration {

    private final MongoTemplate template;

    public ProfessionalRoleMigration(MongoTemplate template) {
        this.template = template;
    }

    @Execution
    public void changeSet() {
        SeedData.saveAuthorityIfMissing(template, AuthoritiesConstants.REMOVED_PROFESSIONAL);
    }

    @RollbackExecution
    public void rollback() {}
}
