import { defineConfig, devices } from '@playwright/test'
import { fileURLToPath } from 'node:url'

// The dev server port the suite drives. Overridable so a second suite can run beside a dev server you keep open
// (pair it with BROWNIE_API_ORIGIN for the proxy and BROWNIE_E2E_BACKEND_ORIGIN for session seeding).
const WEB_PORT = process.env.BROWNIE_E2E_WEB_PORT ?? '5173'

const AUTH_STATE = fileURLToPath(new URL('./e2e/.auth/state.json', import.meta.url))

/**
 * Drives the real running app in a real browser against a real backend --
 * see the End-to-end tests section of the repository README for what "real" means here (a live docker-compose
 * Postgres/Azurite/ClamAV stack and a live brownie-api process are
 * prerequisites this config does not start for you). Real AI extraction
 * is separately opt-in because it spends money; see the README for the
 * switch and for what the default run does and does not prove.
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  globalSetup: './e2e/global-setup.ts',
  use: {
    baseURL: `http://localhost:${WEB_PORT}`,
    storageState: AUTH_STATE,
    trace: 'retain-on-failure',
  },
  webServer: {
    command: `npm run dev -- --port ${WEB_PORT} --strictPort`,
    url: `http://localhost:${WEB_PORT}`,
    reuseExistingServer: !process.env.CI,
    timeout: 30_000,
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
})
