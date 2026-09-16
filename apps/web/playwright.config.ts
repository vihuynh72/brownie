import { defineConfig, devices } from '@playwright/test'
import { fileURLToPath } from 'node:url'

const AUTH_STATE = fileURLToPath(new URL('./e2e/.auth/state.json', import.meta.url))

/**
 * Drives the real running app in a real browser against a real backend --
 * see e2e/README.md for what "real" means here (a live docker-compose
 * Postgres/Azurite/ClamAV stack and a live brownie-api process are
 * prerequisites this config does not start for you) and why these specs
 * never touch the AI extraction path (a live OpenAI key and a running
 * brownie-worker would be needed, and the frontend has no manual
 * field-value editing UI yet to fall back on instead).
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  globalSetup: './e2e/global-setup.ts',
  use: {
    baseURL: 'http://localhost:5173',
    storageState: AUTH_STATE,
    trace: 'retain-on-failure',
  },
  webServer: {
    command: 'npm run dev -- --port 5173 --strictPort',
    url: 'http://localhost:5173',
    reuseExistingServer: !process.env.CI,
    timeout: 30_000,
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
})
