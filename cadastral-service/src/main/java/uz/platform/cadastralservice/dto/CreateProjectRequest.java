package uz.platform.cadastralservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request body of POST /api/projects. */
public record CreateProjectRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 255) String address,
        /**
         * Phase 5 takes the organization from the body. Phase 6 replaces this
         * with the X-Organization-TIN header, validated against the caller's
         * memberships — because a client must never be able to simply state
         * which organization it is acting for.
         */
        @NotBlank @Size(min = 9, max = 9) String organizationTin) {
}
