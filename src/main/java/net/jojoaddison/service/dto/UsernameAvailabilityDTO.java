package net.jojoaddison.service.dto;

import java.util.List;

/**
 * The answer to a username look-ahead: whether the login can be registered, and if not, some that
 * can.
 *
 * <p>Deliberately carries nothing about the user who holds a taken login — not the id, not the
 * email, not whether the account is activated. This endpoint is unauthenticated, so everything it
 * returns is public, and "taken" is the whole of what the registration form needs to know.
 *
 * @param available  true when nothing holds this login and registration would accept it.
 * @param suggestions available alternatives, empty when {@code available} is true.
 */
public record UsernameAvailabilityDTO(boolean available, List<String> suggestions) {
    public UsernameAvailabilityDTO {
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
    }
}
