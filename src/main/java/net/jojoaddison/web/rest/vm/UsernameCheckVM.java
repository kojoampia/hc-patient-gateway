package net.jojoaddison.web.rest.vm;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import net.jojoaddison.config.Constants;

/**
 * View Model object for the username availability look-ahead on the registration form.
 *
 * <p>Carried in a POST body rather than a query parameter deliberately. The check runs on every
 * keystroke a user makes in the username field, and a query string is written to the host nginx
 * access log for every request — so a GET would accumulate a log of half-typed candidate usernames
 * belonging to people who never completed registration. A body is not logged.
 *
 * <p>The constraints mirror {@code ManagedUserVM}'s login field exactly. A candidate that could never
 * be registered is rejected here with a 400 rather than reaching the database, which keeps the
 * cheapest possible answer on the path an unauthenticated caller can reach.
 */
public class UsernameCheckVM {

    @NotBlank
    @Pattern(regexp = Constants.LOGIN_REGEX)
    @Size(min = 1, max = 50)
    private String login;

    public String getLogin() {
        return login;
    }

    public void setLogin(String login) {
        this.login = login;
    }

    // prettier-ignore
    @Override
    public String toString() {
        return "UsernameCheckVM{" + "login='" + login + '\'' + '}';
    }
}
