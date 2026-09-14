package uz.platform.mockoneid;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;

import uz.platform.mockoneid.Fixtures.FixtureUser;
import uz.platform.mockoneid.Fixtures.LegalEntity;

/**
 * The OneID wire protocol, as the technical instruction documents it.
 *
 * <p>One URL for everything. The operation is chosen by a parameter, not by the
 * path, which is the first thing that makes OneID awkward for a standard OAuth2
 * client:</p>
 *
 * <pre>
 * GET  /sso/oauth/Authorization.do  response_type=one_code             -> choose an identity
 * POST /sso/oauth/Authorization.do  grant_type=one_authorization_code  -> opaque access token
 * POST /sso/oauth/Authorization.do  grant_type=one_access_token_identify -> user JSON
 * POST /sso/oauth/Authorization.do  grant_type=one_log_out             -> ret_cd 0
 * </pre>
 *
 * <p>Where the specification is silent — most notably the shape of an error — the
 * mock makes a plain, documented choice rather than inventing detail, and those
 * choices are listed in docs/architecture.md as open questions for the operator.</p>
 *
 * <p>Nothing here logs a PIN, a token, a code or the client secret.</p>
 */
@RestController
public class OneIdController {

    static final String PATH = "/sso/oauth/Authorization.do";
    static final String SELECT_PATH = "/sso/oauth/select";

    private static final Logger log = LoggerFactory.getLogger(OneIdController.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Authorization codes are single use and short lived. */
    private static final Duration CODE_TTL = Duration.ofSeconds(60);

    /** The value in the specification's sample token response, in seconds (about 5.8 days). */
    private static final long TOKEN_EXPIRES_IN = 500_000L;

    private final MockOneIdProperties client;
    private final Map<String, IssuedCode> codes = new ConcurrentHashMap<>();
    private final Map<String, IssuedToken> tokens = new ConcurrentHashMap<>();

    private record IssuedCode(String fixture, String redirectUri, Instant expiresAt) {
    }

    private record IssuedToken(String fixture, String sessionId) {
    }

    public OneIdController(MockOneIdProperties client) {
        this.client = client;
    }

    // ------------------------------------------------------------------------
    // Front channel: the browser arrives here, redirected by Keycloak.
    // ------------------------------------------------------------------------

    /**
     * {@code response_type=one_code}. Stands in for the OneID sign-in screen.
     *
     * <p>A real person would authenticate here with a password, Mobile-ID, an
     * e-signature or a QR code. The mock replaces that with a list of invented
     * identities, because the platform never sees how OneID authenticates anyone
     * — it only sees the result.</p>
     */
    @GetMapping(value = PATH, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> authorize(@RequestParam Map<String, String> params) {
        String problem = firstProblem(params);
        if (problem != null) {
            log.warn("Authorization request refused: {}", problem);
            return ResponseEntity.badRequest().body(page("Invalid authorization request",
                    "<p class=\"err\">" + HtmlUtils.htmlEscape(problem) + "</p>"));
        }

        StringBuilder rows = new StringBuilder();
        for (FixtureUser user : Fixtures.all().values()) {
            rows.append("""
                    <form method="post" action="%s">
                      <input type="hidden" name="fixture" value="%s">
                      <input type="hidden" name="redirect_uri" value="%s">
                      <input type="hidden" name="state" value="%s">
                      <button type="submit"><b>%s</b><span>%s</span></button>
                    </form>
                    """.formatted(
                    SELECT_PATH,
                    HtmlUtils.htmlEscape(user.key()),
                    HtmlUtils.htmlEscape(params.get("redirect_uri")),
                    HtmlUtils.htmlEscape(params.get("state")),
                    HtmlUtils.htmlEscape(user.key()),
                    HtmlUtils.htmlEscape(user.description())));
        }

        return ResponseEntity.ok(page("Mock OneID &middot; Yagona identifikatsiya tizimi",
                "<p>Local development stand-in for <code>sso.egov.uz</code>. Choose an invented "
                        + "identity; no password is involved.</p>" + rows));
    }

    /**
     * The picker's submit. Issues a single-use code and sends the browser back.
     *
     * <p>{@code redirect_uri} is checked again here even though it came from a
     * hidden field the mock itself rendered. A hidden field is still client input,
     * and an open redirect that hands out authorization codes is the classic way
     * those codes get stolen.</p>
     */
    @PostMapping(SELECT_PATH)
    public ResponseEntity<Void> select(@RequestParam String fixture,
                                       @RequestParam("redirect_uri") String redirectUri,
                                       @RequestParam String state) {
        if (Fixtures.find(fixture) == null || !client.allowedRedirectUris().contains(redirectUri)) {
            return ResponseEntity.badRequest().build();
        }

        String code = opaque();
        codes.put(code, new IssuedCode(fixture, redirectUri, Instant.now().plus(CODE_TTL)));
        log.info("Issued authorization code for fixture={}", fixture);

        // Strict encoding of the values: a base64 code contains '+', '/' and '=',
        // and an unencoded '+' is read back as a space, which silently corrupts
        // the code. The specification's own sample is percent-encoded for this
        // reason: code=oofys6rkl9P8%2FNZREMVSmA%3D%3D
        URI location = UriComponentsBuilder.fromUriString(redirectUri)
                .queryParam("code", "{code}")
                .queryParam("state", "{state}")
                .encode()
                .buildAndExpand(code, state)
                .toUri();

        return ResponseEntity.status(HttpStatus.FOUND).location(location).build();
    }

    // ------------------------------------------------------------------------
    // Back channel: Keycloak calls these directly, never the browser.
    // ------------------------------------------------------------------------

    /** Every back-channel operation is a form POST to the same URL, chosen by grant_type. */
    @PostMapping(value = PATH, consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> operation(@RequestParam MultiValueMap<String, String> form) {
        String grantType = form.getFirst("grant_type");

        if (!client.clientId().equals(form.getFirst("client_id"))
                || !client.clientSecret().equals(form.getFirst("client_secret"))) {
            log.warn("Refused {}: client authentication failed", grantType);
            return failure(HttpStatus.UNAUTHORIZED, "invalid_client");
        }

        return switch (grantType == null ? "" : grantType) {
            case "one_authorization_code" -> exchangeCode(form);
            case "one_access_token_identify" -> identify(form);
            case "one_log_out" -> logOut(form);
            default -> {
                log.warn("Refused unknown grant_type={}", grantType);
                yield failure(HttpStatus.BAD_REQUEST, "unsupported_grant_type");
            }
        };
    }

    /**
     * {@code grant_type=one_authorization_code}.
     *
     * <p>The code is removed before it is checked, so a second attempt with the
     * same code fails whether or not the first one succeeded. That is what
     * single-use means, and it is why the provider never retries this call.</p>
     */
    private ResponseEntity<Map<String, Object>> exchangeCode(MultiValueMap<String, String> form) {
        IssuedCode issued = codes.remove(String.valueOf(form.getFirst("code")));
        if (issued == null) {
            log.warn("Refused code exchange: unknown or already used code");
            return failure(HttpStatus.BAD_REQUEST, "invalid_grant");
        }
        if (Instant.now().isAfter(issued.expiresAt())) {
            log.warn("Refused code exchange: expired code for fixture={}", issued.fixture());
            return failure(HttpStatus.BAD_REQUEST, "invalid_grant");
        }
        if (!issued.redirectUri().equals(form.getFirst("redirect_uri"))) {
            log.warn("Refused code exchange: redirect_uri mismatch for fixture={}", issued.fixture());
            return failure(HttpStatus.BAD_REQUEST, "invalid_grant");
        }

        String accessToken = opaque();
        tokens.put(accessToken, new IssuedToken(issued.fixture(), UUID.randomUUID().toString()));
        log.info("Issued access token for fixture={}", issued.fixture());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scope", client.scope());
        body.put("expires_in", TOKEN_EXPIRES_IN);
        body.put("token_type", "bearer");
        // Returned because the specification's sample returns one. How it is
        // meant to be redeemed is not documented, so nothing here accepts it.
        // OPEN QUESTION: How exactly does OneID expect its refresh_token to be redeemed?
        body.put("refresh_token", opaque());
        body.put("access_token", accessToken);
        return ResponseEntity.ok(body);
    }

    /**
     * {@code grant_type=one_access_token_identify}.
     *
     * <p>Answered with HTTP 200 and {@code ret_cd} in the body, including for
     * failures, because {@code ret_cd} is the field the specification defines as
     * the result of the operation. Whether the real service also uses HTTP status
     * codes for this is undocumented.</p>
     */
    private ResponseEntity<Map<String, Object>> identify(MultiValueMap<String, String> form) {
        IssuedToken token = tokens.get(String.valueOf(form.getFirst("access_token")));
        if (token == null) {
            log.warn("Identify refused: unknown access token");
            return ResponseEntity.ok(Map.of("ret_cd", "1"));
        }

        FixtureUser user = Fixtures.find(token.fixture());
        if (!"0".equals(user.retCd())) {
            log.info("Identify for fixture={} answered ret_cd={}", user.key(), user.retCd());
            return ResponseEntity.ok(Map.of("ret_cd", user.retCd()));
        }

        log.info("Identify for fixture={} user_type={} valid={} legal_entities={}",
                user.key(), user.userType(), user.valid(), user.legalEntities().size());
        return ResponseEntity.ok(toOneIdResponse(user, token.sessionId()));
    }

    /** {@code grant_type=one_log_out}. Revokes the access token. */
    private ResponseEntity<Map<String, Object>> logOut(MultiValueMap<String, String> form) {
        IssuedToken removed = tokens.remove(String.valueOf(form.getFirst("access_token")));
        log.info("Log out: token {}", removed == null ? "was not active" : "revoked");
        return ResponseEntity.ok(Map.of("ret_cd", "0"));
    }

    /** The identify response, field for field as in the specification. */
    private static Map<String, Object> toOneIdResponse(FixtureUser user, String sessionId) {
        Map<String, Object> body = new LinkedHashMap<>();
        // The specification's sample sends "valid" as the string "true", not a
        // JSON boolean. Reproduced as such, so the provider's parser is tested
        // against the documented form rather than the tidier one.
        body.put("valid", Boolean.toString(user.valid()));
        body.put("validation_method", user.validationMethods());
        if (user.includePin()) {
            body.put("pin", user.pin());
        }
        body.put("user_id", user.userId());
        body.put("full_name", user.fullName());
        body.put("pport_no", user.passportNumber());
        body.put("birth_date", user.birthDate());
        body.put("sur_name", user.surName());
        body.put("first_name", user.firstName());
        body.put("mid_name", user.midName());
        body.put("user_type", user.userType());
        body.put("sess_id", sessionId);
        body.put("ret_cd", user.retCd());
        body.put("auth_method", user.authMethod());
        // Present only for LEPKCSMETHOD. The specification says the line is not
        // provided otherwise, so it is omitted rather than sent as null.
        if (user.pkcsLegalTin() != null) {
            body.put("pkcs_legal_tin", user.pkcsLegalTin());
        }
        body.put("legal_info", user.legalEntities().stream().map(OneIdController::toLegalInfo).toList());
        return body;
    }

    private static Map<String, Object> toLegalInfo(LegalEntity entity) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("is_basic", entity.basic());
        entry.put("tin", entity.tin());
        entry.put("acron_UZ", entity.name());
        entry.put("le_tin", entity.tin());
        entry.put("le_name", entity.name());
        return entry;
    }

    /** Checks an authorization request the way a registered-client server would. */
    private String firstProblem(Map<String, String> params) {
        if (!"one_code".equals(params.get("response_type"))) {
            return "response_type must be one_code";
        }
        if (!client.clientId().equals(params.get("client_id"))) {
            return "unknown client_id";
        }
        if (!client.allowedRedirectUris().contains(params.get("redirect_uri"))) {
            return "redirect_uri is not registered for this client";
        }
        if (params.get("state") == null || params.get("state").isBlank()) {
            return "state is required";
        }
        return null;
    }

    /** 16 random bytes, base64: the same shape as the specification's sample tokens. */
    private static String opaque() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** The error shape is undocumented; ret_cd plus an OAuth2-style error code is the mock's choice. */
    private static ResponseEntity<Map<String, Object>> failure(HttpStatus status, String error) {
        return ResponseEntity.status(status).body(Map.of("ret_cd", "1", "error", error));
    }

    private static String page(String title, String content) {
        return """
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8"><title>%s</title>
                <style>
                  body{font-family:system-ui,sans-serif;background:#f4f6f5;color:#16201f;margin:0;padding:2rem 1rem}
                  main{max-width:640px;margin:0 auto;background:#fff;border:1px solid #d8e0dc;border-radius:10px;padding:1.5rem}
                  h1{font-size:1.2rem;margin:0 0 .5rem}
                  p{color:#56655f;font-size:.9rem}
                  form{margin:.5rem 0}
                  button{width:100%%;text-align:left;font:inherit;padding:.7rem .9rem;border:1px solid #c7d1cc;border-radius:8px;background:#fff;cursor:pointer}
                  button:hover{background:#eaf1ee}
                  button b{display:block;font-size:.95rem}
                  button span{display:block;color:#56655f;font-size:.82rem;margin-top:.15rem}
                  .err{color:#9e2f27}
                  code{font-family:ui-monospace,Consolas,monospace}
                </style></head>
                <body><main><h1>%s</h1>%s</main></body></html>
                """.formatted(title, title, content);
    }
}
