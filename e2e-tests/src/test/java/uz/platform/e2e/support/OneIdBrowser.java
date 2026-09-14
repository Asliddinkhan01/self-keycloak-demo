package uz.platform.e2e.support;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.net.URI;
import java.net.URLDecoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import uz.platform.e2e.support.Http.Response;
import uz.platform.e2e.support.Keycloak.Tokens;

/**
 * One browser window, signing in the way the app's sign-in popup does.
 *
 * <p>It opens the same URL the popup opens, with {@code kc_idp_hint=oneid}, follows
 * every redirect by hand, and keeps Keycloak's cookies, so it holds a real Keycloak
 * SSO session afterwards, the thing logout has to end. It stops where the popup's
 * callback page would take over, and redeems the code as the app window does. The
 * only shortcut is the mock's identity picker, where a person would click a name.</p>
 */
public final class OneIdBrowser {

    private static final Pattern ONEID_BUTTON = Pattern.compile("href=\"([^\"]*broker/oneid/login[^\"]*)\"");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, String> keycloakCookies = new LinkedHashMap<>();
    private final List<String> visited = new ArrayList<>();

    public Tokens signIn(String fixture) {
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes());
        String url = Keycloak.authorizationUrl(challenge(verifier));

        for (int hop = 0; hop < 20; hop++) {
            Response response = get(url);
            if (response.isRedirect()) {
                String next = URI.create(url).resolve(response.location()).toString();
                if (next.startsWith(Platform.LOGIN_CALLBACK)) {
                    visited.add("-> " + Platform.LOGIN_CALLBACK + " with an authorization code");
                    return Keycloak.exchangeCode(queryParameter(next, "code"), verifier);
                }
                url = next;
            } else if (url.startsWith(Platform.REALM + "/protocol/openid-connect/auth")) {
                url = URI.create(Platform.KEYCLOAK).resolve(unescape(find(ONEID_BUTTON, response, "the OneID button"))).toString();
            } else if (url.startsWith(Platform.MOCK_ONEID) && response.status() == 200) {
                url = chooseIdentity(response, fixture);
            } else {
                throw new AssertionError("sign-in stopped on a page instead of redirecting: " + response
                        + "\nvisited:\n  " + String.join("\n  ", visited));
            }
        }
        throw new AssertionError("sign-in did not finish in 20 hops:\n  " + String.join("\n  ", visited));
    }

    /** What keycloak-js does on logout(): send this browser to the end-session URL. */
    public Response endSession(String idToken) {
        return get(Keycloak.endSessionUrl(idToken));
    }

    /** Where pressing Login again in the same browser ends up, following Keycloak's redirects. */
    public record Landing(String url) {

        /** Sent to OneID: the person has to authenticate again. */
        public boolean isOneIdLoginPage() {
            return url.startsWith(Platform.MOCK_ONEID);
        }

        /** Straight back to the app with a code: Keycloak's session let them in without OneID. */
        public boolean isAppWithCode() {
            return url.startsWith(Platform.LOGIN_CALLBACK) && url.contains("code=");
        }
    }

    public Landing openSignIn() {
        String url = Keycloak.authorizationUrl(challenge(Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes())));
        for (int hop = 0; hop < 10; hop++) {
            Response response = get(url);
            if (!response.isRedirect()) {
                return new Landing(url);
            }
            url = URI.create(url).resolve(response.location()).toString();
            if (!url.startsWith(Platform.KEYCLOAK)) {
                return new Landing(url);
            }
        }
        throw new AssertionError("pressing Login again did not leave Keycloak in 10 hops:\n  " + String.join("\n  ", visited));
    }

    public List<String> visited() {
        return List.copyOf(visited);
    }

    private Response get(String url) {
        Http.Request request = Http.get(url);
        boolean toKeycloak = url.startsWith(Platform.KEYCLOAK);
        if (toKeycloak && !keycloakCookies.isEmpty()) {
            request.header("Cookie", String.join("; ",
                    keycloakCookies.entrySet().stream().map(c -> c.getKey() + "=" + c.getValue()).toList()));
        }
        Response response = request.send();
        visited.add(response.status() + " GET " + url);
        if (toKeycloak) {
            remember(response);
        }
        return response;
    }

    private String chooseIdentity(Response picker, String fixture) {
        Response selected = Http.post(Platform.MOCK_ONEID + "/sso/oauth/select").form(Map.of(
                "fixture", fixture,
                "redirect_uri", field(picker, "redirect_uri"),
                "state", field(picker, "state"))).send();
        visited.add(selected.status() + " POST " + Platform.MOCK_ONEID + "/sso/oauth/select (" + fixture + ")");
        if (selected.status() != 302) {
            throw new AssertionError("mock OneID refused the selection: " + selected);
        }
        return selected.location();
    }

    private void remember(Response response) {
        for (String raw : response.headers().allValues("set-cookie")) {
            String pair = raw.split(";", 2)[0];
            int equals = pair.indexOf('=');
            String name = pair.substring(0, equals).trim();
            String value = pair.substring(equals + 1).trim();
            if (value.isEmpty() || raw.toLowerCase(Locale.ROOT).contains("max-age=0")) {
                keycloakCookies.remove(name);
            } else {
                keycloakCookies.put(name, value);
            }
        }
    }

    private static String field(Response page, String name) {
        return unescape(find(Pattern.compile("name=\"" + name + "\" value=\"([^\"]*)\""), page, "form field " + name));
    }

    private static String find(Pattern pattern, Response page, String what) {
        Matcher matcher = pattern.matcher(page.body());
        if (!matcher.find()) {
            throw new AssertionError("no " + what + " on the page: " + page);
        }
        return matcher.group(1);
    }

    private static String unescape(String html) {
        return html.replace("&amp;", "&");
    }

    private static String queryParameter(String url, String name) {
        for (String pair : URI.create(url).getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts[0].equals(name)) {
                return URLDecoder.decode(parts[1], UTF_8);
            }
        }
        throw new AssertionError("no " + name + " in " + url);
    }

    private static byte[] randomBytes() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
