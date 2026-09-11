package uz.platform.keycloak.oneid;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The response of {@code grant_type=one_access_token_identify}, parsed.
 *
 * <p>Field names and semantics come straight from the OneID technical
 * instruction. Parsing lives here rather than in the provider so that the
 * protocol quirks are in one readable place.</p>
 *
 * <p>Two fields are commonly misread and are worth stating plainly:</p>
 *
 * <ul>
 *   <li><b>{@code ret_cd}</b> is the result of the <em>operation</em>:
 *       {@code "0"} success, {@code "1"} failure. This is what says whether the
 *       call worked, and it must be checked even on HTTP 200.</li>
 *   <li><b>{@code valid}</b> is <em>not</em> token validity. It reports whether
 *       the account reached "Tasdiqlangan foydalanuvchi" status, meaning the
 *       person proved their identity with an e-signature or Mobile-ID. An
 *       account with {@code valid: false} is a real account whose identity is
 *       simply unconfirmed.</li>
 * </ul>
 */
public record OneIdUserInfo(
        String retCd,
        boolean valid,
        String pin,
        String userId,
        String fullName,
        String firstName,
        String surName,
        String midName,
        String birthDate,
        String passportNumber,
        String userType,
        String sessionId,
        String authMethod,
        String pkcsLegalTin,
        List<LegalEntity> legalEntities) {

    public static final String RET_CD_SUCCESS = "0";

    /** One entry of the {@code legal_info} array. */
    public record LegalEntity(String tin, String name, boolean basic) {
    }

    public boolean successful() {
        return RET_CD_SUCCESS.equals(retCd);
    }

    /**
     * Taxpayer numbers of every legal entity attached to this person.
     *
     * <p>These are what reach the platform, as the {@code org_tins} claim. Names
     * are deliberately left behind: a TIN is a public identifier and is all the
     * services need to seed membership, while the authoritative organization
     * record lives in organization-service.</p>
     */
    public List<String> legalEntityTins() {
        List<String> tins = new ArrayList<>();
        for (LegalEntity entity : legalEntities) {
            if (entity.tin() != null && !entity.tin().isBlank()) {
                tins.add(entity.tin());
            }
        }
        return tins;
    }

    public static OneIdUserInfo from(JsonNode node) {
        return new OneIdUserInfo(
                text(node, "ret_cd"),
                // OneID sends this as the string "true" in the documented sample
                // rather than a JSON boolean, so accept either.
                bool(node, "valid"),
                text(node, "pin"),
                text(node, "user_id"),
                text(node, "full_name"),
                text(node, "first_name"),
                text(node, "sur_name"),
                text(node, "mid_name"),
                text(node, "birth_date"),
                text(node, "pport_no"),
                text(node, "user_type"),
                text(node, "sess_id"),
                text(node, "auth_method"),
                // Present ONLY when auth_method is LEPKCSMETHOD, per the
                // specification: the person signed in with a legal entity's
                // e-signature, so that organization is cryptographically
                // asserted rather than chosen.
                text(node, "pkcs_legal_tin"),
                legalEntities(node));
    }

    private static List<LegalEntity> legalEntities(JsonNode node) {
        List<LegalEntity> entities = new ArrayList<>();
        JsonNode array = node.get("legal_info");
        if (array == null || !array.isArray()) {
            return entities;
        }
        for (JsonNode entry : array) {
            // tin/le_tin and acron_UZ/le_name appear to be duplicates in the
            // specification, whose descriptions for the second pair are blank.
            // Prefer the documented ones and fall back, rather than guessing.
            String tin = firstNonBlank(text(entry, "tin"), text(entry, "le_tin"));
            String name = firstNonBlank(text(entry, "acron_UZ"), text(entry, "le_name"));
            entities.add(new LegalEntity(tin, name, entry.path("is_basic").asBoolean(false)));
        }
        return entities;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static boolean bool(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return false;
        }
        return value.isBoolean() ? value.asBoolean() : Boolean.parseBoolean(value.asText());
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
