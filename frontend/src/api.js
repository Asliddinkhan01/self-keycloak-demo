import { getValidToken } from './keycloak'

/**
 * Everything goes through the gateway. One origin for the browser, so CORS is
 * configured once, and the services are not individually exposed.
 */
const GATEWAY = import.meta.env.VITE_GATEWAY_URL ?? 'http://localhost:8090'

/**
 * The whole API client. Its real job is these two headers:
 *
 *   Authorization:      Bearer <keycloak token>   who you are
 *   X-Organization-TIN: 111111111                 who you are acting for
 *
 * The second one is a request, not an assertion. The backend verifies it against
 * the caller's memberships on every call, so sending a TIN you do not belong to
 * produces 403 rather than access. Nothing here can grant anything; it only
 * states what is being asked.
 *
 * Everything else is error handling, so the UI can show the raw status code —
 * which is the interesting part while learning: 200, 400, 401 and 403 each mean
 * something different here.
 */
async function request(path, { method = 'GET', body, organizationTin, withToken = true } = {}) {
  const headers = {}

  if (body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }

  if (withToken) {
    const token = await getValidToken()
    if (token) {
      headers.Authorization = `Bearer ${token}`
    }
  }

  if (organizationTin) {
    headers['X-Organization-TIN'] = organizationTin
  }

  let response
  try {
    response = await fetch(`${GATEWAY}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body)
    })
  } catch (error) {
    // A CORS failure, or the gateway not running, lands here with no status.
    return { status: 0, ok: false, data: `Network or CORS error: ${error.message}` }
  }

  const text = await response.text()
  let data = null
  if (text) {
    try {
      data = JSON.parse(text)
    } catch {
      data = text
    }
  }

  return { status: response.status, ok: response.ok, data }
}

// ---- session ---------------------------------------------------------------

/**
 * Session bootstrap. user-service upserts the local profile from the token and
 * calls organization-service as a service to reconcile memberships, then returns
 * roles, effective permissions and active organizations in one response.
 */
export const getMe = () => request('/api/users/me')

export const getMyClaims = () => request('/api/users/me/claims')

export const getMyOrganizations = () => request('/api/organizations/mine')

// ---- public ----------------------------------------------------------------

/** Deliberately sent with no Authorization header. */
export const getPublicHello = () => request('/api/public/hello', { withToken: false })

/** Sent with no token on purpose, to show 401 against a protected route. */
export const getProjectsWithoutToken = (tin) =>
  request('/api/projects', { withToken: false, organizationTin: tin })

// ---- projects (organization-scoped) ----------------------------------------

export const getProjects = (tin) => request('/api/projects', { organizationTin: tin })

export const createProject = (tin, name, address) =>
  request('/api/projects', { method: 'POST', organizationTin: tin, body: { name, address } })

/** Sent without the organization header, to show the 400 that follows. */
export const getProjectsWithoutOrganization = () => request('/api/projects')

// ---- payments (organization-scoped) ----------------------------------------

export const getPayments = (tin) => request('/api/payments', { organizationTin: tin })

export const createPayment = (tin, projectId, amount) =>
  request('/api/payments', {
    method: 'POST',
    organizationTin: tin,
    body: { projectId, amount: Number(amount) }
  })

// ---- administrative --------------------------------------------------------

export const getAdminUsers = () => request('/api/admin/users')

export const getPlatformAudit = () => request('/api/platform/audit')

// ---- organizations ---------------------------------------------------------

export const getOrganizations = () => request('/api/organizations')
