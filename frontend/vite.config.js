import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    // Fixed port. It must match three things or login breaks: the redirect URIs
    // and web origins of the platform-web client in realm-export.json, and the
    // gateway's app.cors.allowed-origin. strictPort makes Vite fail loudly
    // rather than quietly moving to 5175 and producing a confusing
    // "Invalid parameter: redirect_uri" from Keycloak.
    port: 5174,
    strictPort: true
  }
})
