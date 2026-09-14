import { createApp } from 'vue'
import App from './App.vue'
import './style.css'

// Nothing to initialise before mounting. Signing in happens later, in a separate
// window, when the person presses Login; see keycloak.js. A reload starts signed
// out, because tokens are only ever held in memory.
createApp(App).mount('#app')
