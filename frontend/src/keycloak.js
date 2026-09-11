import Keycloak from 'keycloak-js'

/**
 * The one Keycloak object of this app.
 *
 * These three values must match the realm import exactly:
 *   url      -> where Keycloak is running
 *   realm    -> platform
 *   clientId -> platform-web, a PUBLIC client
 *
 * A browser app cannot keep a client secret — anyone can read the JavaScript —
 * so platform-web is public. Its security comes from two other places: the
 * registered redirect URIs, and PKCE.
 *
 * Note what this app never sees: the OneID client secret, the OneID access
 * token, and the user's PIN. Only Keycloak talks to OneID, and only Keycloak
 * holds the link to the national identity. The browser holds a Keycloak token
 * and nothing else.
 */
export const keycloak = new Keycloak({
  url: import.meta.env.VITE_KEYCLOAK_URL ?? 'http://localhost:8190',
  realm: import.meta.env.VITE_KEYCLOAK_REALM ?? 'platform',
  clientId: import.meta.env.VITE_KEYCLOAK_CLIENT_ID ?? 'platform-web'
})

/**
 * Must be awaited once, before Vue mounts.
 *
 * On a normal page load this simply reports "not authenticated". When the page
 * loads as the redirect back from the login screen, the URL carries an
 * authorization code; init() exchanges it for tokens using the PKCE verifier it
 * stored earlier, and returns true.
 */
export async function initKeycloak() {
  const authenticated = await keycloak.init({
    // PKCE: the app generates a random secret, sends only its SHA-256 hash with
    // the login request, and reveals the original when exchanging the code. A
    // stolen authorization code is then useless on its own.
    pkceMethod: 'S256',
    // The hidden-iframe session check needs third-party cookies and only adds
    // confusion in a learning project. Off on purpose.
    checkLoginIframe: false
  })

  // Tidy the leftover OAuth parameters out of the address bar.
  window.history.replaceState({}, document.title, window.location.pathname)

  return authenticated
}

/** Sends the browser to Keycloak. We never render a password field ourselves. */
export function login() {
  return keycloak.login({ redirectUri: window.location.origin + '/' })
}

/**
 * Ends the Keycloak session, not just the local tokens.
 *
 * Clearing tokens in the browser is not logout, it only hides them: the Keycloak
 * session would still be live and the next login would silently walk straight
 * back in. Note also what this does NOT do — it does not end the OneID session
 * at sso.egov.uz, which is a deliberate scope decision recorded in the README.
 */
export function logout() {
  return keycloak.logout({ redirectUri: window.location.origin + '/' })
}

/**
 * A currently valid access token, refreshed if it expires within 30 seconds.
 *
 * Access tokens live 5 minutes in this realm. Without this the app would start
 * collecting 401s after five minutes of use.
 */
export async function getValidToken() {
  if (!keycloak.authenticated) {
    return null
  }
  try {
    await keycloak.updateToken(30)
  } catch (error) {
    console.warn('Token refresh failed, sending the user back to login', error)
    login()
    return null
  }
  return keycloak.token
}

/** Realm roles as Keycloak decoded them from the access token. */
export function realmRoles() {
  return keycloak.realmAccess?.roles ?? []
}

export function username() {
  return keycloak.tokenParsed?.preferred_username ?? '-'
}
