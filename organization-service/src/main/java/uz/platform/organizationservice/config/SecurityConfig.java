package uz.platform.organizationservice.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import uz.platform.security.ResourceServerSecurity;

/**
 * Security for organization-service.
 *
 * <p>This service is registered in Keycloak as a client with every flow
 * disabled. That is not a contradiction: it never asks Keycloak for a token, so
 * it needs no credentials and no service account. The registration exists for
 * two other reasons — a client role has to belong to a client, and a token
 * audience has to name one. Both matter here, because this is the service other
 * services call.</p>
 */
@Configuration
@Import(ResourceServerSecurity.class)
public class SecurityConfig {
}
