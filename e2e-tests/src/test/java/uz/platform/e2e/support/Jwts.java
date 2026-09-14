package uz.platform.e2e.support;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.function.Consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Reading JWTs, verifying one the way a resource server does, and forging the
 * kinds an attacker can make without Keycloak's private key.
 */
public final class Jwts {

    private static final Base64.Decoder DECODE = Base64.getUrlDecoder();
    private static final Base64.Encoder ENCODE = Base64.getUrlEncoder().withoutPadding();

    private Jwts() {
    }

    public static JsonNode header(String jwt) {
        return part(jwt, 0);
    }

    public static JsonNode claims(String jwt) {
        return part(jwt, 1);
    }

    /** RS256 verification against the realm's published JWKS, with no library in between. */
    public static boolean signedBy(String jwt, JsonNode jwks) {
        String kid = header(jwt).path("kid").asText();
        String[] parts = jwt.split("\\.");
        for (JsonNode key : jwks.path("keys")) {
            if (!kid.equals(key.path("kid").asText()) || !"RSA".equals(key.path("kty").asText())) {
                continue;
            }
            try {
                PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                        new BigInteger(1, DECODE.decode(key.path("n").asText())),
                        new BigInteger(1, DECODE.decode(key.path("e").asText()))));
                Signature verifier = Signature.getInstance("SHA256withRSA");
                verifier.initVerify(publicKey);
                verifier.update((parts[0] + "." + parts[1]).getBytes(US_ASCII));
                return verifier.verify(DECODE.decode(parts[2]));
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }
        return false;
    }

    /** Edited claims under the original header and signature. */
    public static String withClaims(String jwt, Consumer<ObjectNode> edit) {
        String[] parts = jwt.split("\\.");
        ObjectNode claims = (ObjectNode) claims(jwt);
        edit.accept(claims);
        try {
            return parts[0] + "." + ENCODE.encodeToString(Http.JSON.writeValueAsBytes(claims)) + "." + parts[2];
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The same claims with {"alg": "none"} and no signature. */
    public static String unsigned(String jwt) {
        String header = ENCODE.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(UTF_8));
        return header + "." + jwt.split("\\.")[1] + ".";
    }

    private static JsonNode part(String jwt, int index) {
        try {
            return Http.JSON.readTree(DECODE.decode(jwt.split("\\.")[index]));
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("not a JWT", e);
        }
    }
}
