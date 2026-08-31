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
 * Two properties, and they pull in opposite directions.
 *
 * <p><b>Security:</b> an unconfigured deployment must end up with no administrator at all, rather than one whose
 * password anybody can guess.</p>
 *
 * <p><b>Recoverability:</b> the administrator's email must be correctable from configuration. Production ran on
 * {@code admin@localhost} for months because it was not — the property was write-once, applied only when the
 * account was created, so setting it afterwards did nothing and the one privileged account on the system had no
 * mail-based recovery path.</p>
 *
 * <p>The tension is that reconciling on every start is one line away from a change that <em>undoes</em> the fix on
 * every restart — see {@link #anUnsetPropertyLeavesACorrectedAddressAlone()}, which is the test that matters most
 * here.</p>
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

    private AdminBootstrapInitializer withEmail(String email, String password) {
        return new AdminBootstrapInitializer(template, passwordEncoder, "admin", email, password);
    }

    /** No administrator in the database. */
    private void noAdmin() {
        when(template.findOne(any(Query.class), eq(User.class))).thenReturn(null);
    }

    /** An existing administrator holding {@code email}, returned by the login lookup. */
    private User existingAdmin(String email) {
        User admin = new User();
        admin.setLogin("admin");
        admin.setEmail(email);
        admin.setPassword("encoded:whatever-was-already-there");
        admin.setActivated(true);
        when(template.findOne(any(Query.class), eq(User.class))).thenReturn(admin);
        // Nobody else holds the address unless a test says otherwise.
        when(template.exists(any(Query.class), eq(User.class))).thenReturn(false);
        return admin;
    }

    // --- creating the first administrator -------------------------------------------------------

    @Test
    @DisplayName("creates no account when no password is configured")
    void createsNothingWithoutAPassword() {
        noAdmin();

        initializer("").run(null);

        verify(template, never()).save(any(User.class));
    }

    @Test
    @DisplayName("treats a blank password as unset — whitespace is not a credential")
    void treatsBlankPasswordAsUnset() {
        noAdmin();

        initializer("   ").run(null);

        verify(template, never()).save(any(User.class));
    }

    @Test
    @DisplayName("null password is tolerated rather than throwing on startup")
    void toleratesNullPassword() {
        noAdmin();

        initializer(null).run(null);

        verify(template, never()).save(any(User.class));
    }

    @Test
    @DisplayName("creates an activated admin with the configured password when the account is missing")
    void createsAdminWhenConfigured() {
        noAdmin();

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
    @DisplayName("an unset email still creates the account with the historical default")
    void createsWithTheHistoricalDefaultWhenEmailIsUnset() {
        noAdmin();

        withEmail("", "a-secret").run(null);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(template).save(saved.capture());
        // Creation and reconciliation read the empty string differently on purpose: here it means "use the
        // default", there it means "leave it alone".
        assertThat(saved.getValue().getEmail()).isEqualTo("admin@localhost");
    }

    @Test
    @DisplayName("honours a configured login other than 'admin'")
    void honoursACustomLogin() {
        noAdmin();

        new AdminBootstrapInitializer(template, passwordEncoder, "root", "root@example.com", "another-secret").run(null);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(template).save(saved.capture());
        assertThat(saved.getValue().getLogin()).isEqualTo("root");
        assertThat(saved.getValue().getEmail()).isEqualTo("root@example.com");
    }

    // --- the password is still never rewritten --------------------------------------------------

    @Test
    @DisplayName("never rewrites an existing administrator's password")
    void leavesAnExistingAdminsPasswordAlone() {
        existingAdmin("admin@localhost");

        // Same email as configured, so nothing to reconcile either — this must be a complete no-op.
        initializer("a-different-password").run(null);

        verify(template, never()).save(any(User.class));
    }

    @Test
    @DisplayName("reconciling the email does not rotate the password")
    void reconcilingDoesNotTouchThePassword() {
        User admin = existingAdmin("admin@localhost");

        withEmail("consultant@jojoaddison.net", "a-different-password").run(null);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(template).save(saved.capture());
        // An operator who rotated the password must not have a deploy put the old one back. Rotating a
        // credential as a side effect of correcting an address would be a silent security regression.
        assertThat(saved.getValue().getPassword()).isEqualTo("encoded:whatever-was-already-there");
    }

    // --- reconciling the email ------------------------------------------------------------------

    @Test
    @DisplayName("corrects an existing administrator's undeliverable address")
    void reconcilesTheEmail() {
        existingAdmin("admin@localhost");

        withEmail("consultant@jojoaddison.net", "irrelevant").run(null);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(template).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("consultant@jojoaddison.net");
    }

    @Test
    @DisplayName("an unset property leaves a corrected address alone rather than resetting it")
    void anUnsetPropertyLeavesACorrectedAddressAlone() {
        // THE TEST THAT MATTERS. Somebody has already fixed the address — by hand, or by a previous deploy that
        // carried the variable. This gateway starts without it set. If empty meant "reset to the default", this
        // change would undo its own fix on every restart, and would do it silently.
        existingAdmin("consultant@jojoaddison.net");

        withEmail("", "irrelevant").run(null);

        verify(template, never()).save(any(User.class));
    }

    @Test
    @DisplayName("a null property is treated as unset, not as an empty address")
    void aNullPropertyIsTreatedAsUnset() {
        existingAdmin("consultant@jojoaddison.net");

        withEmail(null, "irrelevant").run(null);

        verify(template, never()).save(any(User.class));
    }

    @Test
    @DisplayName("does not rewrite when the address already matches, ignoring case and padding")
    void doesNotRewriteWhenAlreadyCorrect() {
        existingAdmin("consultant@jojoaddison.net");

        withEmail("  Consultant@JojoAddison.net  ", "irrelevant").run(null);

        // A write on every restart would churn the audit fields and make a real change impossible to spot.
        verify(template, never()).save(any(User.class));
    }

    @Test
    @DisplayName("refuses to take an address another account already holds")
    void refusesToStealAnAddress() {
        existingAdmin("admin@localhost");
        when(template.exists(any(Query.class), eq(User.class))).thenReturn(true);

        withEmail("someone.else@example.test", "irrelevant").run(null);

        // User.email is unique, so the save would throw — and stealing it from whoever has it is not the right
        // answer either. It warns and leaves the administrator as it was.
        verify(template, never()).save(any(User.class));
    }

    @Test
    @DisplayName("a failed reconciliation does not bring the gateway down")
    void aFailedReconciliationDoesNotFailStartup() {
        existingAdmin("admin@localhost");
        when(template.save(any(User.class))).thenThrow(new IllegalStateException("mongo said no"));

        // This runs in an ApplicationRunner. An exception escaping it takes the whole gateway down, and a wrong
        // administrator address is not worth trading a serving gateway for.
        withEmail("consultant@jojoaddison.net", "irrelevant").run(null);
    }
}
