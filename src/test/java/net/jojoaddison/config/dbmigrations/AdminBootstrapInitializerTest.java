package net.jojoaddison.config.dbmigrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import net.jojoaddison.domain.Authority;
import net.jojoaddison.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The point of these tests is a security property: an unconfigured deployment must end up with no administrator at
 * all, rather than with one whose password anybody can guess.
 */
class AdminBootstrapInitializerTest {

    private MongoTemplate template;
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        template = mock(MongoTemplate.class);
        passwordEncoder = mock(PasswordEncoder.class);
        when(passwordEncoder.encode(anyString())).thenAnswer(invocation -> "encoded:" + invocation.getArgument(0));
        when(template.save(any(Authority.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private AdminBootstrapInitializer initializer(String password) {
        return new AdminBootstrapInitializer(template, passwordEncoder, "admin", "admin@localhost", password);
    }

    private void adminExists(boolean exists) {
        when(template.exists(any(Query.class), eq(User.class))).thenReturn(exists);
    }

    @Test
    @DisplayName("creates no account when no password is configured")
    void createsNothingWithoutAPassword() {
        adminExists(false);

        initializer("").run(null);

        verify(template, never()).save(any(User.class));
    }

    @Test
    @DisplayName("treats a blank password as unset — whitespace is not a credential")
    void treatsBlankPasswordAsUnset() {
        adminExists(false);

        initializer("   ").run(null);

        verify(template, never()).save(any(User.class));
    }

    @Test
    @DisplayName("null password is tolerated rather than throwing on startup")
    void toleratesNullPassword() {
        adminExists(false);

        initializer(null).run(null);

        verify(template, never()).save(any(User.class));
    }

    @Test
    @DisplayName("creates an activated admin with the configured password when the account is missing")
    void createsAdminWhenConfigured() {
        adminExists(false);

        initializer("s3cret-from-the-environment").run(null);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(template).save(saved.capture());
        User admin = saved.getValue();
        assertThat(admin.getLogin()).isEqualTo("admin");
        assertThat(admin.getEmail()).isEqualTo("admin@localhost");
        assertThat(admin.isActivated()).isTrue();
        assertThat(admin.getPassword()).isEqualTo("encoded:s3cret-from-the-environment");
        assertThat(admin.getAuthorities()).extracting(Authority::getName).containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_USER");
    }

    @Test
    @DisplayName("never rewrites an existing administrator's password")
    void leavesAnExistingAdminAlone() {
        adminExists(true);

        initializer("a-different-password").run(null);

        verify(template, never()).save(any(User.class));
    }

    @Test
    @DisplayName("honours a configured login other than 'admin'")
    void honoursACustomLogin() {
        adminExists(false);

        new AdminBootstrapInitializer(template, passwordEncoder, "root", "root@example.com", "another-secret").run(null);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(template).save(saved.capture());
        assertThat(saved.getValue().getLogin()).isEqualTo("root");
        assertThat(saved.getValue().getEmail()).isEqualTo("root@example.com");
    }
}
