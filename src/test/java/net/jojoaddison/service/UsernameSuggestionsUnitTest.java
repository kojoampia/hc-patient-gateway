package net.jojoaddison.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.jojoaddison.config.Constants;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the candidate generation behind the registration form's username look-ahead.
 *
 * <p>Separate from {@link UserServiceIT} on purpose: generating candidates touches no database, and
 * the properties worth pinning here — every candidate registrable, nothing longer than the column
 * allows, no duplicates — are the ones a database test would obscure rather than prove.
 */
class UsernameSuggestionsUnitTest {

    @Test
    void everySuggestionIsItselfARegistrableLogin() {
        // If this ever fails, the look-ahead is offering names the registration validator will reject —
        // the user clicks a suggestion and the form goes red on a value we handed them.
        for (String login : List.of("kojo", "a", "patient.one", "user-name", "a.b@example.com", "x_y")) {
            assertThat(UserService.suggestionsFor(login))
                .as("suggestions for '%s'", login)
                .isNotEmpty()
                .allSatisfy(suggestion -> {
                    assertThat(suggestion).matches(Constants.LOGIN_REGEX);
                    assertThat(suggestion).hasSizeLessThanOrEqualTo(50);
                });
        }
    }

    @Test
    void suggestionsAreDistinctAndExcludeTheTakenLogin() {
        List<String> suggestions = UserService.suggestionsFor("kojo");

        assertThat(suggestions).doesNotContain("kojo").doesNotHaveDuplicates();
    }

    @Test
    void aMaximumLengthLoginIsTruncatedToMakeRoomForTheSuffix() {
        // 50 characters: appending anything would exceed @Size(max = 50) and produce a suggestion that
        // cannot be registered. The base has to give way instead.
        String maxLength = "a".repeat(50);

        List<String> suggestions = UserService.suggestionsFor(maxLength);

        assertThat(suggestions).isNotEmpty().allSatisfy(suggestion -> assertThat(suggestion).hasSize(50));
    }

    @Test
    void generationIsDeterministic() {
        // Two people typing the same taken name are told the same alternatives. Nothing here reserves a
        // name, so this does not remove the race — it keeps it visible instead of scattering it.
        assertThat(UserService.suggestionsFor("kojo")).isEqualTo(UserService.suggestionsFor("kojo"));
    }

    @Test
    void theObviousChoiceIsOfferedFirst() {
        assertThat(UserService.suggestionsFor("kojo")).startsWith("kojo1", "kojo2", "kojo3");
    }
}
