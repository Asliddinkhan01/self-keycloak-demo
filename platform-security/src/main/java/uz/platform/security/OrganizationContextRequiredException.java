package uz.platform.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when an endpoint needs an acting organization and the caller sent none.
 *
 * <p>400 rather than 403: the caller may well be entitled to act for an
 * organization, they simply did not say which one. Telling them that is more
 * useful than a blanket refusal, and it keeps 403 meaning "you asked for
 * something you are not allowed to have".</p>
 */
@ResponseStatus(value = HttpStatus.BAD_REQUEST,
        reason = "X-Organization-TIN header is required for this endpoint")
public class OrganizationContextRequiredException extends RuntimeException {
}
