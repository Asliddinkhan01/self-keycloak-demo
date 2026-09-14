import { describe, it, expect, vi } from 'vitest'
import { forwardAuthorizationResponse, LOGIN_CHANNEL } from './login-callback.js'

/** A stand-in for the popup window as Keycloak leaves it on /auth-callback.html. */
function popupAt(search) {
  const posted = []
  class Channel {
    constructor(name) {
      this.name = name
    }
    postMessage(data) {
      posted.push({ channel: this.name, data })
    }
    close() {}
  }
  return {
    posted,
    location: { search, pathname: '/auth-callback.html' },
    history: { replaceState: vi.fn() },
    close: vi.fn(),
    BroadcastChannel: Channel
  }
}

describe('the sign-in popup callback page', () => {
  it('passes code, state and issuer to the app window on the login channel, then closes', () => {
    const popup = popupAt('?state=s-123&session_state=x&iss=http%3A%2F%2Flocalhost%3A8190%2Frealms%2Fplatform&code=c-456')

    forwardAuthorizationResponse(popup)

    expect(popup.posted).toEqual([{
      channel: LOGIN_CHANNEL,
      data: { state: 's-123', code: 'c-456', iss: 'http://localhost:8190/realms/platform', error: null, errorDescription: null }
    }])
    expect(popup.close).toHaveBeenCalledTimes(1)
  })

  it('passes an error from Keycloak as an error, with no code', () => {
    const popup = popupAt('?error=access_denied&error_description=User%20cancelled&state=s-123')

    forwardAuthorizationResponse(popup)

    expect(popup.posted[0].data).toMatchObject({ state: 's-123', code: null, error: 'access_denied', errorDescription: 'User cancelled' })
  })

  it('removes the code from the popup history before closing', () => {
    const popup = popupAt('?state=s-123&code=c-456')

    forwardAuthorizationResponse(popup)

    expect(popup.history.replaceState).toHaveBeenCalledWith(null, '', '/auth-callback.html')
  })

  it('posts nothing when there is no state to match, but still closes', () => {
    const popup = popupAt('?code=c-456')

    forwardAuthorizationResponse(popup)

    expect(popup.posted).toEqual([])
    expect(popup.close).toHaveBeenCalledTimes(1)
  })
})
