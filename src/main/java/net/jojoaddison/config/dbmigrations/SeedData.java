package net.jojoaddison.config.dbmigrations;

import java.time.Instant;
import net.jojoaddison.config.Constants;
import net.jojoaddison.domain.Authority;
import net.jojoaddison.domain.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Shared, idempotent helpers for the Mongock change units that seed authorities and bootstrap accounts.
 *
 * <p>Every method is safe to run against a database that already holds the record it would create, so a change unit
 * can be re-run (or a new one added for existing databases) without duplicating or clobbering data.</p>
 *
 * <p><strong>The seeded accounts are development credentials.</strong> Their passwords come from
 * {@link #defaultPasswordFor(String)}, which derives a well-known value from the login, so they must be rotated or the
 * accounts removed before exposing an environment publicly. Passwords are never logged.</p>
 */
final class SeedData {

    private static final Logger LOG = LoggerFactory.getLogger(SeedData.class);

    private SeedData() {}

    /**
     * Derives the well-known bootstrap password for a login: the capitalised login, {@code @}, then one digit per
     * character of the login ({@code admin} becomes {@code Admin@01234}).
     *
     * @param login the login to derive a password for.
     * @return the plaintext password, to be encoded before it is stored.
     */
    static String defaultPasswordFor(String login) {
        StringBuilder password = new StringBuilder().append(Character.toUpperCase(login.charAt(0))).append(login.substring(1)).append('@');
        for (int i = 0; i < login.length(); i++) {
            password.append(i);
        }
        return password.toString();
    }

    /**
     * Saves an authority unless one with the same name already exists.
     *
     * @param template the Mongo template to use.
     * @param name the authority name, e.g. {@code ROLE_USER}.
     * @return the existing authority when present, otherwise the newly saved one.
     */
    static Authority saveAuthorityIfMissing(MongoTemplate template, String name) {
        Authority existing = template.findById(name, Authority.class);
        if (existing != null) {
            return existing;
        }
        Authority authority = new Authority();
        authority.setName(name);
        LOG.debug("Seeding authority {}", name);
        return template.save(authority);
    }

    /**
     * Saves a user unless one with the same login already exists.
     *
     * @param template the Mongo template to use.
     * @param user the user to save.
     * @return {@code true} when the user was created, {@code false} when a user with that login was already present.
     */
    static boolean saveUserIfMissing(MongoTemplate template, User user) {
        Query byLogin = Query.query(Criteria.where("login").is(user.getLogin()));
        if (template.exists(byLogin, User.class)) {
            LOG.debug("User {} already exists, leaving it untouched", user.getLogin());
            return false;
        }
        template.save(user);
        LOG.info("Seeded user {} with a well-known development password — rotate it outside development", user.getLogin());
        return true;
    }

    /**
     * Builds an activated, system-created user whose password is the encoded {@link #defaultPasswordFor(String)} value.
     *
     * @param id the fixed document id.
     * @param login the login, also used to derive the password and the {@code <login>@localhost} email.
     * @param firstName the first name.
     * @param lastName the last name.
     * @param passwordEncoder the encoder to hash the derived password with.
     * @param authorities the authorities to grant.
     * @return the unsaved user.
     */
    static User buildUser(
        String id,
        String login,
        String firstName,
        String lastName,
        PasswordEncoder passwordEncoder,
        Authority... authorities
    ) {
        User user = new User();
        user.setId(id);
        user.setLogin(login);
        user.setPassword(passwordEncoder.encode(defaultPasswordFor(login)));
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setEmail(login + "@localhost");
        user.setActivated(true);
        user.setLangKey("en");
        user.setCreatedBy(Constants.SYSTEM);
        user.setCreatedDate(Instant.now());
        for (Authority authority : authorities) {
            user.getAuthorities().add(authority);
        }
        return user;
    }
}
