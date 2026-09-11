package uz.platform.cadastralservice.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import uz.platform.security.PermissionSecurity;
import uz.platform.security.ResourceServerSecurity;

/**
 * Security for cadastral-service.
 *
 * <p>This service is a confidential Keycloak client with a service account,
 * because it makes outbound calls to organization-service to validate that a
 * caller may act for a given TIN. Its service account holds ORG_READ and
 * nothing else — least privilege made concrete, and the subject of phase 7.</p>
 */
@Configuration
@Import({ ResourceServerSecurity.class, PermissionSecurity.class })
public class SecurityConfig {
}
