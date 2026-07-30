package net.jojoaddison.config.dbmigrations;

import io.mongock.api.annotations.ChangeUnit;
import java.time.Instant;
import net.jojoaddison.config.Constants;
import net.jojoaddison.domain.Authority;
import net.jojoaddison.domain.User;
import net.jojoaddison.security.AuthoritiesConstants;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Creates the initial database setup.
 */
@ChangeUnit(id = "users-initialization", order = "001")
public class InitialSetupMigration implements ApplicationRunner {

    private final MongoTemplate template;
    private final PasswordEncoder passwordEncoder;
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(InitialSetupMigration.class);

    public InitialSetupMigration(MongoTemplate template, PasswordEncoder passwordEncoder) {
        this.template = template;
        this.passwordEncoder = passwordEncoder;
        cleanup();
    }

    public final void cleanup() {
        template.dropCollection(Authority.class);
        template.dropCollection(User.class);
        logger.info("Dropped Authority and User collections");
    }

    @Override
    public void run(ApplicationArguments args) {
        Authority userAuthority = saveAuthorityIfMissing(createUserAuthority());
        Authority adminAuthority = saveAuthorityIfMissing(createAdminAuthority());
        Authority patientAuthority = saveAuthorityIfMissing(createPatientAuthority());
        Authority angelAuthority = saveAuthorityIfMissing(createAngelAuthority());
        saveUserIfMissing(createUser(userAuthority), "user");
        saveUserIfMissing(createAdmin(adminAuthority, userAuthority), "admin");
        saveUserIfMissing(createPatient(patientAuthority, "patient"), "patient");
        saveUserIfMissing(createAngel(angelAuthority, "angel"), "angel");
        logger.info("Initial setup migration completed successfully");
    }

    private Authority saveAuthorityIfMissing(Authority authority) {
        Authority existingAuthority = template.findById(authority.getName(), Authority.class);
        if (existingAuthority != null) {
            return existingAuthority;
        }
        return template.save(authority);
    }

    private void saveUserIfMissing(User user, String login) {
        Query query = Query.query(Criteria.where("login").is(login));
        if (!template.exists(query, User.class)) {
            template.save(user);
        }
    }

    private Authority createAuthority(String authority) {
        Authority adminAuthority = new Authority();
        adminAuthority.setName(authority);
        return adminAuthority;
    }

    private Authority createAdminAuthority() {
        Authority adminAuthority = createAuthority(AuthoritiesConstants.ADMIN);
        return adminAuthority;
    }

    private Authority createUserAuthority() {
        Authority userAuthority = createAuthority(AuthoritiesConstants.USER);
        return userAuthority;
    }

    private Authority createPatientAuthority() {
        Authority patientAuthority = createAuthority(AuthoritiesConstants.PATIENT);
        return patientAuthority;
    }

    private Authority createAngelAuthority() {
        Authority angelAuthority = createAuthority(AuthoritiesConstants.ANGEL);
        return angelAuthority;
    }

    private User createUser(Authority userAuthority) {
        User userUser = new User();
        String login = "user";
        String password = (Character.toUpperCase(login.charAt(0)) + login.substring(1) + "@");
        for (int i = 0; i < login.length(); i++) {
            password += i;
        }
        logger.info("Creating user with login: {} and password: {}", login, password);
        userUser.setId("user-2");
        userUser.setLogin("user");
        userUser.setPassword(passwordEncoder.encode(password));
        userUser.setFirstName("User");
        userUser.setLastName("User");
        userUser.setEmail("user@localhost");
        userUser.setActivated(true);
        userUser.setLangKey("en");
        userUser.setCreatedBy(Constants.SYSTEM);
        userUser.setCreatedDate(Instant.now());
        userUser.getAuthorities().add(userAuthority);
        return userUser;
    }

    private User createAdmin(Authority adminAuthority, Authority userAuthority) {
        User adminUser = new User();
        String login = "admin";
        String password = (Character.toUpperCase(login.charAt(0)) + login.substring(1) + "@");
        for (int i = 0; i < login.length(); i++) {
            password += i;
        }
        logger.info("Creating admin with login: {} and password: {}", login, password);
        adminUser.setId("user-1");
        adminUser.setLogin("admin");
        adminUser.setPassword(passwordEncoder.encode(password));
        adminUser.setFirstName("admin");
        adminUser.setLastName("Administrator");
        adminUser.setEmail("admin@localhost");
        adminUser.setActivated(true);
        adminUser.setLangKey("en");
        adminUser.setCreatedBy(Constants.SYSTEM);
        adminUser.setCreatedDate(Instant.now());
        adminUser.getAuthorities().add(adminAuthority);
        adminUser.getAuthorities().add(userAuthority);
        return adminUser;
    }

    private User createPatient(Authority patientAuthority, String login) {
        User patientUser = new User();
        String password = (Character.toUpperCase(login.charAt(0)) + login.substring(1) + "@");
        for (int i = 0; i < login.length(); i++) {
            password += i;
        }
        logger.info("Creating patient with login: {} and password: {}", login, password);
        patientUser.setId("user-3");
        patientUser.setLogin(login);
        patientUser.setPassword(passwordEncoder.encode(password));
        patientUser.setFirstName("Patient");
        patientUser.setLastName("Patient");
        patientUser.setEmail("patient@localhost");
        patientUser.setActivated(true);
        patientUser.setLangKey("en");
        patientUser.setCreatedBy(Constants.SYSTEM);
        patientUser.setCreatedDate(Instant.now());
        patientUser.getAuthorities().add(patientAuthority);
        return patientUser;
    }

    private User createAngel(Authority angelAuthority, String login) {
        User angelUser = new User();
        String password = (Character.toUpperCase(login.charAt(0)) + login.substring(1) + "@");
        for (int i = 0; i < login.length(); i++) {
            password += i;
        }
        logger.info("Creating angel with login: {} and password: {}", login, password);
        angelUser.setId("user-4");
        angelUser.setLogin(login);
        angelUser.setPassword(passwordEncoder.encode(password));
        angelUser.setFirstName("Angel");
        angelUser.setLastName("Angel");
        angelUser.setEmail("angel@localhost");
        angelUser.setActivated(true);
        angelUser.setLangKey("en");
        angelUser.setCreatedBy(Constants.SYSTEM);
        angelUser.setCreatedDate(Instant.now());
        angelUser.getAuthorities().add(angelAuthority);
        return angelUser;
    }
}
