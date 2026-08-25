package net.jojoaddison.web.rest.vm;

import jakarta.validation.constraints.Size;
import net.jojoaddison.service.dto.AdminUserDTO;

/**
 * View Model extending the AdminUserDTO, which is meant to be used in the user management UI.
 */
public class ManagedUserVM extends AdminUserDTO {

    public static final int PASSWORD_MIN_LENGTH = 4;

    public static final int PASSWORD_MAX_LENGTH = 100;

    @Size(min = PASSWORD_MIN_LENGTH, max = PASSWORD_MAX_LENGTH)
    private String password;

    /**
     * Which surface sent this family here, from the handoff link's {@code ?src=}.
     *
     * <p>On this view model rather than on {@code AdminUserDTO} deliberately. {@code AdminUserDTO} is the shape the
     * user-management API accepts for edits, and putting it there would make a record of where somebody came from
     * into something an administrator could rewrite later. It is an input to registration and nothing else.</p>
     *
     * <p>Capped in length because it is unauthenticated input; the value is discarded anyway unless it matches the
     * allowlist, and a size limit is what stops somebody posting a megabyte to find out.</p>
     */
    @Size(max = 50)
    private String source;

    public ManagedUserVM() {
        // Empty constructor needed for Jackson.
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    // prettier-ignore
    @Override
    public String toString() {
        return "ManagedUserVM{" + super.toString() + "} ";
    }
}
