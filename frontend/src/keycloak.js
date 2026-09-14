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

/**
 * Tells the other tabs of this app that the person signed out.
 *
 * keycloak-js keeps tokens in memory, per tab. With checkLoginIframe off, nothing
 * else would tell a second tab that the session is gone: it would carry on
 * showing a signed-in page until its next token refresh was refused.
 */
const authChannel = typeof BroadcastChannel === 'undefined' ? null : new BroadcastChannel('platform-auth')

authChannel?.addEventListener('message', (event) => {
  if (event.data === 'logout') {
    keycloak.clearToken()
  }
})

const sessionEndedListeners = new Set()

/**
 * Runs whenever this tab stops being signed in: Logout here, Logout in another
 * tab, or a token refresh Keycloak refused because the session is gone.
 */
export function onSessionEnded(listener) {
  sessionEndedListeners.add(listener)
}

// keycloak-js fires this from clearToken(), which every path above goes through.
keycloak.onAuthLogout = () => sessionEndedListeners.forEach((listener) => listener())

/** Sends the browser to Keycloak. We never render a password field ourselves. */
export function login() {
  return keycloak.login({ redirectUri: window.location.origin + '/' })
}

/**
 * Ends the Keycloak session, not just the local tokens.
 *
 * Clearing tokens in the browser is not logout, it only hides them: the Keycloak
 * session would still be live and the next login would silently walk straight
 * back in. So the browser is sent to Keycloak's end-session endpoint, which
 * deletes the session server-side and revokes its refresh token.
 *
 * The order matters. The URL is built first because it carries the ID token as
 * id_token_hint; with that hint and a registered post_logout_redirect_uri,
 * Keycloak ends the session without asking "Do you want to log out?". Only then
 * are the tokens forgotten, in this tab and the others, so nothing that still
 * looks signed in survives even if the redirect itself fails.
 *
 * Note also what this does NOT do by default — it does not end the OneID session
 * at sso.egov.uz. That is Keycloak's job, behind ONEID_CALL_LOGOUT, never the
 * browser's: the browser has no OneID token to end it with.
 */
export function logout() {
  const logoutUrl = keycloak.createLogoutUrl({ redirectUri: window.location.origin + '/' })
  authChannel?.postMessage('logout')
  keycloak.clearToken()
  // replace, not assign: Back must not return to a page that was signed in.
  window.location.replace(logoutUrl)
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
    // Two different failures land here.
    //
    // Keycloak answered 400: the session is gone — logged out in another tab,
    // ended by an administrator, or idle past the realm's timeout. keycloak-js
    // has already cleared its tokens, which fired onSessionEnded, so
    // `authenticated` is now false. Deliberately no automatic login() here: it
    // would bounce the person to Keycloak in the middle of a click, when what
    // they need to see is that they were signed out.
    //
    // Keycloak unreachable: the tokens are kept, and the current one is sent as
    // it is. The backend then decides, which is the honest answer either way.
    console.warn('Token refresh failed', error)
  }
  return keycloak.authenticated ? keycloak.token : null
}

/** Realm roles as Keycloak decoded them from the access token. */
export function realmRoles() {
  return keycloak.realmAccess?.roles ?? []
}

export function username() {
  return keycloak.tokenParsed?.preferred_username ?? '-'
}
