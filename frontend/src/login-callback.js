/**
 * The sign-in popup's last page: hands Keycloak's answer to the app window, then closes.
 *
 * Keycloak redirects the popup here, to /auth-callback.html?code=...&state=...&iss=...,
 * once the person has authenticated at OneID and Keycloak has created or updated their
 * account. This page does nothing with the code itself. Only the app window that
 * started the login can redeem it, because only that window holds the PKCE verifier,
 * and only that window can recognise the state it generated.
 *
 * Why BroadcastChannel rather than window.opener.postMessage: a page on the way, such
 * as OneID's own login page, may send a Cross-Origin-Opener-Policy header. That severs
 * window.opener for the rest of the popup's life, and the login would never complete.
 * A BroadcastChannel reaches every window of this origin regardless, and nothing
 * outside it.
 */
export const LOGIN_CHANNEL = 'platform-login'

export function forwardAuthorizationResponse(win) {
  const params = new URLSearchParams(win.location.search)
  const state = params.get('state')

  if (state) {
    const channel = new win.BroadcastChannel(LOGIN_CHANNEL)
    channel.postMessage({
      state,
      code: params.get('code'),
      iss: params.get('iss'),
      error: params.get('error'),
      errorDescription: params.get('error_description')
    })
    channel.close()
  }

  // The code must not linger in this window's history, even for the moment before it closes.
  win.history.replaceState(null, '', win.location.pathname)
  win.close()
}
