import Keycloak from 'keycloak-js'
import { LOGIN_CHANNEL } from './login-callback.js'

/**
 * Signing in, keeping the session alive, and signing out.
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
const config = {
  url: import.meta.env.VITE_KEYCLOAK_URL ?? 'http://localhost:8190',
  realm: import.meta.env.VITE_KEYCLOAK_REALM ?? 'platform',
  clientId: import.meta.env.VITE_KEYCLOAK_CLIENT_ID ?? 'platform-web'
}
const realmUrl = `${config.url}/realms/${config.realm}`

/** The popup's last stop, registered under platform-web's redirect URIs. */
const callbackUrl = () => `${window.location.origin}/auth-callback.html`

/** How often to notice that the person closed the sign-in window. */
const POPUP_POLL_MS = 500
/** How long a closed window may still deliver its answer, which is posted just before it closes. */
const POPUP_CLOSED_GRACE_MS = 1000

export class LoginError extends Error {
  constructor(code, message) {
    super(message)
    this.name = 'LoginError'
    this.code = code
  }
}

/** The keycloak-js instance of the signed-in session, or null when signed out. */
let current = null
/** The sign-in in progress, so a second click brings its window forward instead of opening another. */
let pending = null

const sessionEndedListeners = new Set()

/**
 * Runs whenever this tab stops being signed in: Logout here, Logout in another
 * tab, or a token refresh Keycloak refused because the session is gone.
 */
export function onSessionEnded(listener) {
  sessionEndedListeners.add(listener)
}

function sessionEnded() {
  current = null
  sessionEndedListeners.forEach((listener) => listener())
}

/**
 * Tells the other tabs of this app that the person signed out.
 *
 * Tokens live in memory, per tab. Without this, a second tab would carry on
 * showing a signed-in page until its next token refresh was refused.
 */
const authChannel = typeof BroadcastChannel === 'undefined' ? null : new BroadcastChannel('platform-auth')

authChannel?.addEventListener('message', (event) => {
  if (event.data === 'logout') {
    current?.clearToken()
  }
})

/**
 * Signs in through a separate window.
 *
 *   1. A popup opens on Keycloak's authorization endpoint. With the default
 *      idpHint "oneid", Keycloak skips its own sign-in page and sends the popup
 *      straight to OneID.
 *   2. The person signs in at OneID, inside the popup.
 *   3. OneID returns the popup to Keycloak. Keycloak, on its server, exchanges
 *      OneID's code for OneID's token, fetches the person's data with it, creates
 *      or updates the Keycloak user, and then sends the popup to
 *      /auth-callback.html with a Keycloak code.
 *   4. The callback page hands that code to this window and closes. This window
 *      redeems it for Keycloak's tokens, with the PKCE verifier only it holds.
 *
 * The OneID token never reaches the browser: steps 3's exchange and lookup
 * happen between Keycloak and OneID only.
 *
 * Pass idpHint: null for Keycloak's own sign-in page, where the development users
 * sign in with a password.
 *
 * NOT async on purpose: the popup must open in the same tick as the click. After
 * any await, browsers treat window.open as unsolicited and block it.
 */
export function login({ idpHint = 'oneid' } = {}) {
  if (pending) {
    pending.popup.focus?.()
    return pending.promise
  }

  const popup = window.open('', 'platform-login', popupFeatures())
  if (!popup) {
    return Promise.reject(new LoginError('popup_blocked',
      'The browser blocked the sign-in window. Allow pop-ups for this site and try again.'))
  }

  const promise = signInThroughPopup(popup, idpHint).finally(() => {
    pending = null
    if (!popup.closed) {
      popup.close()
    }
  })
  pending = { popup, promise }
  return promise
}

async function signInThroughPopup(popup, idpHint) {
  // state ties the answer to this attempt (the CSRF defence for the redirect),
  // nonce ties the ID token to it, and the PKCE verifier makes the code useless to
  // anyone who sees it. All three stay in this function's memory.
  const state = randomString()
  const nonce = randomString()
  const verifier = randomString()
  const challenge = await sha256Base64Url(verifier)

  // Listen before navigating, so no answer can arrive unheard.
  const answer = waitForCallback(popup, state)
  popup.location.href = authorizationUrl({ state, nonce, challenge, idpHint })
  const { code, iss } = await answer

  // RFC 9207: an answer claiming to come from another issuer is not ours to redeem.
  if (iss && iss !== realmUrl) {
    throw new LoginError('issuer_mismatch', 'The sign-in answer came from an unexpected server.')
  }

  const tokens = await exchangeCode(code, verifier)
  if (claims(tokens.id_token).nonce !== nonce) {
    throw new LoginError('nonce_mismatch', 'The sign-in answer does not belong to this sign-in attempt.')
  }

  // A fresh keycloak-js instance per session: an instance can be initialised only
  // once, and it takes over from here — token refresh, logout URL, clearing tokens.
  const keycloak = new Keycloak(config)
  await keycloak.init({
    token: tokens.access_token,
    refreshToken: tokens.refresh_token,
    idToken: tokens.id_token,
    pkceMethod: 'S256',
    // The hidden-iframe session check needs third-party cookies and only adds
    // confusion in a learning project. Off on purpose.
    checkLoginIframe: false
  })
  // keycloak-js fires this from clearToken(), which every way out goes through.
  keycloak.onAuthLogout = sessionEnded
  current = keycloak
}

/** Resolves with the callback page's answer for this attempt, or rejects when the window is closed first. */
function waitForCallback(popup, state) {
  return new Promise((resolve, reject) => {
    const channel = new BroadcastChannel(LOGIN_CHANNEL)
    let done = false
    let closedSeen = false

    const finish = (settle, value) => {
      if (done) return
      done = true
      clearInterval(watch)
      channel.close()
      settle(value)
    }

    channel.addEventListener('message', (event) => {
      const message = event.data
      // Another tab's sign-in, or a stale one: not this attempt, keep waiting.
      if (!message || message.state !== state) return
      if (message.error) {
        finish(reject, new LoginError(message.error, message.errorDescription || 'Signing in was not completed.'))
      } else if (!message.code) {
        finish(reject, new LoginError('no_code', 'The sign-in window returned without an authorization code.'))
      } else {
        finish(resolve, message)
      }
    })

    const watch = setInterval(() => {
      if (popup.closed && !closedSeen) {
        closedSeen = true
        setTimeout(() => finish(reject, new LoginError('popup_closed',
          'The sign-in window was closed before signing in finished.')), POPUP_CLOSED_GRACE_MS)
      }
    }, POPUP_POLL_MS)
  })
}

function authorizationUrl({ state, nonce, challenge, idpHint }) {
  const params = new URLSearchParams({
    client_id: config.clientId,
    redirect_uri: callbackUrl(),
    response_type: 'code',
    response_mode: 'query',
    scope: 'openid',
    state,
    nonce,
    code_challenge: challenge,
    code_challenge_method: 'S256'
  })
  if (idpHint) {
    params.set('kc_idp_hint', idpHint)
  }
  return `${realmUrl}/protocol/openid-connect/auth?${params}`
}

async function exchangeCode(code, verifier) {
  let response
  try {
    response = await fetch(`${realmUrl}/protocol/openid-connect/token`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        grant_type: 'authorization_code',
        client_id: config.clientId,
        code,
        redirect_uri: callbackUrl(),
        code_verifier: verifier
      })
    })
  } catch {
    throw new LoginError('network', 'Keycloak could not be reached to finish signing in.')
  }
  const body = await response.json().catch(() => ({}))
  if (!response.ok || !body.access_token) {
    throw new LoginError(body.error || 'token_exchange_failed', body.error_description || 'Keycloak refused to finish signing in.')
  }
  return body
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
  const keycloak = current
  if (!keycloak) {
    return
  }
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
  const keycloak = current
  if (!keycloak?.authenticated) {
    return null
  }
  try {
    await keycloak.updateToken(30)
  } catch (error) {
    // Two different failures land here.
    //
    // Keycloak answered 400: the session is gone — logged out in another tab,
    // ended by an administrator, or idle past the realm's timeout. keycloak-js
    // has already cleared its tokens, which fired onSessionEnded. Deliberately no
    // automatic sign-in here: it would open a window in the middle of a click,
    // when what the person needs to see is that they were signed out.
    //
    // Keycloak unreachable: the tokens are kept, and the current one is sent as
    // it is. The backend then decides, which is the honest answer either way.
    console.warn('Token refresh failed', error)
  }
  return keycloak.authenticated ? keycloak.token : null
}

export function isAuthenticated() {
  return current?.authenticated === true
}

/** Realm roles as Keycloak decoded them from the access token. */
export function realmRoles() {
  return current?.realmAccess?.roles ?? []
}

export function username() {
  return current?.tokenParsed?.preferred_username ?? '-'
}

// ---- small helpers ------------------------------------------------------------

function popupFeatures() {
  const width = 520
  const height = 720
  const left = Math.max(0, Math.round((window.screenX || 0) + ((window.outerWidth || width) - width) / 2))
  const top = Math.max(0, Math.round((window.screenY || 0) + ((window.outerHeight || height) - height) / 2))
  return `popup=yes,width=${width},height=${height},left=${left},top=${top}`
}

function randomString() {
  return base64Url(crypto.getRandomValues(new Uint8Array(32)))
}

async function sha256Base64Url(value) {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value))
  return base64Url(new Uint8Array(digest))
}

function base64Url(bytes) {
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

/** A token's claims, read only to compare the nonce. Its signature is Keycloak's to vouch for, over TLS. */
function claims(jwt) {
  const base64 = jwt.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')
  const bytes = Uint8Array.from(atob(base64), (character) => character.charCodeAt(0))
  return JSON.parse(new TextDecoder().decode(bytes))
}
