package net.jojoaddison.config.dbmigrations;

import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import net.jojoaddison.security.AuthoritiesConstants;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Creates the initial database setup: the {@code ROLE_USER} and {@code ROLE_ADMIN} authorities.
 *
 * <p>This change unit seeds <strong>authorities only, in every profile</strong>. The authorities are structural — the
 * registration flow assigns {@code ROLE_USER} to every new account, so a database without them is broken rather than
 * merely empty.</p>
 *
 * <p>It deliberately creates <strong>no accounts</strong>. It used to seed {@code admin} and {@code user} with
 * passwords derived from their logins, in every profile including production, which meant any deployment shipped
 * publicly known credentials. Development accounts now come from {@link DevSeedDataInitializer} (which runs under
 * {@code dev} and {@code test} only) and the first production administrator from
 * {@link AdminBootstrapInitializer} (which ships no default password at all).</p>
 *
 * <p>Each step is idempotent (see {@link SeedData}), so re-running it against a populated database leaves existing
 * records untouched.</p>
 */
@ChangeUnit(id = "users-initialization", order = "001")
public class InitialSetupMigration {

    private final MongoTemplate template;

    public InitialSetupMigration(MongoTemplate template) {
        this.template = template;
    }

    @Execution
    public void changeSet() {
        SeedData.saveAuthorityIfMissing(template, AuthoritiesConstants.USER);
        SeedData.saveAuthorityIfMissing(template, AuthoritiesConstants.ADMIN);
    }

    @RollbackExecution
    public void rollback() {}
}
