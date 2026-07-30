package net.jojoaddison.config.dbmigrations;

import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import net.jojoaddison.domain.Authority;
import net.jojoaddison.security.AuthoritiesConstants;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Creates the initial database setup: the {@code ROLE_USER} and {@code ROLE_ADMIN} authorities plus the {@code user}
 * and {@code admin} bootstrap accounts.
 *
 * <p>Each step is idempotent (see {@link SeedData}), so re-running it against a populated database leaves existing
 * records untouched. Patient- and angel-facing seed data is added by {@link PatientRolesMigration} rather than here, so
 * that databases which already ran this change unit still receive it.</p>
 */
@ChangeUnit(id = "users-initialization", order = "001")
public class InitialSetupMigration {

    private final MongoTemplate template;
    private final PasswordEncoder passwordEncoder;

    public InitialSetupMigration(MongoTemplate template, PasswordEncoder passwordEncoder) {
        this.template = template;
        this.passwordEncoder = passwordEncoder;
    }

    @Execution
    public void changeSet() {
        Authority userAuthority = SeedData.saveAuthorityIfMissing(template, AuthoritiesConstants.USER);
        Authority adminAuthority = SeedData.saveAuthorityIfMissing(template, AuthoritiesConstants.ADMIN);
        SeedData.saveUserIfMissing(template, SeedData.buildUser("user-2", "user", "User", "User", passwordEncoder, userAuthority));
        SeedData.saveUserIfMissing(
            template,
            SeedData.buildUser("user-1", "admin", "Admin", "Administrator", passwordEncoder, adminAuthority, userAuthority)
        );
    }

    @RollbackExecution
    public void rollback() {}
}
