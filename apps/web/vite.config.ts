/// <reference types="vitest/config" />
import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// In production, NGINX routes /api, /oauth2, and /logout to the API under
// one public origin (see the repository README's "Browser and session
// boundary" notes). This dev-server proxy recreates that same-origin
// arrangement locally, so a session cookie set by the API is usable by
// fetch calls the dev server itself serves on a different port.
// Where the dev server proxies API calls to. Overridable so a second API instance (for example one
// started on other ports while yours keeps running) can be driven through the same dev server.
const apiOrigin = process.env.BROWNIE_API_ORIGIN ?? 'http://localhost:8081'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': apiOrigin,
      '/oauth2': apiOrigin,
      '/login': apiOrigin,
      '/logout': apiOrigin,
    },
  },
  test: {
    environment: 'jsdom',
    globals: false,
    // e2e/ holds real Playwright specs (a different test runner, a different `test`/`expect`) --
    // vitest's own default include glob would otherwise try, and fail, to run them too.
    exclude: ['**/node_modules/**', '**/e2e/**'],
  },
})
