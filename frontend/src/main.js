import { createApp } from 'vue'
import App from './App.vue'
import { initKeycloak } from './keycloak'
import './style.css'

// Keycloak is initialised BEFORE Vue mounts. On the way back from the login page
// this is where the authorization code becomes tokens, so by the time App.vue
// renders the app already knows who the user is.
initKeycloak()
  .then(() => createApp(App).mount('#app'))
  .catch((error) => {
    console.error('Keycloak init failed', error)
    document.getElementById('app').innerHTML =
      '<p style="font-family:system-ui;padding:2rem">Keycloak init failed. Is Keycloak running on http://localhost:8190 ? See the browser console.</p>'
  })
