package net.jojoaddison.config.dbmigrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import net.jojoaddison.domain.Authority;
import net.jojoaddison.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Guards the fix for a defect that reached production: the Mongock change units used to create {@code admin},
 * {@code user}, {@code patient} and {@code angel} with passwords derived from their logins, in <em>every</em> profile.
 * A public deployment therefore accepted publicly known credentials.
 *
 * <p>Change units run unconditionally — Mongock has no notion of a Spring profile — so the only safe rule is that they
 * create no accounts at all. Authorities are different: they are structural (registration grants {@code ROLE_USER}), so
 * they must still be seeded everywhere.</p>
 */
class MigrationsSeedNoAccountsTest {

    private MongoTemplate template;
    private List<Authority> savedAuthorities;

    @BeforeEach
    void setUp() {
        template = mock(MongoTemplate.class);
        savedAuthorities = new ArrayList<>();
        when(template.findById(any(), any())).thenReturn(null);
        when(template.save(any(Authority.class))).thenAnswer(invocation -> {
            savedAuthorities.add(invocation.getArgument(0));
            return invocation.getArgument(0);
        });
    }

    @Test
    @DisplayName("001 seeds ROLE_USER and ROLE_ADMIN and creates no accounts")
    void initialSetupSeedsAuthoritiesOnly() {
        new InitialSetupMigration(template).changeSet();

        assertThat(savedAuthorities).extracting(Authority::getName).containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
        verify(template, never()).save(any(User.class));
    }

    @Test
    @DisplayName("002 seeds the patient roles and creates no accounts")
    void patientRolesSeedsAuthoritiesOnly() {
        new PatientRolesMigration(template).changeSet();

        assertThat(savedAuthorities).extracting(Authority::getName).containsExactlyInAnyOrder("ROLE_PATIENT", "ROLE_ANGEL", "ROLE_USER");
        verify(template, never()).save(any(User.class));
    }

    @Test
    @DisplayName("the accounts with derived passwords are seeded only under dev and test")
    void developmentAccountsAreProfileGated() {
        Profile profile = DevSeedDataInitializer.class.getAnnotation(Profile.class);

        assertThat(profile).as("DevSeedDataInitializer must never run in production").isNotNull();
        assertThat(profile.value()).containsExactlyInAnyOrder("dev", "test");
    }
}
