package net.jojoaddison.config.dbmigrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import net.jojoaddison.domain.Authority;
import net.jojoaddison.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import tech.jhipster.config.JHipsterConstants;
import tools.jackson.databind.ObjectMapper;

/**
 * Covers the accounts this class seeds from an external document — {@code hc-patient-quality}'s
 * {@code patient-demo-seed.json} in practice, one account per person in the record the patient service seeds.
 *
 * <p>The five fixed accounts are asserted here too, but only that they are still there: they are what the quality
 * stack signs in with, and {@code doctor} is the login the patient service's demo clinician joins to.</p>
 */
class DevSeedDataInitializerTest {

    private static final String FIXTURE = "classpath:config/seed-document-fixture.json";

    private MongoTemplate template;
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        template = mock(MongoTemplate.class);
        passwordEncoder = mock(PasswordEncoder.class);
        when(passwordEncoder.encode(anyString())).thenAnswer(invocation -> "encoded:" + invocation.getArgument(0));
        when(template.save(any(Authority.class))).thenAnswer(invocation -> invocation.getArgument(0));
        // Nothing is stored yet, so every account this run builds is a new one.
        when(template.exists(any(Query.class), eq(User.class))).thenReturn(false);
    }

    private DevSeedDataInitializer initializer(String location, String... activeProfiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(activeProfiles);
        return new DevSeedDataInitializer(
            template,
            passwordEncoder,
            location,
            new ObjectMapper(),
            new DefaultResourceLoader(),
            environment
        );
    }

    private List<User> savedUsers() {
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        org.mockito.Mockito.verify(template, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        return saved.getAllValues();
    }

    @Test
    @DisplayName("seeds the five fixed accounts when no document is configured")
    void seedsTheFixedAccountsWithoutADocument() {
        initializer("", JHipsterConstants.SPRING_PROFILE_DEVELOPMENT).run(null);

        assertThat(savedUsers()).extracting(User::getLogin).containsExactly("admin", "user", "patient", "angel", "doctor");
    }

    @Test
    @DisplayName("adds one account per person in the document, on top of the fixed five")
    void seedsTheDocumentsAccounts() {
        initializer(FIXTURE, JHipsterConstants.SPRING_PROFILE_DEVELOPMENT).run(null);

        assertThat(savedUsers())
            .extracting(User::getLogin)
            .containsExactly("admin", "user", "patient", "angel", "doctor", "grace", "ophelia");
    }

    @Test
    @DisplayName("takes each account's authorities from the document, not from this class")
    void takesAuthoritiesFromTheDocument() {
        initializer(FIXTURE, JHipsterConstants.SPRING_PROFILE_DEVELOPMENT).run(null);

        // The care angel in the record holds ROLE_ANGEL, and nothing in this class knows that she is one.
        User angel = savedUsers().stream().filter(user -> "ophelia".equals(user.getLogin())).findFirst().orElseThrow();
        assertThat(angel.getAuthorities()).extracting(Authority::getName).containsExactlyInAnyOrder("ROLE_ANGEL", "ROLE_USER");
    }

    @Test
    @DisplayName("gives an account with no declared email the <login>@localhost the patient service joins on")
    void defaultsTheEmailToTheJoinAddress() {
        initializer(FIXTURE, JHipsterConstants.SPRING_PROFILE_DEVELOPMENT).run(null);

        User angel = savedUsers().stream().filter(user -> "ophelia".equals(user.getLogin())).findFirst().orElseThrow();
        assertThat(angel.getEmail()).isEqualTo("ophelia@localhost");
    }

    @Test
    @DisplayName("derives a password for an account that declares none, by the same public rule as the fixed accounts")
    void derivesAnUndeclaredPassword() {
        initializer(FIXTURE, JHipsterConstants.SPRING_PROFILE_DEVELOPMENT).run(null);

        User angel = savedUsers().stream().filter(user -> "ophelia".equals(user.getLogin())).findFirst().orElseThrow();
        assertThat(angel.getPassword()).isEqualTo("encoded:" + SeedData.defaultPasswordFor("ophelia"));
    }

    @Test
    @DisplayName("applies every active profile block — the quality stack runs dev,test")
    void appliesEveryActiveProfileBlock() {
        initializer(FIXTURE, JHipsterConstants.SPRING_PROFILE_DEVELOPMENT, JHipsterConstants.SPRING_PROFILE_TEST).run(null);

        assertThat(savedUsers()).extracting(User::getLogin).contains("grace", "testonly");
    }

    @Test
    @DisplayName("applies only the blocks that are active")
    void appliesOnlyActiveBlocks() {
        initializer(FIXTURE, JHipsterConstants.SPRING_PROFILE_TEST).run(null);

        assertThat(savedUsers()).extracting(User::getLogin).contains("testonly").doesNotContain("grace");
    }

    @Test
    @DisplayName("seeds the fixed accounts anyway when the document is missing")
    void survivesAMissingDocument() {
        // A gateway that will not start takes the whole subsystem's login with it, so a seed file that is not there
        // is a warning and nothing more.
        initializer("file:/does/not/exist.json", JHipsterConstants.SPRING_PROFILE_DEVELOPMENT).run(null);

        assertThat(savedUsers()).extracting(User::getLogin).containsExactly("admin", "user", "patient", "angel", "doctor");
    }

    @Test
    @DisplayName("is gated to dev and test")
    void isGatedToDevelopmentAndTest() {
        // These passwords are derived from their logins by a rule in a public repository, and one of these accounts
        // can read every patient record. The gate is the only thing keeping them out of a real deployment.
        org.springframework.context.annotation.Profile gate =
            DevSeedDataInitializer.class.getAnnotation(org.springframework.context.annotation.Profile.class);

        assertThat(gate).isNotNull();
        assertThat(gate.value()).containsExactlyInAnyOrder(
            JHipsterConstants.SPRING_PROFILE_DEVELOPMENT,
            JHipsterConstants.SPRING_PROFILE_TEST
        );
    }
}
