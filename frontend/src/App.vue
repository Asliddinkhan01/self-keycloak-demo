<script setup>
import { ref, computed } from 'vue'
import { isAuthenticated, login, logout, onSessionEnded, realmRoles, username } from './keycloak'
import * as api from './api'

const authenticated = ref(isAuthenticated())
const roles = computed(() => (authenticated.value ? realmRoles() : []))
const sessionNotice = ref('')
const signingIn = ref(false)
const loginError = ref('')

// Called straight from the click, so login() can open its window before anything
// asynchronous happens; see keycloak.js.
async function signIn(idpHint) {
  loginError.value = ''
  signingIn.value = true
  try {
    await login({ idpHint })
    authenticated.value = true
    sessionNotice.value = ''
    await loadSession()
  } catch (error) {
    loginError.value = error.message
  } finally {
    signingIn.value = false
  }
}

// Session bootstrap: profile, effective permissions and active organizations.
const me = ref(null)
// The acting organization. Request context, not identity — it changes without a
// new token, and the backend verifies it on every call.
const actingTin = ref('')

const newProjectName = ref('Yangi qurilish loyihasi')
const newProjectAddress = ref('Toshkent shahri')
const paymentProjectId = ref('')
const paymentAmount = ref('1500.00')

const lastCall = ref('')
const result = ref(null)
const busy = ref(false)

// However this tab's session ends, nothing that looks signed in stays on screen:
// no profile, no acting organization, no response from a call made as that person.
onSessionEnded(() => {
  authenticated.value = false
  me.value = null
  actingTin.value = ''
  result.value = null
  sessionNotice.value = 'Your session has ended: signed out in this or another tab, or ended by Keycloak.'
})

async function call(label, fn) {
  busy.value = true
  lastCall.value = label
  try {
    result.value = await fn()
  } finally {
    busy.value = false
  }
}

async function loadSession() {
  await call('GET /api/users/me', api.getMe)
  if (result.value?.ok) {
    me.value = result.value.data
    const tins = me.value.organizationTinsActive ?? []
    if (!actingTin.value && tins.length) {
      actingTin.value = tins[0]
    }
  }
}

const organizations = computed(() => me.value?.organizationTinsActive ?? [])
const permissions = computed(() => me.value?.effectivePermissionsHere ?? [])

const statusText = computed(() => {
  if (!result.value) return ''
  const s = result.value.status
  if (s === 0) return 'network or CORS error'
  if (s === 400) return '400 Bad Request - no X-Organization-TIN was sent'
  if (s === 401) return '401 Unauthorized - no valid token'
  if (s === 403) return '403 Forbidden - valid token, but not permitted'
  return `${s}${result.value.ok ? ' OK' : ''}`
})
</script>

<template>
  <h1>OneID Platform</h1>
  <p class="sub">
    Vue talks only to the gateway on :8090. It never sees OneID, the OneID token,
    or the PIN.
  </p>

  <!-- ------------- session ------------- -->
  <div class="card">
    <h2>Session</h2>

    <template v-if="!authenticated">
      <p v-if="sessionNotice" class="status err">{{ sessionNotice }}</p>
      <p class="hint">
        Not signed in. Login opens a separate window on OneID. After you sign in
        there, Keycloak creates or updates your account on its server, the window
        closes, and this page is signed in. This app never sees your OneID
        credentials or OneID's token, and renders no password field.
      </p>
      <button class="primary" :disabled="signingIn" @click="signIn('oneid')">
        {{ signingIn ? 'Signing in…' : 'Login with OneID' }}
      </button>
      <button :disabled="signingIn" @click="signIn(null)">Developer accounts (password)</button>
      <p v-if="loginError" class="status err">{{ loginError }}</p>
    </template>

    <template v-else>
      <div class="row">
        <strong>{{ username() }}</strong>
        <span>
          <span
            v-for="role in roles"
            :key="role"
            class="badge"
            :class="{ admin: role === 'ADMIN' || role === 'SUPER_ADMIN' }"
          >{{ role }}</span>
        </span>
      </div>
      <p class="hint">Roles come from the <code>realm_access.roles</code> claim.</p>

      <button class="primary" :disabled="busy" @click="loadSession()">Load session</button>
      <button :disabled="busy" @click="call('GET /api/users/me/claims', api.getMyClaims)">Raw token claims</button>
      <button :disabled="busy" @click="call('GET /api/organizations/mine', api.getMyOrganizations)">My organizations</button>
      <button @click="logout()">Logout</button>
      <p class="hint">
        Logout ends the Keycloak session itself, not just this tab's tokens, and
        signs out any other tab of this app too.
      </p>

      <template v-if="permissions.length">
        <p class="hint" style="margin-top:.9rem">
          Effective permissions at user-service, resolved from roles in its database,
          never carried in the token:
        </p>
        <span v-for="p in permissions" :key="p" class="badge perm">{{ p }}</span>
      </template>
    </template>
  </div>

  <!-- ------------- organization switcher ------------- -->
  <div class="card" v-if="authenticated">
    <h2>Acting organization</h2>

    <template v-if="organizations.length">
      <div class="row">
        <button
          v-for="tin in organizations"
          :key="tin"
          :class="{ on: actingTin === tin }"
          @click="actingTin = tin"
        >{{ tin }}</button>
      </div>
      <p class="hint">
        Sent as <code>X-Organization-TIN</code>. Switching needs no new token, because
        the organization is request context rather than identity. Try typing a TIN you
        do not belong to, such as 444444444, and the backend answers 403.
      </p>
      <input v-model="actingTin" placeholder="TIN" style="width:9rem" />
    </template>

    <template v-else>
      <p class="hint">
        None yet. Press <strong>Load session</strong> above: that call is what asks
        organization-service to reconcile memberships from the OneID claim.
      </p>
      <input v-model="actingTin" placeholder="TIN" style="width:9rem" />
    </template>
  </div>

  <!-- ------------- projects ------------- -->
  <div class="card" v-if="authenticated">
    <h2>Projects &mdash; needs PROJECT_* and an organization</h2>

    <button :disabled="busy" @click="call('GET /api/projects', () => api.getProjects(actingTin))">
      List projects
    </button>
    <button :disabled="busy" @click="call('GET /api/projects (no org header)', api.getProjectsWithoutOrganization)">
      Without organization &rarr; 400
    </button>
    <button :disabled="busy" @click="call('GET /api/projects (no token)', () => api.getProjectsWithoutToken(actingTin))">
      Without token &rarr; 401
    </button>

    <div class="row" style="margin-top:.7rem">
      <input v-model="newProjectName" placeholder="project name" style="width:15rem" />
      <input v-model="newProjectAddress" placeholder="address" style="width:12rem" />
      <button
        :disabled="busy"
        @click="call('POST /api/projects', () => api.createProject(actingTin, newProjectName, newProjectAddress))"
      >Create project (QURUVCHI)</button>
    </div>

    <p class="hint">
      Buttons stay enabled for everyone on purpose. Sign in as <code>bank_user</code>
      and press Create to see a real 403 from the server. Hiding a button is a
      convenience, never the control.
    </p>
  </div>

  <!-- ------------- payments ------------- -->
  <div class="card" v-if="authenticated">
    <h2>Payments &mdash; needs PAYMENT_* and an organization</h2>

    <button :disabled="busy" @click="call('GET /api/payments', () => api.getPayments(actingTin))">
      List payments
    </button>

    <div class="row" style="margin-top:.7rem">
      <input v-model="paymentProjectId" placeholder="project id (uuid)" style="width:21rem" />
      <input v-model="paymentAmount" placeholder="amount" style="width:7rem" />
      <button
        :disabled="busy"
        @click="call('POST /api/payments', () => api.createPayment(actingTin, paymentProjectId, paymentAmount))"
      >Record payment (BANK)</button>
    </div>

    <p class="hint">
      <code>dual</code> holds QURUVCHI and BANK and can do both. Effective permissions
      are the union across roles, with no special case in the code.
    </p>
  </div>

  <!-- ------------- administrative ------------- -->
  <div class="card" v-if="authenticated">
    <h2>Administrative</h2>

    <button :disabled="busy" @click="call('GET /api/organizations', api.getOrganizations)">
      All organizations (ORGANIZATION_READ)
    </button>
    <button :disabled="busy" @click="call('GET /api/admin/users', api.getAdminUsers)">
      All users (USER_READ)
    </button>
    <button class="danger" :disabled="busy" @click="call('GET /api/platform/audit', api.getPlatformAudit)">
      Platform audit (PLATFORM_ADMIN)
    </button>

    <p class="hint">
      <code>admin_user</code> reaches the first two and not the third.
      <code>super_admin</code> is the reverse on the last two. Neither role contains
      the other, which is the design rather than an oversight.
    </p>
  </div>

  <!-- ------------- public ------------- -->
  <div class="card">
    <h2>Public</h2>
    <button :disabled="busy" @click="call('GET /api/public/hello', api.getPublicHello)">
      Public endpoint (no token)
    </button>
  </div>

  <!-- ------------- response ------------- -->
  <div class="card" v-if="result">
    <h2>Response</h2>
    <p class="hint">{{ lastCall }}<span v-if="actingTin"> &middot; X-Organization-TIN: {{ actingTin }}</span></p>
    <p class="status" :class="result.ok ? 'ok' : 'err'">{{ statusText }}</p>
    <pre>{{ JSON.stringify(result.data, null, 2) }}</pre>
  </div>
</template>
