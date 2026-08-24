package net.jojoaddison.config.dbmigrations;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
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
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import tech.jhipster.config.JHipsterConstants;
import tools.jackson.databind.ObjectMapper;

/**
 * Seeds the {@code admin}, {@code user}, {@code patient}, {@code angel} and {@code doctor} convenience accounts, and
 * then any accounts named by an external seed document.
 *
 * <p><strong>Runs under {@code dev} and {@code test} only.</strong> Their passwords come from
 * {@link SeedData#defaultPasswordFor(String)}, which derives a publicly known value from the login, so an environment
 * that anyone else can reach must never have them. This used to be a Mongock change unit that ran in every profile,
 * which is how a production deployment came to accept {@code admin} / {@code Admin@01234}; the profile gate is the
 * whole point of this class, so do not remove it to "make production easier to log into" — that is what
 * {@link AdminBootstrapInitializer} is for. {@code doctor} makes that gate matter more than it used to: it is the only
 * fixed account anywhere that holds a clinical discipline ({@code ROLE_DOCTOR} since 2026-08-24, {@code
 * ROLE_PROFESSIONAL} before it), which the patient service treats as unrestricted, cross-patient access. Nothing
 * grants one in production, where they are an administrator's to assign.</p>
 *
 * <p>An {@link ApplicationRunner} rather than a change unit because it must be re-runnable: Mongock records a change
 * unit as executed and never runs it again, so a developer who drops a user could not get it back without editing the
 * changelog collection. Seeding is additive and idempotent — an existing login is left untouched, so accounts created
 * through the API survive a restart — and passwords are never logged.</p>
 *
 * <h2>The external document</h2>
 *
 * <p>When {@code hc.seed.location} names a document, its {@code users} array is seeded too — one account per person in
 * whatever record that document describes. It is unset in this repository; the stack that sets it is
 * {@code hc-patient-quality}, whose {@code quality/patient-demo-seed.json} is extracted from the dashboard's
 * {@code patient-web-demo.html}. <strong>The same file is read by the patient service</strong>, which takes the
 * clinical collections from it and ignores {@code users}, exactly as this class ignores those collections. The join
 * between the two halves is the email {@code <login>@localhost}, which a seeded {@code Professional} carries and an
 * account here is given.</p>
 *
 * <p>The document is keyed by profile, and <strong>every active profile's block is applied</strong> — {@code dev} then
 * {@code test} — which is what {@code hc-admin}'s equivalent does and what the quality stack, running with both
 * active, depends on. Those accounts' authorities come from the document rather than from this class, so a record with
 * a care angel in it produces an account holding {@code ROLE_ANGEL} without anything here knowing that role exists.</p>
 */
@Component
@Profile({ JHipsterConstants.SPRING_PROFILE_DEVELOPMENT, JHipsterConstants.SPRING_PROFILE_TEST })
public class DevSeedDataInitializer implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(DevSeedDataInitializer.class);

    private final MongoTemplate template;
    private final PasswordEncoder passwordEncoder;
    private final String location;
    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;
    private final Environment environment;

    public DevSeedDataInitializer(
        MongoTemplate template,
        PasswordEncoder passwordEncoder,
        @Value("${hc.seed.location:}") String location,
        ObjectMapper objectMapper,
        ResourceLoader resourceLoader,
        Environment environment
    ) {
        this.template = template;
        this.passwordEncoder = passwordEncoder;
        this.location = location;
        this.objectMapper = objectMapper;
        this.resourceLoader = resourceLoader;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        // The ids are fixed (user-1 … user-4) because they were fixed when this seeding lived in the change units, and
        // audit fields in existing development databases refer to them.
        Authority userAuthority = SeedData.saveAuthorityIfMissing(template, AuthoritiesConstants.USER);
        Authority adminAuthority = SeedData.saveAuthorityIfMissing(template, AuthoritiesConstants.ADMIN);
        Authority patientAuthority = SeedData.saveAuthorityIfMissing(template, AuthoritiesConstants.PATIENT);
        Authority angelAuthority = SeedData.saveAuthorityIfMissing(template, AuthoritiesConstants.ANGEL);
        Authority doctorAuthority = SeedData.saveAuthorityIfMissing(template, AuthoritiesConstants.DOCTOR);

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
            SeedData.buildUser("user-5", "doctor", "Ama", "Mensah", passwordEncoder, doctorAuthority, userAuthority)
        );
        LOG.debug("Development seed accounts are present");

        seedFromDocument();
    }

    /** Seeds the accounts named by {@code hc.seed.location}, when it is set and readable. */
    private void seedFromDocument() {
        if (location == null || location.isBlank()) {
            LOG.debug("hc.seed.location is not set; no external seed accounts will be loaded");
            return;
        }

        SeedDocument document = read();
        if (document == null) {
            return;
        }
        if (environment.acceptsProfiles(Profiles.of(JHipsterConstants.SPRING_PROFILE_DEVELOPMENT))) {
            seed(JHipsterConstants.SPRING_PROFILE_DEVELOPMENT, document.dev);
        }
        if (environment.acceptsProfiles(Profiles.of(JHipsterConstants.SPRING_PROFILE_TEST))) {
            seed(JHipsterConstants.SPRING_PROFILE_TEST, document.test);
        }
    }

    /**
     * @return the parsed document, or {@code null} when it is missing or unreadable — which is logged and otherwise
     *     ignored, because seed accounts are a development convenience and a broken file must not stop the gateway
     *     from starting. A gateway that will not start takes the whole subsystem's login with it.
     */
    private SeedDocument read() {
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            LOG.warn("hc.seed.location points at {}, which does not exist; no seed accounts will be loaded", location);
            return null;
        }
        try (InputStream source = resource.getInputStream()) {
            return objectMapper.readValue(source, SeedDocument.class);
        } catch (IOException | RuntimeException e) {
            LOG.warn("Failed to read seed accounts from {}; none will be loaded", location, e);
            return null;
        }
    }

    private void seed(String profile, ProfileData data) {
        if (data == null || data.users == null || data.users.isEmpty()) {
            LOG.debug("The seed document carries no '{}' accounts", profile);
            return;
        }
        data.users.stream().filter(Objects::nonNull).forEach(this::createUserIfMissing);
    }

    private void createUserIfMissing(SeedUser seedUser) {
        if (seedUser.login == null || seedUser.login.isBlank()) {
            LOG.warn("Skipping a seed account with no login");
            return;
        }

        User user = new User();
        if (seedUser.id != null && !seedUser.id.isBlank()) {
            user.setId(seedUser.id);
        }
        user.setLogin(seedUser.login);
        user.setPassword(passwordEncoder.encode(seedUser.resolvePassword()));
        user.setFirstName(seedUser.firstName);
        user.setLastName(seedUser.lastName);
        // <login>@localhost is what the patient service's Professional records join on, so a document that leaves the
        // address out still produces the address the other half is looking for.
        user.setEmail(seedUser.email == null || seedUser.email.isBlank() ? seedUser.login + "@localhost" : seedUser.email);
        user.setActivated(!Boolean.FALSE.equals(seedUser.activated));
        user.setLangKey(Constants.DEFAULT_LANGUAGE);
        user.setCreatedBy(Constants.SYSTEM);
        user.setCreatedDate(Instant.now());

        Set<Authority> authorities = new HashSet<>();
        seedUser.authorities.forEach(name -> authorities.add(SeedData.saveAuthorityIfMissing(template, name)));
        user.setAuthorities(authorities);

        // Additive, like everything else here: an existing login keeps whatever it has now, including a password
        // someone changed through the API. Never logs the password.
        SeedData.saveUserIfMissing(template, user);
    }

    /** Root of the seed document: one block per profile. Must stay {@code static} so Jackson can instantiate it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class SeedDocument {

        public ProfileData dev;
        public ProfileData test;
    }

    /**
     * One profile's block. The clinical collections in the same document belong to the patient service and are
     * unknown here, which is what {@code ignoreUnknown} is for — one file, read by two services, neither of which has
     * to know the other's half.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ProfileData {

        public List<SeedUser> users = new ArrayList<>();
    }

    /** A single seed account. Must stay {@code static} so Jackson can instantiate it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class SeedUser {

        public String id;
        public String login;
        public String email;
        public String firstName;
        public String lastName;
        public String password;
        public Boolean activated;
        public List<String> authorities = new ArrayList<>();

        /**
         * @return the declared password, or the value {@link SeedData#defaultPasswordFor(String)} derives from the
         *     login when the document declares none — the same publicly known rule the fixed accounts above use, so a
         *     document does not have to restate it.
         */
        String resolvePassword() {
            return password == null || password.isBlank() ? SeedData.defaultPasswordFor(login) : password;
        }
    }
}
