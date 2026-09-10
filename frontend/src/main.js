import { createApp } from 'vue'
import App from './App.vue'
import { initKeycloak } from './keycloak'
import './style.css'

// Keycloak is initialised BEFORE Vue mounts. On the way back from the login
// page this is where the authorization code is exchanged for tokens, so by the
// time App.vue renders we already know who the user is.
initKeycloak()
  .then(() => {
    createApp(App).mount('#app')
  })
  .catch((error) => {
    console.error('Keycloak init failed', error)
    document.getElementById('app').innerHTML =
      '<p style="font-family:sans-serif;padding:2rem">Keycloak init failed. Is Keycloak running on http://localhost:8080 ? See the browser console.</p>'
  })
