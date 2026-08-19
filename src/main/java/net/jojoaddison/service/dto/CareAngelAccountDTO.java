package net.jojoaddison.service.dto;

/**
 * The account a nominated care angel will use.
 *
 * @param login the derived login, which the patient's profile records for display.
 * @param email the nominee's address.
 * @param accountExisted true when this person already had an account and was simply granted the role — the caller
 *                       sends a different mail in that case, because they already have a password.
 * @param resetKey the key for the "set your password" link, and <strong>only</strong> for a newly created account.
 *                 Never returned to the patient who nominated them: it is passed to the mail service and no further.
 */
public record CareAngelAccountDTO(String login, String email, boolean accountExisted, String resetKey) {}
