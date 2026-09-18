import { chromium } from '@playwright/test'
import { fileURLToPath } from 'node:url'

const AUTH_STATE = fileURLToPath(new URL('./.auth/state.json', import.meta.url))
const BACKEND_ORIGIN = process.env.BROWNIE_E2E_BACKEND_ORIGIN ?? 'http://localhost:8081'
const SUBJECT = process.env.BROWNIE_E2E_SUBJECT ?? 'e2e-playwright'
const TEST_SUPPORT_TOKEN = process.env.BROWNIE_TEST_SUPPORT_TOKEN

/**
 * Seeds a real, authenticated session the same way this codebase's own
 * MockMvc integration tests already do (see backend's
 * TestSupportAuthController, gated to the local/test Spring profiles only)
 * -- there is no real identity provider available to drive an actual OIDC
 * login through, and this app has no in-app login form to automate even
 * if there were.
 *
 * That route only exists when the backend was started with
 * BROWNIE_TEST_SUPPORT_TOKEN set, and it requires the same value back in
 * an X-Test-Support-Token header from a loopback client, so a browser page
 * on its own can never reach it: the token has to come from this process's
 * environment, the same .env the backend read.
 *
 * context.request (not a bare fetch) is what makes this work: any
 * Set-Cookie the response carries is captured straight into this
 * context's own cookie jar, so storageState() below persists the exact
 * same SESSION/XSRF-TOKEN cookies every spec's browser context reuses.
 * Cookies are scoped by domain, not by port, so a cookie issued directly
 * by the backend's own origin (8081) is still sent once a later page
 * navigates to the Vite dev server (5173) on the same host.
 */
export default async function globalSetup(): Promise<void> {
  if (!TEST_SUPPORT_TOKEN) {
    throw new Error(
      'BROWNIE_TEST_SUPPORT_TOKEN is not set in this shell. Set it in the repository .env (the backend must ' +
        'have been started with the same value) and export it before running the e2e suite. See the End-to-end ' +
        'tests section of the repository README.',
    )
  }

  const browser = await chromium.launch()
  const context = await browser.newContext()

  const response = await context.request.get(`${BACKEND_ORIGIN}/test-support/sessions?subject=${SUBJECT}`, {
    headers: { 'X-Test-Support-Token': TEST_SUPPORT_TOKEN },
  })
  if (!response.ok()) {
    throw new Error(
      `Could not seed an e2e session from ${BACKEND_ORIGIN}/test-support/sessions (status ${response.status()}). ` +
        'Is brownie-api running locally with BROWNIE_ENVIRONMENT=local and the same BROWNIE_TEST_SUPPORT_TOKEN? ' +
        'See the End-to-end tests section of the repository README.',
    )
  }

  await context.storageState({ path: AUTH_STATE })
  await browser.close()
}
