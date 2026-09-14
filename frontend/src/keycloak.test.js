import { describe, it, expect, vi, beforeEach } from 'vitest'

/*
 * Scenario 25: the frontend clears its authentication state.
 *
 * keycloak.js is the code under test. keycloak-js is replaced by a fake with the
 * same contract for the parts keycloak.js relies on: createLogoutUrl reads the
 * ID token, clearToken forgets the tokens and fires onAuthLogout, and
 * updateToken may refuse. That Keycloak really ends the session is proven
 * against the running server in e2e-tests (LogoutE2ETest).
 */
vi.mock('keycloak-js', () => ({
  default: class FakeKeycloak {
    authenticated = false
    token = undefined
    idToken = undefined
    onAuthLogout = undefined
    updateToken = vi.fn()
    login = vi.fn()

    createLogoutUrl({ redirectUri }) {
      const params = new URLSearchParams({ client_id: 'platform-web', post_logout_redirect_uri: redirectUri })
      if (this.idToken) params.set('id_token_hint', this.idToken)
      return `http://keycloak.test/realms/platform/protocol/openid-connect/logout?${params}`
    }

    clearToken() {
      if (this.token) {
        this.token = undefined
        this.idToken = undefined
        this.authenticated = false
        this.onAuthLogout?.()
      }
    }
  }
}))

/** Tabs of one browser: a message reaches every other channel of the same name, never its sender. */
class TabChannel {
  static open = new Set()
  constructor(name) {
    this.name = name
    this.listeners = []
    TabChannel.open.add(this)
  }
  addEventListener(type, listener) {
    if (type === 'message') this.listeners.push(listener)
  }
  postMessage(data) {
    for (const channel of TabChannel.open) {
      if (channel !== this && channel.name === this.name) {
        queueMicrotask(() => channel.listeners.forEach((listener) => listener({ data })))
      }
    }
  }
  close() {
    TabChannel.open.delete(this)
  }
}

const replace = vi.fn()
vi.stubGlobal('BroadcastChannel', TabChannel)
vi.stubGlobal('window', { location: { origin: 'http://localhost:5174', pathname: '/', replace } })
vi.spyOn(console, 'warn').mockImplementation(() => {})

const { keycloak, logout, onSessionEnded, getValidToken } = await import('./keycloak.js')

const sessionEnded = vi.fn()
onSessionEnded(sessionEnded)

beforeEach(() => {
  Object.assign(keycloak, { authenticated: true, token: 'access-token', idToken: 'id-token' })
  keycloak.updateToken.mockReset().mockResolvedValue(true)
  keycloak.login.mockReset()
  sessionEnded.mockClear()
  replace.mockReset()
})

describe('25. Frontend clears authentication state', () => {
  it('logout sends the browser to Keycloak end-session with the ID token as id_token_hint', () => {
    logout()

    expect(replace).toHaveBeenCalledTimes(1)
    const url = new URL(replace.mock.calls[0][0])
    expect(url.pathname).toBe('/realms/platform/protocol/openid-connect/logout')
    expect(url.searchParams.get('id_token_hint')).toBe('id-token')
    expect(url.searchParams.get('post_logout_redirect_uri')).toBe('http://localhost:5174/')
  })

  it('logout has already forgotten the tokens when the browser leaves', () => {
    replace.mockImplementation(() => {
      expect(keycloak.token).toBeUndefined()
      expect(keycloak.authenticated).toBe(false)
      expect(sessionEnded).toHaveBeenCalledTimes(1)
    })

    logout()

    expect(replace).toHaveBeenCalledTimes(1)
  })

  it('logout tells the other tabs of the app', async () => {
    const otherTab = new BroadcastChannel('platform-auth')
    const received = new Promise((resolve) => otherTab.addEventListener('message', (event) => resolve(event.data)))

    logout()

    await expect(received).resolves.toBe('logout')
    otherTab.close()
  })

  it('a logout in another tab signs this tab out, without a redirect of its own', async () => {
    const otherTab = new BroadcastChannel('platform-auth')

    otherTab.postMessage('logout')

    await vi.waitFor(() => expect(keycloak.authenticated).toBe(false))
    expect(keycloak.token).toBeUndefined()
    expect(sessionEnded).toHaveBeenCalledTimes(1)
    expect(replace).not.toHaveBeenCalled()
    otherTab.close()
  })

  it('a refresh Keycloak refuses leaves the tab signed out, and does not bounce to login', async () => {
    // What keycloak-js does when the token endpoint answers 400: clear, then reject.
    keycloak.updateToken.mockImplementation(async () => {
      keycloak.clearToken()
      throw new Error('400 Session not active')
    })

    await expect(getValidToken()).resolves.toBeNull()
    expect(sessionEnded).toHaveBeenCalledTimes(1)
    expect(keycloak.login).not.toHaveBeenCalled()
  })

  it('an unreachable Keycloak keeps the tokens and lets the backend decide', async () => {
    keycloak.updateToken.mockRejectedValue(new Error('network error'))

    await expect(getValidToken()).resolves.toBe('access-token')
    expect(sessionEnded).not.toHaveBeenCalled()
  })

  it('a signed-out tab sends no token and asks Keycloak for nothing', async () => {
    keycloak.clearToken()

    await expect(getValidToken()).resolves.toBeNull()
    expect(keycloak.updateToken).not.toHaveBeenCalled()
  })
})
