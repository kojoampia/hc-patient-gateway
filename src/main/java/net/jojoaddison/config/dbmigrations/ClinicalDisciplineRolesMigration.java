package net.jojoaddison.config.dbmigrations;

import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.util.List;
import net.jojoaddison.domain.Authority;
import net.jojoaddison.domain.User;
import net.jojoaddison.security.AuthoritiesConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Replaces the blanket {@code ROLE_PROFESSIONAL} with the eight clinical disciplines.
 *
 * <h2>Why this is a change unit and not a deletion</h2>
 *
 * <p>{@link ProfessionalRoleMigration} (003) seeded {@code ROLE_PROFESSIONAL} in every profile and has already run
 * everywhere that matters — production applied it on 2026-08-11. Mongock records a change unit as executed and never
 * runs it again, so <strong>deleting that class would have removed nothing from any existing database</strong>: the
 * authority would have stayed in the collection, stayed on the accounts holding it, and stayed in tokens, while the
 * patient service quietly stopped honouring it. Removal has to be its own forward step, which is this one. 003 is
 * kept as the record of what was applied, not as something to re-run.</p>
 *
 * <h2>It replaces rather than strips</h2>
 *
 * <p>Every account holding {@code ROLE_PROFESSIONAL} is given {@code ROLE_DOCTOR} and then has the old role removed.
 * Doctor is the discipline whose reach matches what the blanket role granted — {@code ScopeOfPractice} gives it every
 * clinical domain, which is what {@code ROLE_PROFESSIONAL} had — so nobody's capability changes on the day. Removing
 * without replacing would have been the same outage as leaving it in place, arrived at from the other direction: an
 * account that signs in exactly as before and is served empty lists.</p>
 *
 * <p>Anyone whose real discipline is not doctor has to be corrected by an administrator afterwards, and that is the
 * right way round — this migration cannot know that the person behind an account is a nurse, and guessing narrower
 * would lock somebody out of their own work.</p>
 *
 * <h2>Reading it rather than querying it</h2>
 *
 * <p>The holders are found by loading users and asking each one, rather than by a Mongo query against
 * {@code authorities.name}. {@link Authority}'s name is its {@code @Id}, so the embedded subdocument's field name is
 * a detail of how Spring Data flattens it — a query that guessed wrong would match nothing, report zero holders, and
 * look exactly like a clean run. The user collection holds a handful of accounts; correctness is worth more than the
 * query here.</p>
 *
 * <p><strong>In production this touches no account.</strong> The authority was seeded there and granted to nobody, so
 * the loop finds nothing and the net effect is eight authority documents an administrator may assign. Check it the
 * way 003 was checked — the applied line in the gateway log, which names the count.</p>
 *
 * <p>Idempotent, like the change units before it.</p>
 */
@ChangeUnit(id = "clinical-discipline-roles", order = "004")
public class ClinicalDisciplineRolesMigration {

    private static final Logger LOG = LoggerFactory.getLogger(ClinicalDisciplineRolesMigration.class);

    private final MongoTemplate template;

    public ClinicalDisciplineRolesMigration(MongoTemplate template) {
        this.template = template;
    }

    @Execution
    public void changeSet() {
        AuthoritiesConstants.CLINICAL.forEach(name -> SeedData.saveAuthorityIfMissing(template, name));
        Authority doctor = SeedData.saveAuthorityIfMissing(template, AuthoritiesConstants.DOCTOR);

        List<User> holders = template
            .findAll(User.class)
            .stream()
            .filter(
                user ->
                    user
                        .getAuthorities()
                        .stream()
                        .anyMatch(authority -> AuthoritiesConstants.REMOVED_PROFESSIONAL.equals(authority.getName()))
            )
            .toList();

        for (User user : holders) {
            user.getAuthorities().removeIf(authority -> AuthoritiesConstants.REMOVED_PROFESSIONAL.equals(authority.getName()));
            user.getAuthorities().add(doctor);
            template.save(user);
            LOG.info("Replaced ROLE_PROFESSIONAL with ROLE_DOCTOR on account {}", user.getLogin());
        }

        template.remove(Query.query(Criteria.where("_id").is(AuthoritiesConstants.REMOVED_PROFESSIONAL)), Authority.class);

        LOG.info(
            "Seeded {} clinical disciplines and removed ROLE_PROFESSIONAL from {} account(s)",
            AuthoritiesConstants.CLINICAL.size(),
            holders.size()
        );
    }

    @RollbackExecution
    public void rollback() {}
}
