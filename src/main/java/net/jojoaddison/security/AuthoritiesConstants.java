package net.jojoaddison.security;

import java.util.List;

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
     * The clinical disciplines, spelled exactly as {@code hc-professional}'s gateway spells them.
     *
     * <h2>Why this gateway issues somebody else's vocabulary</h2>
     *
     * <p>These replaced {@code ROLE_PROFESSIONAL} on 2026-08-24. That authority was defined here and checked only by
     * {@code hc-patient}'s api — a blanket "clinical staff" role this subsystem minted for itself and then required
     * of everybody else. Because the three stacks share one JWT signing key, a clinician who signed in to
     * {@code hc-professional} arrived at the patient service holding {@code ROLE_DOCTOR}, matched no check, resolved
     * to no patient, and was served empty lists rather than a refusal. Two halves of one platform had two names for
     * a clinician and only one of them was ever issued by the portal clinicians actually use.</p>
     *
     * <p>So this gateway now issues the same eight names, and the patient service accepts exactly them from either
     * source. <strong>The strings must stay byte-identical across all three repositories</strong> — here,
     * {@code hc-professional/gateway}, and {@code hc-patient/api}. There is no shared artefact to enforce it.</p>
     *
     * <p>Registration hardcodes {@code ROLE_USER} ({@code UserService.registerUser}), so none of these can be
     * self-granted; they are assigned deliberately, by an administrator or by the development seed.</p>
     */
    public static final String DOCTOR = "ROLE_DOCTOR";

    public static final String NURSE = "ROLE_NURSE";

    public static final String CARER = "ROLE_CARER";

    public static final String PARAMEDIC = "ROLE_PARAMEDIC";

    public static final String PHARMACIST = "ROLE_PHARMACIST";

    public static final String THERAPIST = "ROLE_THERAPIST";

    public static final String CHEMIST = "ROLE_CHEMIST";

    public static final String TECHNICIAN = "ROLE_TECHNICIAN";

    /** Every clinical discipline, in the order the seed and the migration walk them. */
    public static final List<String> CLINICAL = List.of(DOCTOR, NURSE, CARER, PARAMEDIC, PHARMACIST, THERAPIST, CHEMIST, TECHNICIAN);

    /** The blanket clinical authority removed on 2026-08-24, named only so the migration can delete it. */
    public static final String REMOVED_PROFESSIONAL = "ROLE_PROFESSIONAL";

    private AuthoritiesConstants() {}
}
