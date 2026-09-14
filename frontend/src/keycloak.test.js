import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { createHash } from 'node:crypto'

/*
 * keycloak.js: signing in through a popup, and scenario 25, the frontend clearing
 * its authentication state.
 *
 * keycloak-js is replaced by a fake with the same contract for the parts
 * keycloak.js relies on: init with tokens, createLogoutUrl reading the ID token,
 * clearToken firing onAuthLogout, and updateToken that may refuse. The browser
 * pieces are fakes too: window.open returns a fake popup, fetch plays Keycloak's
 * token endpoint, and BroadcastChannel is an in-memory bus with browser semantics.
 * That Keycloak really brokers OneID and ends sessions is proven against the
 * running server in e2e-tests.
 */
const kc = vi.hoisted(() => ({ instances: [] }))

vi.mock('keycloak-js', () => ({
  default: class FakeKeycloak {
    authenticated = false
    token = undefined
    refreshToken = undefined
    idToken = undefined
    tokenParsed = undefined
    realmAccess = undefined
    onAuthLogout = undefined
    updateToken = vi.fn(async () => true)

    constructor(config) {
      this.config = config
      kc.instances.push(this)
    }

    async init(options) {
      this.initOptions = options
      this.token = options.token
      this.refreshToken = options.refreshToken
      this.idToken = options.idToken
      this.tokenParsed = { preferred_username: 'akarimov' }
      this.realmAccess = { roles: ['JISMONIY_SHAXS'] }
      this.authenticated = true
      return true
    }

    createLogoutUrl({ redirectUri }) {
      const params = new URLSearchParams({ client_id: 'platform-web', post_logout_redirect_uri: redirectUri })
      if (this.idToken) params.set('id_token_hint', this.idToken)
      return `http://localhost:8190/realms/platform/protocol/openid-connect/logout?${params}`
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

/** Windows of one browser: a message reaches every other channel of the same name, never its sender. */
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

const popups = []
const browserWindow = {
  location: { origin: 'http://localhost:5174', pathname: '/', replace: vi.fn() },
  open: vi.fn(() => {
    const popup = { closed: false, location: { href: '' }, focus: vi.fn() }
    popup.close = vi.fn(() => { popup.closed = true })
    popups.push(popup)
    return popup
  })
}
const fetchMock = vi.fn()
const storageWrites = vi.fn()
const storage = { setItem: storageWrites, getItem: () => null, removeItem: vi.fn() }

vi.stubGlobal('window', browserWindow)
vi.stubGlobal('BroadcastChannel', TabChannel)
vi.stubGlobal('fetch', fetchMock)
vi.stubGlobal('localStorage', storage)
vi.stubGlobal('sessionStorage', storage)
vi.spyOn(console, 'warn').mockImplementation(() => {})

const auth = await import('./keycloak.js')

const sessionEnded = vi.fn()
auth.onSessionEnded(sessionEnded)

const REALM = 'http://localhost:8190/realms/platform'
const jwt = (claims) =>
  `${Buffer.from('{"alg":"RS256"}').toString('base64url')}.${Buffer.from(JSON.stringify(claims)).toString('base64url')}.signature`

/** The query the popup was sent to. */
const requestOf = (popup) => Object.fromEntries(new URL(popup.location.href).searchParams)

/** What auth-callback.html posts when Keycloak returns the popup to it. */
function callbackPage(message) {
  const channel = new TabChannel('platform-login')
  channel.postMessage({ code: null, iss: null, error: null, errorDescription: null, ...message })
  channel.close()
}

/** Keycloak's token endpoint answering the code exchange. */
function tokenEndpointAnswers({ nonce, ok = true, body } = {}) {
  fetchMock.mockImplementationOnce(async () => ({
    ok,
    json: async () => body ?? { access_token: jwt({ sub: 'u-1' }), refresh_token: 'refresh-token', id_token: jwt({ nonce }) }
  }))
}

/** Starts a sign-in and waits until its popup has been sent to Keycloak. */
async function startSignIn(options) {
  const done = auth.login(options)
  const popup = popups.at(-1)
  await vi.waitFor(() => expect(popup.location.href).not.toBe(''))
  return { done, popup, request: requestOf(popup) }
}

/** A whole successful popup sign-in. */
async function signIn(options) {
  const started = await startSignIn(options)
  tokenEndpointAnswers({ nonce: started.request.nonce })
  callbackPage({ state: started.request.state, code: 'the-code', iss: REALM })
  await started.done
  return started
}

beforeEach(() => {
  popups.length = 0
  kc.instances.length = 0
  browserWindow.open.mockClear()
  browserWindow.location.replace.mockReset()
  fetchMock.mockReset()
  storageWrites.mockClear()
  sessionEnded.mockClear()
})

afterEach(() => {
  for (const instance of kc.instances) instance.clearToken()
})

describe('Signing in through a popup', () => {
  it('opens the sign-in window in the same tick as the click, so the browser allows it', async () => {
    const done = auth.login()

    expect(browserWindow.open).toHaveBeenCalledTimes(1)

    popups[0].close()
    await expect(done).rejects.toMatchObject({ code: 'popup_closed' })
  })

  it('sends the window straight to OneID through Keycloak, with PKCE, state and nonce', async () => {
    const { request } = await signIn()

    expect(requestOf(popups[0]).client_id).toBe('platform-web')
    expect(request).toMatchObject({
      client_id: 'platform-web',
      redirect_uri: 'http://localhost:5174/auth-callback.html',
      response_type: 'code',
      scope: 'openid',
      code_challenge_method: 'S256',
      kc_idp_hint: 'oneid'
    })
    expect(request.state).toMatch(/^[A-Za-z0-9_-]{43}$/)
    expect(request.nonce).toMatch(/^[A-Za-z0-9_-]{43}$/)
    expect(request.nonce).not.toBe(request.state)

    const [url, init] = fetchMock.mock.calls[0]
    const exchange = new URLSearchParams(init.body)
    expect(url).toBe(`${REALM}/protocol/openid-connect/token`)
    expect(exchange.get('grant_type')).toBe('authorization_code')
    expect(exchange.get('code')).toBe('the-code')
    expect(exchange.get('redirect_uri')).toBe('http://localhost:5174/auth-callback.html')
    // The verifier redeemed here is the one whose hash the window carried.
    expect(createHash('sha256').update(exchange.get('code_verifier')).digest('base64url')).toBe(request.code_challenge)
  })

  it('finishes signed in, with keycloak-js holding the tokens, and closes the window', async () => {
    const { popup } = await signIn()

    expect(auth.isAuthenticated()).toBe(true)
    expect(auth.username()).toBe('akarimov')
    expect(auth.realmRoles()).toEqual(['JISMONIY_SHAXS'])
    expect(kc.instances.at(-1).initOptions).toMatchObject({ refreshToken: 'refresh-token', checkLoginIframe: false })
    expect(popup.closed).toBe(true)
  })

  it('keeps tokens in memory only, never in localStorage or sessionStorage', async () => {
    await signIn()

    expect(storageWrites).not.toHaveBeenCalled()
  })

  it('offers Keycloak sign-in page for the developer accounts, without the OneID hint', async () => {
    const { request } = await signIn({ idpHint: null })

    expect(request.kc_idp_hint).toBeUndefined()
  })

  it('ignores an answer carrying another state: another tab, or an old attempt', async () => {
    const { done, popup } = await startSignIn()

    callbackPage({ state: 'not-this-attempt', code: 'someone-elses-code', iss: REALM })
    await new Promise((resolve) => setTimeout(resolve, 20))

    expect(fetchMock).not.toHaveBeenCalled()
    expect(auth.isAuthenticated()).toBe(false)
    popup.close()
    await expect(done).rejects.toMatchObject({ code: 'popup_closed' })
  })

  it('refuses an ID token whose nonce belongs to another attempt', async () => {
    const { done, request } = await startSignIn()

    tokenEndpointAnswers({ nonce: 'a-different-nonce' })
    callbackPage({ state: request.state, code: 'the-code', iss: REALM })

    await expect(done).rejects.toMatchObject({ code: 'nonce_mismatch' })
    expect(auth.isAuthenticated()).toBe(false)
  })

  it('refuses an answer claiming to come from another issuer, without redeeming its code', async () => {
    const { done, request } = await startSignIn()

    callbackPage({ state: request.state, code: 'the-code', iss: 'http://evil.example/realms/platform' })

    await expect(done).rejects.toMatchObject({ code: 'issuer_mismatch' })
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('reports an error Keycloak or OneID sent back', async () => {
    const { done, request } = await startSignIn()

    callbackPage({ state: request.state, error: 'access_denied', errorDescription: 'Cancelled' })

    await expect(done).rejects.toMatchObject({ code: 'access_denied', message: 'Cancelled' })
    expect(auth.isAuthenticated()).toBe(false)
  })

  it('reports a code exchange Keycloak refused', async () => {
    const { done, request } = await startSignIn()

    tokenEndpointAnswers({ ok: false, body: { error: 'invalid_grant', error_description: 'Code not valid' } })
    callbackPage({ state: request.state, code: 'the-code', iss: REALM })

    await expect(done).rejects.toMatchObject({ code: 'invalid_grant' })
  })

  it('reports a blocked popup at once', async () => {
    browserWindow.open.mockReturnValueOnce(null)

    await expect(auth.login()).rejects.toMatchObject({ code: 'popup_blocked' })
  })

  it('brings the same window forward on a second click instead of opening another', async () => {
    const first = auth.login()
    const second = auth.login()

    expect(second).toBe(first)
    expect(browserWindow.open).toHaveBeenCalledTimes(1)
    expect(popups[0].focus).toHaveBeenCalled()

    popups[0].close()
    await expect(first).rejects.toMatchObject({ code: 'popup_closed' })
  })
})

describe('25. Frontend clears authentication state', () => {
  it('logout sends the browser to Keycloak end-session with the ID token as id_token_hint', async () => {
    await signIn()
    const idToken = kc.instances.at(-1).idToken

    auth.logout()

    expect(browserWindow.location.replace).toHaveBeenCalledTimes(1)
    const url = new URL(browserWindow.location.replace.mock.calls[0][0])
    expect(url.pathname).toBe('/realms/platform/protocol/openid-connect/logout')
    expect(url.searchParams.get('id_token_hint')).toBe(idToken)
    expect(url.searchParams.get('post_logout_redirect_uri')).toBe('http://localhost:5174/')
  })

  it('logout has already forgotten the tokens when the browser leaves', async () => {
    await signIn()
    browserWindow.location.replace.mockImplementation(() => {
      expect(auth.isAuthenticated()).toBe(false)
      expect(sessionEnded).toHaveBeenCalledTimes(1)
    })

    auth.logout()

    expect(browserWindow.location.replace).toHaveBeenCalledTimes(1)
  })

  it('logout tells the other tabs of the app', async () => {
    await signIn()
    const otherTab = new TabChannel('platform-auth')
    const received = new Promise((resolve) => otherTab.addEventListener('message', (event) => resolve(event.data)))

    auth.logout()

    await expect(received).resolves.toBe('logout')
    otherTab.close()
  })

  it('a logout in another tab signs this tab out, without a redirect of its own', async () => {
    await signIn()
    const otherTab = new TabChannel('platform-auth')

    otherTab.postMessage('logout')

    await vi.waitFor(() => expect(auth.isAuthenticated()).toBe(false))
    expect(sessionEnded).toHaveBeenCalledTimes(1)
    expect(browserWindow.location.replace).not.toHaveBeenCalled()
    otherTab.close()
  })

  it('a refresh Keycloak refuses leaves the tab signed out, and opens no sign-in window', async () => {
    await signIn()
    const keycloak = kc.instances.at(-1)
    // What keycloak-js does when the token endpoint answers 400: clear, then reject.
    keycloak.updateToken.mockImplementation(async () => {
      keycloak.clearToken()
      throw new Error('400 Session not active')
    })

    await expect(auth.getValidToken()).resolves.toBeNull()
    expect(sessionEnded).toHaveBeenCalledTimes(1)
    expect(browserWindow.open).toHaveBeenCalledTimes(1)
  })

  it('an unreachable Keycloak keeps the tokens and lets the backend decide', async () => {
    await signIn()
    const keycloak = kc.instances.at(-1)
    keycloak.updateToken.mockRejectedValue(new Error('network error'))

    await expect(auth.getValidToken()).resolves.toBe(keycloak.token)
    expect(sessionEnded).not.toHaveBeenCalled()
  })

  it('a signed-out tab sends no token and asks Keycloak for nothing', async () => {
    await expect(auth.getValidToken()).resolves.toBeNull()
    expect(fetchMock).not.toHaveBeenCalled()
  })
})
