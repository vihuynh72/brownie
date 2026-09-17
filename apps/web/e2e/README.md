# End-to-end tests

Real Playwright specs driving the real built app in a real Chromium
browser against a real running backend -- not the component-level
`vitest`/`jest-axe` tests under `src/**/__tests__`, which mount one Vue
component in `jsdom` with every API call mocked. These specs make actual
HTTP requests to a `brownie-api ` process talking to actual Postgres,
Azurite, and ClamAV containers.

## Prerequisites

Before running `npm run test:e2e`, from the repository root:

```sh
docker compose -f infra/local/compose.yaml up -d --wait
cd backend && ./mvnw -pl brownie-api spring-boot:run
```

(see the repository root `README.md`'s own `Run` section for the full
`.env` setup this depends on). `playwright.config.ts` starts the Vite dev
server itself; it does not start Postgres, Azurite, ClamAV, or
`brownie-api` for you.

## How sign-in works here

This app has no in-app login form -- signing in is a full redirect to a
real Entra External ID tenant, which these tests have no credentials to
drive through, and no test double stands in for it. `global-setup.ts`
instead calls `TestSupportAuthController`'s `/test-support/sessions`
route, which creates the exact same kind of session a real login would
(same provisioning, same cookies) without a real identity-provider round
trip. That controller only exists at all when `BROWNIE_ENVIRONMENT=local`
or `test` -- it is not reachable, and therefore this whole approach does
not work, against a `pilot` or `production` deployment. That is
deliberate, not a gap to close.

## What the default run covers, and the paid golden path

The default run (`npm run test:e2e`) covers template teaching, the
empty-document export safety gate, and `session-and-recovery.spec.ts`:
real sign-out (the server must answer 401 afterwards, and the page must
carry on to the identity provider's end-session URL -- that one external
hop is stubbed so the suite stays offline-safe), recovery from a failed
identity request, and a failed optional source upload during document
creation whose warning must survive the navigation to the new document.
The latter two inject server failures with `page.route`; none of the
three makes a model call.

The real AI journey is opt-in. Start `brownie-worker` with the same local
configuration as the API, but with the `brownie_worker` database role,
then run:

```sh
BROWNIE_E2E_INCLUDE_AI=1 npx playwright test golden-path.ai
```

This spends a real OpenAI call using a synthetic transcript. It checks
apply, accept, validation, approval, both browser downloads, the DOCX's
title/date/attendees/action-item content, and the PDF file signature. The
action-item assertions are hard failures: the two commitments in the
transcript must appear in the exported DOCX, and it must not say "No
action items recorded". The DOCX inspection requires `unzip` on PATH. It
does not prove Word layout or PDF text/layout fidelity.

## What the free success-path specs deliberately do not cover

Neither free success-path spec drives the grounded-extraction path (`Try grounded
extraction` on a document's Assist tab, or anywhere the model gateway is
invoked) -- that needs a real `BROWNIE_OPENAI_API_KEY` call and a running
`brownie-worker` process to actually complete the job, and would spend
real money on every run. Content-control-tag detection during template
teaching is unrelated and deterministic (plain DOCX-structure parsing,
no model call), which is why `template-teaching.spec.ts` can drive that
whole flow for real.

One consequence worth naming plainly: because this frontend has no manual
field-value editing UI yet (a document's Content pane only supports
reviewing and locking an existing value, not typing a new one), there is
currently no way to drive a document from empty to a real, populated,
successfully-exported state through the UI alone without either a real
model call or a manual API request outside the browser. `document-
validation-guard.spec.ts` proves the other real, safe half of that same
story instead: an empty document's required fields correctly block
export and the UI never offers to approve one, exercised for real in a
real browser.

## What "automated accessibility scans" means across this repository

Both layers are real and both run in CI-style automation, but they check
different things: the `jest-axe` component tests under `src/**/__tests__`
scan one Vue component's rendered markup in isolation (`jsdom`, no real
layout/rendering engine); the `@axe-core/playwright` scans in this
directory scan a real page in a real browser, with real layout, at each
step of an actual user journey. Neither one substitutes for the other,
and neither substitutes for a real person testing with a real screen
reader -- that part of this phase's own gate has no automated stand-in.
