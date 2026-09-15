/// <reference types="vitest/config" />
import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// In production, NGINX routes /api, /oauth2, and /logout to the API under
// one public origin (see the repository README's "Browser and session
// boundary" notes). This dev-server proxy recreates that same-origin
// arrangement locally, so a session cookie set by the API is usable by
// fetch calls the dev server itself serves on a different port.
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
      '/api': 'http://localhost:8081',
      '/oauth2': 'http://localhost:8081',
      '/login': 'http://localhost:8081',
      '/logout': 'http://localhost:8081',
    },
  },
  test: {
    environment: 'jsdom',
    globals: false,
  },
})
