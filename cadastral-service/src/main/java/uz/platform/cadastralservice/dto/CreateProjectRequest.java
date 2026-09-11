package uz.platform.cadastralservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body of POST /api/projects.
 *
 * <p>Note what is no longer here: the organization TIN. Until phase 6 the client
 * stated which organization it was acting for, in the body, and the server
 * believed it. That is the single largest hole an application like this can
 * have — any authenticated user could file documents on behalf of any company.
 *
 * <p>The acting organization now arrives in the X-Organization-TIN header and is
 * verified against the caller's memberships before the controller runs. Removing
 * the field from the body is part of the fix: if it cannot be stated, it cannot
 * be believed.
 */
public record CreateProjectRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 255) String address) {
}
