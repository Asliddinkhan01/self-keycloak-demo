import { fileURLToPath } from 'node:url'
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
  },
  build: {
    rollupOptions: {
      // Two pages: the app, and the sign-in window's callback page, which
      // Keycloak redirects to with the authorization code. Both must be built.
      input: {
        main: fileURLToPath(new URL('./index.html', import.meta.url)),
        authCallback: fileURLToPath(new URL('./auth-callback.html', import.meta.url))
      }
    }
  }
})
