import Keycloak from 'keycloak-js'

/**
 * The one and only Keycloak object of this app.
 *
 * These three values must match the realm import exactly:
 *   url      -> where Keycloak is running
 *   realm    -> "demo"
 *   clientId -> "demo-frontend", a PUBLIC client
 *
 * A browser app cannot keep a client secret (anyone can read the JavaScript),
 * so demo-frontend is a public client. Its security comes from two things:
 * the registered redirect URIs, and PKCE.
 */
export const keycloak = new Keycloak({
  url: 'http://localhost:8180',
  realm: 'demo',
  clientId: 'demo-frontend'
})

/**
 * Must be awaited once, before the Vue app is mounted.
 *
 * When the page loads normally this simply reports "not authenticated".
 * When the page loads as the redirect back from the Keycloak login page, the
 * URL carries an authorization code; init() exchanges that code for tokens
 * (using the PKCE verifier it stored earlier) and returns true.
 */
export async function initKeycloak() {
  const authenticated = await keycloak.init({
    // PKCE: the app generates a random secret, sends only its SHA-256 hash to
    // Keycloak with the login request, and reveals the original when exchanging
    // the code for a token. A stolen authorization code is then useless.
    pkceMethod: 'S256',
    // The hidden-iframe session check needs third-party cookies and only adds
    // confusion in a learning project. Turned off on purpose.
    checkLoginIframe: false
  })

  // Tidy the leftover OAuth parameters out of the address bar.
  window.history.replaceState({}, document.title, window.location.pathname)

  return authenticated
}

/** Sends the browser to the Keycloak login page. We never build our own form. */
export function login() {
  return keycloak.login({ redirectUri: window.location.origin + '/' })
}

/**
 * Sends the browser to Keycloak's self-registration page.
 *
 * This is the SAME authorization-code request as login(), with one extra
 * parameter that makes Keycloak open registration.ftl instead of login.ftl.
 * It only works because the realm has "registrationAllowed": true; without
 * that, Keycloak refuses with "Registration not allowed".
 *
 * A user who signs up here gets the realm's default role composite,
 * default-roles-demo, which this project configured to include USER. So a
 * brand new account can immediately read products and its own profile, but
 * not create or delete anything: ADMIN is only ever granted by an operator.
 */
export function register() {
  return keycloak.register({ redirectUri: window.location.origin + '/' })
}

/** Ends the Keycloak SSO session, not just the local token. */
export function logout() {
  return keycloak.logout({ redirectUri: window.location.origin + '/' })
}

/**
 * Returns a currently valid access token, refreshing it first if it expires
 * within the next 30 seconds.
 *
 * Access tokens in this realm live 5 minutes. Without this call the app would
 * start getting 401s after five minutes of use.
 */
export async function getValidToken() {
  if (!keycloak.authenticated) {
    return null
  }
  try {
    await keycloak.updateToken(30)
  } catch (error) {
    // The refresh token is gone or expired: the session is over.
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

export function hasRole(role) {
  return realmRoles().includes(role)
}
