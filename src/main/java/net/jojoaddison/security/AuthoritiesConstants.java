package net.jojoaddison.security;

/**
 * Constants for Spring Security authorities.
 */
public final class AuthoritiesConstants {

    public static final String ADMIN = "ROLE_ADMIN";

    public static final String USER = "ROLE_USER";

    public static final String ANONYMOUS = "ROLE_ANONYMOUS";

    public static final String PATIENT = "ROLE_PATIENT";

    public static final String ANGEL = "ROLE_ANGEL";

    /**
     * Clinical staff, who legitimately read across patients.
     *
     * <p>The patient service already defines this authority and gates its cross-patient reads and its staff-only
     * writes on it ({@code PatientScope.isUnrestricted}); until now nothing could issue it, so it named an access
     * level no token could carry. It exists here so a token can. Registration hardcodes {@code ROLE_USER}
     * ({@code UserService.registerUser}), so this cannot be self-granted — it is assigned deliberately, by an
     * administrator or by the development seed.</p>
     */
    public static final String PROFESSIONAL = "ROLE_PROFESSIONAL";

    private AuthoritiesConstants() {}
}
