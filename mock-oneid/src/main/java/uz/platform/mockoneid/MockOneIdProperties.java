package uz.platform.mockoneid;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The client registration the mock accepts, standing in for what the OneID
 * operator issues by email for a real integration.
 *
 * @param clientId            issued by the OneID administrator
 * @param clientSecret        issued by the OneID administrator; never logged
 * @param scope               an administrator-issued client name, not a permission list
 * @param allowedRedirectUris exact-match allow-list, as the operator registers them
 */
@ConfigurationProperties(prefix = "mock-oneid")
public record MockOneIdProperties(
        String clientId,
        String clientSecret,
        String scope,
        List<String> allowedRedirectUris) {

    public MockOneIdProperties {
        allowedRedirectUris = allowedRedirectUris == null ? List.of() : List.copyOf(allowedRedirectUris);
    }
}
