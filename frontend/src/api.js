import { getValidToken } from './keycloak'

const USER_SERVICE = 'http://localhost:8081'
const PRODUCT_SERVICE = 'http://localhost:8082'

/**
 * The whole "API client". Its only real job is this line:
 *
 *   headers.Authorization = `Bearer ${token}`
 *
 * That header is what turns an anonymous HTTP call into an authenticated one.
 * Everything else here is error handling so the UI can show you the raw status
 * code, which is the interesting part while learning (200 / 401 / 403).
 */
async function request(url, { method = 'GET', body, withToken = true } = {}) {
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

  let response
  try {
    response = await fetch(url, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body)
    })
  } catch (error) {
    // A CORS failure or a service that is not running lands here.
    return { status: 0, ok: false, data: `Network/CORS error: ${error.message}` }
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

// ---- product-service (:8082) ------------------------------------------------

/** Public: deliberately sent WITHOUT an Authorization header. */
export const getPublicProducts = () =>
  request(`${PRODUCT_SERVICE}/api/products/public`, { withToken: false })

/** Authenticated: any valid token. */
export const getProducts = () => request(`${PRODUCT_SERVICE}/api/products`)

/** ADMIN only. */
export const createProduct = (name, price) =>
  request(`${PRODUCT_SERVICE}/api/products`, {
    method: 'POST',
    body: { name, price: Number(price) }
  })

/** ADMIN only. */
export const deleteProduct = (id) =>
  request(`${PRODUCT_SERVICE}/api/products/${id}`, { method: 'DELETE' })

/** Sent on purpose without a token, to demonstrate 401. */
export const getProductsWithoutToken = () =>
  request(`${PRODUCT_SERVICE}/api/products`, { withToken: false })

// ---- user-service (:8081) ---------------------------------------------------

/** USER role required. */
export const getMe = () => request(`${USER_SERVICE}/api/users/me`)

/** USER role required. Returns every claim of the access token. */
export const getMyClaims = () => request(`${USER_SERVICE}/api/users/me/claims`)

/** ADMIN role required. */
export const getAllUsers = () => request(`${USER_SERVICE}/api/users`)
