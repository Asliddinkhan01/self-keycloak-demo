<script setup>
import { ref, computed } from 'vue'
import { keycloak, login, logout, register, realmRoles } from './keycloak'
import * as api from './api'

const authenticated = ref(keycloak.authenticated === true)
const username = computed(() => keycloak.tokenParsed?.preferred_username ?? '-')
const roles = computed(() => realmRoles())

// Form state for the ADMIN-only actions.
const newName = ref('Mechanical keyboard')
const newPrice = ref('129.00')
const deleteId = ref('1')

// Last API result, shown at the bottom of the page.
const lastCall = ref('')
const result = ref(null)
const busy = ref(false)

async function call(label, fn) {
  busy.value = true
  lastCall.value = label
  try {
    result.value = await fn()
  } finally {
    busy.value = false
  }
}

const statusText = computed(() => {
  if (!result.value) return ''
  const s = result.value.status
  if (s === 0) return 'network / CORS error'
  if (s === 401) return '401 Unauthorized - no valid token was presented'
  if (s === 403) return '403 Forbidden - valid token, but the role is missing'
  return `${s} ${result.value.ok ? 'OK' : ''}`.trim()
})
</script>

<template>
  <h1>Keycloak Microservices Demo</h1>

  <!-- ---------------- authentication ---------------- -->
  <div class="card">
    <h2>Authentication</h2>

    <template v-if="!authenticated">
      <p class="hint">
        You are not logged in. Both buttons send the browser to Keycloak. The
        login and sign-up forms belong to Keycloak, not to this app.
      </p>
      <button class="primary" @click="login()">Login</button>
      <button @click="register()">Sign up</button>
      <p class="hint">
        A new account created here gets the USER role automatically, through the
        realm's default-roles-demo composite. ADMIN is never self-granted.
      </p>
    </template>

    <template v-else>
      <p>
        <strong>{{ username }}</strong>
        <span style="margin-left: 0.75rem">
          <span
            v-for="role in roles"
            :key="role"
            class="badge"
            :class="{ admin: role === 'ADMIN' }"
          >{{ role }}</span>
        </span>
      </p>
      <p class="hint">
        These roles come from the realm_access.roles claim inside the access token.
      </p>
      <button @click="logout()">Logout</button>
    </template>
  </div>

  <!-- ---------------- products ---------------- -->
  <div class="card">
    <h2>product-service :8082</h2>

    <button :disabled="busy" @click="call('GET /api/products/public (no token)', api.getPublicProducts)">
      Public products info
    </button>
    <button :disabled="busy" @click="call('GET /api/products (no token)', api.getProductsWithoutToken)">
      Products WITHOUT token &rarr; 401
    </button>
    <button :disabled="busy || !authenticated" @click="call('GET /api/products', api.getProducts)">
      Get products
    </button>

    <div style="margin-top: 0.75rem">
      <input v-model="newName" placeholder="product name" />
      <input v-model="newPrice" placeholder="price" style="width: 6rem" />
      <button
        :disabled="busy || !authenticated"
        @click="call('POST /api/products', () => api.createProduct(newName, newPrice))"
      >
        Create product (ADMIN)
      </button>
    </div>

    <div>
      <input v-model="deleteId" placeholder="id" style="width: 4rem" />
      <button
        class="danger"
        :disabled="busy || !authenticated"
        @click="call('DELETE /api/products/' + deleteId, () => api.deleteProduct(deleteId))"
      >
        Delete product (ADMIN)
      </button>
    </div>

    <p class="hint">
      The ADMIN buttons stay enabled for everyone on purpose: log in as
      <code>user</code> and press one to see a real 403 come back from Spring Security.
    </p>
  </div>

  <!-- ---------------- users ---------------- -->
  <div class="card">
    <h2>user-service :8081</h2>

    <button :disabled="busy || !authenticated" @click="call('GET /api/users/me', api.getMe)">
      Get my profile (USER)
    </button>
    <button :disabled="busy || !authenticated" @click="call('GET /api/users/me/claims', api.getMyClaims)">
      Show my raw token claims (USER)
    </button>
    <button :disabled="busy || !authenticated" @click="call('GET /api/users', api.getAllUsers)">
      List all users (ADMIN)
    </button>
  </div>

  <!-- ---------------- result ---------------- -->
  <div class="card" v-if="result">
    <h2>Response</h2>
    <p class="hint">{{ lastCall }}</p>
    <p class="status" :class="result.ok ? 'ok' : 'err'">{{ statusText }}</p>
    <pre>{{ JSON.stringify(result.data, null, 2) }}</pre>
  </div>
</template>
