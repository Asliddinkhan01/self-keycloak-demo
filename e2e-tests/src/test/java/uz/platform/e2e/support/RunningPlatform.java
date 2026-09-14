package uz.platform.e2e.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Fails fast, once, with a readable message when part of the platform is not
 * running, instead of 26 connection-refused stack traces.
 */
public final class RunningPlatform implements BeforeAllCallback {

    private static String problem;
    private static boolean checked;

    @Override
    public void beforeAll(ExtensionContext context) {
        synchronized (RunningPlatform.class) {
            if (!checked) {
                problem = check();
                checked = true;
            }
        }
        if (problem != null) {
            throw new IllegalStateException(problem);
        }
    }

    private static String check() {
        Map<String, String> endpoints = new LinkedHashMap<>();
        endpoints.put("Keycloak", Platform.REALM + "/.well-known/openid-configuration");
        endpoints.put("mock-oneid", Platform.MOCK_ONEID + "/sso/oauth/Authorization.do");
        endpoints.put("api-gateway", Platform.GATEWAY + "/actuator/health");
        endpoints.put("user-service", Platform.USER_SERVICE + "/actuator/health");
        endpoints.put("organization-service", Platform.ORGANIZATION_SERVICE + "/actuator/health");
        endpoints.put("cadastral-service", Platform.CADASTRAL_SERVICE + "/actuator/health");

        List<String> unreachable = new ArrayList<>();
        endpoints.forEach((name, url) -> {
            try {
                Http.get(url).send();
            } catch (RuntimeException e) {
                unreachable.add(name + " (" + url + ")");
            }
        });
        return unreachable.isEmpty() ? null
                : "The end-to-end tests need the whole platform running. Not reachable: " + unreachable
                        + ". See docs/testing.md.";
    }
}
