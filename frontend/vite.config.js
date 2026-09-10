import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    // Fixed port: it must match the redirect URIs and web origins registered
    // for the demo-frontend client in Keycloak. strictPort makes Vite fail
    // loudly instead of silently moving to 5174, which would break login.
    port: 5173,
    strictPort: true
  }
})
