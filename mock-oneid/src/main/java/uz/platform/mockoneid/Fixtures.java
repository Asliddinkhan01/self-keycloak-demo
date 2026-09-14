package uz.platform.mockoneid;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The identities the mock can hand out.
 *
 * <p>Every PIN, passport number and person here is invented. The shapes are not:
 * each fixture reproduces a case the OneID specification describes, so the
 * provider's real code paths — including its failure paths — can be exercised
 * locally rather than assumed.</p>
 *
 * <p>The {@code user_id} values deliberately differ from the password-based
 * development users imported with the realm ({@code ali}, {@code malika} and so
 * on). If a OneID login arrived with a username Keycloak already has, the
 * first-login flow would stop to ask whether to link the two accounts, which is
 * a different lesson from the one this mock is for.</p>
 */
public final class Fixtures {

    public record LegalEntity(String tin, String name, boolean basic) {
    }

    /**
     * One fake OneID account.
     *
     * @param retCd       {@code "0"} success, {@code "1"} failure, as in the specification
     * @param includePin  false to reproduce a response the provider must refuse
     * @param valid       OneID's "Tasdiqlangan foydalanuvchi" status, not token validity
     * @param pkcsLegalTin only set for {@code LEPKCSMETHOD}, and otherwise omitted entirely,
     *                    exactly as the specification describes
     */
    public record FixtureUser(
            String key,
            String description,
            String retCd,
            boolean includePin,
            String pin,
            String userId,
            String firstName,
            String surName,
            String midName,
            String birthDate,
            String passportNumber,
            String userType,
            boolean valid,
            List<String> validationMethods,
            String authMethod,
            String pkcsLegalTin,
            List<LegalEntity> legalEntities) {

        /** OneID's full_name: surname, given name, patronymic. */
        public String fullName() {
            return String.join(" ", surName, firstName, midName).trim();
        }
    }

    private static final Map<String, FixtureUser> ALL = new LinkedHashMap<>();

    static {
        add(new FixtureUser(
                "akarimov",
                "Physical person with two legal entities attached. Organization switching.",
                "0", true,
                "30101851234567", "akarimov",
                "Ali", "Karimov", "Valiyevich", "1985-01-30", "AA1234567",
                "I", true, List.of("PKCSMETHOD"), "PKCSMETHOD", null,
                List.of(
                        new LegalEntity("111111111", "\"IT-GROUP\" MChJ", true),
                        new LegalEntity("222222222", "\"QURILISH SAVDO\" MChJ", false))));

        add(new FixtureUser(
                "myusupova",
                "Physical person, confirmed by Mobile-ID, no legal entities.",
                "0", true,
                "41204921234568", "myusupova",
                "Malika", "Yusupova", "Shavkatovna", "1992-04-12", "AB7654321",
                "I", true, List.of("MOBILEMETHOD"), "MOBILEMETHOD", null,
                List.of()));

        add(new FixtureUser(
                "bbankov",
                "Signed in with a legal entity's e-signature. The organization is asserted, not chosen.",
                "0", true,
                "31507781234569", "bbankov",
                "Bek", "Bankov", "Rustamovich", "1978-07-15", "AC1122334",
                "L", true, List.of("PKCSMETHOD"), "LEPKCSMETHOD", "333333333",
                List.of(new LegalEntity("333333333", "\"MILLIY BANK\" AJ", true))));

        add(new FixtureUser(
                "ntasdiqlanmagan",
                "Password login, never confirmed with ERI or Mobile-ID. valid=false still logs in.",
                "0", true,
                "30909991234570", "ntasdiqlanmagan",
                "Nodir", "Tasdiqlanmagan", "Olimovich", "1999-09-09", "AD5566778",
                "I", false, List.of(), "LOGINPASSMETHOD", null,
                List.of()));

        add(new FixtureUser(
                "broken",
                "OneID answers ret_cd=1. The login must fail with a generic message.",
                "1", true,
                null, "broken", null, null, null, null, null,
                null, false, List.of(), null, null,
                List.of()));

        add(new FixtureUser(
                "nopin",
                "ret_cd=0 but no pin. Nothing stable to link on, so the login must fail.",
                "0", false,
                null, "nopin",
                "Pinsiz", "Foydalanuvchi", "", "2000-01-01", "AE0000000",
                "I", true, List.of("PKCSMETHOD"), "PKCSMETHOD", null,
                List.of()));
    }

    private Fixtures() {
    }

    private static void add(FixtureUser user) {
        ALL.put(user.key(), user);
    }

    public static Map<String, FixtureUser> all() {
        return ALL;
    }

    public static FixtureUser find(String key) {
        return ALL.get(key);
    }
}
