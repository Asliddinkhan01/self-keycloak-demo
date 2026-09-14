package uz.platform.keycloak.oneid;

import java.util.List;
import java.util.Map;

import org.jboss.logging.Logger;
import org.keycloak.broker.provider.AbstractIdentityProviderMapper;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.models.IdentityProviderMapperModel;
import org.keycloak.models.IdentityProviderSyncMode;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.provider.ProviderConfigProperty;

/**
 * Writes the OneID data onto the Keycloak user, and grants the baseline roles.
 *
 * <h2>Why a mapper rather than the provider alone</h2>
 *
 * <p>The provider parses the protocol and puts what it learned onto the brokered
 * identity. That identity is not yet a stored user. Keycloak 26 creates the stored
 * user through its <em>user profile</em>, which by default declares only
 * {@code username}, {@code email}, {@code firstName} and {@code lastName} and
 * silently drops anything else. So {@code org_tins} and
 * {@code identity_verified} would vanish between OneID and the token, with no
 * error anywhere.</p>
 *
 * <p>The obvious fix, switching the realm's unmanaged-attribute policy to
 * {@code ENABLED}, is the wrong one: it would let a person edit their own
 * {@code identity_verified} in the account console. A mapper writes the values
 * onto the stored user directly, from the broker, where no person can reach.</p>
 *
 * <h2>When it runs</h2>
 *
 * <ul>
 *   <li>{@link #importNewUser} on the first OneID login, right after the user is
 *       created.</li>
 *   <li>{@link #updateBrokeredUser} on every later login, because the realm sets
 *       this mapper to {@code FORCE} sync. OneID is authoritative for this data;
 *       the stored copy is a cache and is refreshed each time.</li>
 * </ul>
 *
 * <h2>Roles: granted, never revoked</h2>
 *
 * <p>Every OneID account gets {@code JISMONIY_SHAXS}. An account with legal
 * entities, or one that signed in as a legal entity, also gets
 * {@code YURIDIK_SHAXS}. {@code QURUVCHI}, {@code BANK}, {@code ADMIN} and
 * {@code SUPER_ADMIN} are never granted here: those are business decisions made by
 * an administrator, not facts a national identity system can assert.</p>
 *
 * <p>The asymmetry is deliberate. If OneID stops reporting a legal entity, this
 * mapper does <b>not</b> remove {@code YURIDIK_SHAXS}, because it cannot tell
 * whether an administrator granted that role on purpose. Acting for the vanished
 * organization is still refused, by the membership table, which is where that
 * decision belongs.</p>
 */
public class OneIdAttributeRoleMapper extends AbstractIdentityProviderMapper {

    public static final String PROVIDER_ID = "oneid-attribute-role-mapper";

    private static final Logger log = Logger.getLogger(OneIdAttributeRoleMapper.class);

    private static final String[] COMPATIBLE_PROVIDERS = { OneIdIdentityProviderFactory.PROVIDER_ID };

    static final String ROLE_PHYSICAL_PERSON = "JISMONIY_SHAXS";
    static final String ROLE_LEGAL_ENTITY = "YURIDIK_SHAXS";
    static final String USER_TYPE_LEGAL_ENTITY = "L";

    /**
     * Copied from the brokered identity onto the stored user.
     *
     * <p>{@code oneid_pin} is among them and stays inside Keycloak. No protocol
     * mapper emits it, so it reaches no token and no service.</p>
     */
    static final List<String> COPIED_ATTRIBUTES = List.of(
            OneIdIdentityProvider.ATTR_ONEID_USER_ID,
            OneIdIdentityProvider.ATTR_USER_TYPE,
            OneIdIdentityProvider.ATTR_IDENTITY_VERIFIED,
            OneIdIdentityProvider.ATTR_AUTH_METHOD,
            OneIdIdentityProvider.ATTR_PIN,
            OneIdIdentityProvider.ATTR_ORG_TINS,
            OneIdIdentityProvider.ATTR_PKCS_LEGAL_TIN);

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String[] getCompatibleProviders() {
        return COMPATIBLE_PROVIDERS;
    }

    @Override
    public String getDisplayCategory() {
        return "Attribute Importer";
    }

    @Override
    public String getDisplayType() {
        return "OneID attributes and baseline roles";
    }

    @Override
    public String getHelpText() {
        return "Stores OneID user_id, user_type, identity_verified, auth_method, the PIN and the "
                + "legal entity TINs on the user, and grants JISMONIY_SHAXS, plus YURIDIK_SHAXS when "
                + "legal entities are present. Never grants business roles and never revokes roles.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return List.of();
    }

    /** FORCE matters most: it is what refreshes OneID data on every login. */
    @Override
    public boolean supportsSyncMode(IdentityProviderSyncMode syncMode) {
        return true;
    }

    @Override
    public void importNewUser(KeycloakSession session, RealmModel realm, UserModel user,
                              IdentityProviderMapperModel mapperModel, BrokeredIdentityContext context) {
        apply(realm, user, context);
    }

    @Override
    public void updateBrokeredUser(KeycloakSession session, RealmModel realm, UserModel user,
                                   IdentityProviderMapperModel mapperModel, BrokeredIdentityContext context) {
        apply(realm, user, context);
    }

    @Override
    public void updateBrokeredUserLegacy(KeycloakSession session, RealmModel realm, UserModel user,
                                         IdentityProviderMapperModel mapperModel, BrokeredIdentityContext context) {
        apply(realm, user, context);
    }

    private void apply(RealmModel realm, UserModel user, BrokeredIdentityContext context) {
        Map<String, List<String>> reported = context.getAttributes();

        for (String name : COPIED_ATTRIBUTES) {
            List<String> values = nonBlank(reported.get(name));
            if (values.isEmpty()) {
                // OneID is authoritative. A legal entity it no longer reports, or
                // a pkcs_legal_tin absent because this login used a different
                // method, must not linger on the user from an earlier login.
                user.removeAttribute(name);
            } else {
                user.setAttribute(name, values);
            }
        }

        grant(realm, user, ROLE_PHYSICAL_PERSON);

        boolean actsForLegalEntity =
                USER_TYPE_LEGAL_ENTITY.equals(context.getUserAttribute(OneIdIdentityProvider.ATTR_USER_TYPE))
                        || !nonBlank(reported.get(OneIdIdentityProvider.ATTR_ORG_TINS)).isEmpty();
        if (actsForLegalEntity) {
            grant(realm, user, ROLE_LEGAL_ENTITY);
        }
    }

    private static void grant(RealmModel realm, UserModel user, String roleName) {
        RoleModel role = KeycloakModelUtils.getRoleFromString(realm, roleName);
        if (role == null) {
            // A realm without the role is a configuration error, not a reason to
            // fail the person's login.
            log.warnf("Realm %s has no role %s; not granted", realm.getName(), roleName);
            return;
        }
        if (!user.hasRole(role)) {
            user.grantRole(role);
            log.infof("Granted %s to %s", roleName, user.getUsername());
        }
    }

    private static List<String> nonBlank(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().filter(v -> v != null && !v.isBlank()).toList();
    }
}
