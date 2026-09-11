package uz.platform.userservice.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import uz.platform.security.PermissionSecurity;
import uz.platform.security.ResourceServerSecurity;

/**
 * Security for user-service.
 *
 * <p>Everything lives in {@link ResourceServerSecurity}, shared by every service
 * in the platform. The import is explicit rather than an auto-configuration so
 * that a reader can see, from this file, exactly where this service's security
 * comes from.</p>
 *
 * <p>Anything genuinely specific to user-service — an extra public path, a
 * stricter rule on one endpoint — would be added here as a second filter chain
 * with a higher precedence, never by editing the shared baseline.</p>
 */
@Configuration
@Import({ ResourceServerSecurity.class, PermissionSecurity.class })
public class SecurityConfig {
}
