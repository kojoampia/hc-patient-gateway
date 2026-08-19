package net.jojoaddison.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Pattern;
import net.jojoaddison.config.Constants;
import org.junit.jupiter.api.Test;

/**
 * The login a care angel's account is derived from: first initial, last letter of the first name, underscore, surname.
 *
 * <p>Every case here is a real name rather than a hypothetical, and each one produced a login the system would have
 * refused or duplicated if it were not handled.</p>
 */
class CareAngelLoginUnitTest {

    private static final Pattern LOGIN = Pattern.compile(Constants.LOGIN_REGEX);

    @Test
    void theOrdinaryCase() {
        assertThat(CareAngelService.deriveLogin("Grace", "Mensah", "grace@example.test")).isEqualTo("ge_mensah");
    }

    @Test
    void accentsAreTransliteratedRatherThanRejected() {
        // LOGIN_REGEX permits only [_.@A-Za-z0-9-], so an accented login could not be created at all. An approximate
        // login beats a nomination that fails.
        String login = CareAngelService.deriveLogin("Ámà", "Owusú", "ama@example.test");

        assertThat(login).isEqualTo("aa_owusu");
        assertThat(LOGIN.matcher(login).matches()).as("must satisfy the gateway's own login pattern").isTrue();
    }

    @Test
    void aOneCharacterFirstNameRepeatsItsOnlyLetter() {
        // First and last letter are the same letter. Legal and unambiguous — deliberately not "fixed".
        assertThat(CareAngelService.deriveLogin("A", "Boateng", "a@example.test")).isEqualTo("aa_boateng");
    }

    @Test
    void aMissingSurnameFallsBackToTheEmailRatherThanTrailingAnUnderscore() {
        assertThat(CareAngelService.deriveLogin("Kofi", "", "kofi.b@example.test")).isEqualTo("kofib");
        assertThat(CareAngelService.deriveLogin(null, null, "esi@example.test")).isEqualTo("esi");
    }

    @Test
    void aNameWithNothingUsableStillYieldsALegalLogin() {
        // A nomination is never refused for being hard to spell.
        String login = CareAngelService.deriveLogin("***", "***", "***@example.test");

        assertThat(login).isNotBlank();
        assertThat(LOGIN.matcher(login).matches()).isTrue();
    }

    @Test
    void everyDerivedLoginSatisfiesTheLoginPattern() {
        String[][] names = { { "Grace", "Mensah" }, { "Ámà", "Owusú" }, { "A", "B" }, { "Jean-Luc", "Picard" } };
        for (String[] name : names) {
            String login = CareAngelService.deriveLogin(name[0], name[1], "x@example.test");
            assertThat(LOGIN.matcher(login).matches()).as("login %s from %s %s", login, name[0], name[1]).isTrue();
        }
    }
}
