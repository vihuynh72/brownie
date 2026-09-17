# Brownie

Brownie is a personal project for building and reviewing source-backed
documents.

## Run

Check the pinned local toolchain with:

```sh
./scripts/verify-toolchain.sh
```

Start local Postgres (loopback-only, with the app's own least-privilege
roles already created):

```sh
docker compose -f infra/local/compose.yaml up -d --wait
```

The first time only, copy `.env.example` to `.env` and put any real
values there, never in `.env.example` itself. Then, from the repository
root:

```sh
cp .env.example .env
set -o allexport
source .env
set +o allexport
cd backend && ./mvnw -pl brownie-api spring-boot:run
```

The API listens on 8081, matching `BROWNIE_PUBLIC_ORIGIN` and the callback
registered with the identity provider; health checks answer separately on
8090 (`curl http://localhost:8090/actuator/health`).

Login (`BROWNIE_OIDC_ISSUER`/`CLIENT_ID`/`CLIENT_SECRET`) requires an Entra
External ID tenant and app registration that only the project owner can
create. Until those three are set in `.env`,
`brownie-api` will fail to start on the `local` profile; the `test` profile
used by `mvnw test`/`verify` does not need them. Once configured:

- Start login: open `http://localhost:8081/oauth2/authorization/entra`.
  On the local profile a completed or failed sign-in lands on the web
  app at `http://localhost:5173` (`BROWNIE_WEB_ORIGIN`), so start the
  web app first with `npm run dev` in `apps/web`. A failed sign-in
  arrives there as `/?signin=failed&reason=<code>` and is also logged
  at WARN with the provider's description. Note that
  `BROWNIE_OIDC_ISSUER` must be the tenant's `ciamlogin.com` issuer for
  customer accounts to sign in at all -- see `.env.example`.
- Current identity and workspace memberships: `GET /api/v1/me` (401 until
  logged in). A personal workspace is created automatically the first time
  each identity logs in.
- Log out: `POST /logout` (needs the `XSRF-TOKEN` cookie echoed back as an
  `X-XSRF-TOKEN` header, like any other mutation -- see CSRF below). A
  plain request is redirected to the identity provider's end-session page
  and back; with `Accept: application/json` the response is instead a
  `200` whose `redirectUrl` names that same page, which is how the web app
  finishes signing out after its own `fetch` cannot follow a cross-origin
  redirect.

## Identity

Brownie authenticates one kind of principal: customers signing in through
the Entra External ID tenant configured above. That is the only identity
provider integration in this codebase --
there is no separate staff or admin login path today, and every row in
`user_identity` belongs to a customer.

The Microsoft/Azure account used to administer that tenant (create the
app registration, manage billing, deploy resources) is a different,
workforce identity, used only in the Azure and Entra admin portals,
outside the running application. Brownie's code has no route, no
configuration value, and no database table that references it, and it
must stay that way: a staff-facing feature, if one is ever built, needs
its own separate sign-in path rather than a shortcut through the
customer registration or an elevated `WorkspaceRole`.

Which Spring profile is active can't be influenced from outside
`BROWNIE_ENVIRONMENT` itself: `BrownieEnvironmentListener` sets it as the
*only* active profile before any configuration file is even read,
overwriting whatever else -- a stray `SPRING_PROFILES_ACTIVE` left in the
environment, for instance -- might already be set. A production run
can't end up with the `test` profile's placeholder OIDC credentials
active alongside it, since only one profile is ever active, and
`production`, `pilot`, and `local` all require `BROWNIE_OIDC_ISSUER` with
no fallback value, so a missing real tenant fails startup outright
instead of silently running against nothing.

Stop Postgres, keeping its data for next time:

```sh
docker compose -f infra/local/compose.yaml down
```

Stop it and deliberately discard all local data (also drops the roles
above, since they are only recreated on a truly empty volume):

```sh
docker compose -f infra/local/compose.yaml down -v
```

## End-to-end tests

Real Playwright specs drive the built app in a real Chromium browser against
a running backend. They differ from the component-level `vitest`/`jest-axe`
tests under `src/**/__tests__`, which mount a Vue component in `jsdom` with
every API call mocked. The E2E specs make actual HTTP requests to a
`brownie-api` process talking to Postgres, Azurite, and ClamAV containers.

### Prerequisites

Before running `npm run test:e2e`, from the repository root:

```sh
docker compose -f infra/local/compose.yaml up -d --wait
cd backend && ./mvnw -pl brownie-api spring-boot:run
```

The `.env` setup above is required. `playwright.config.ts` starts the Vite
dev server itself; it does not start Postgres, Azurite, ClamAV, or
`brownie-api`.

### Sign-in in E2E tests

This app has no in-app login form. Signing in is a full redirect to a real
Entra External ID tenant, which these tests do not have credentials to drive
through, and no test double stands in for it. `global-setup.ts` instead calls
`TestSupportAuthController`'s `/test-support/sessions` route, which creates
the same kind of session as a real login (including provisioning and cookies)
without an identity-provider round trip. That controller exists only when
`BROWNIE_ENVIRONMENT=local` or `test`; it is not reachable against a `pilot`
or `production` deployment.

### Coverage and the paid golden path

The default run (`npm run test:e2e`) covers template teaching, the empty-
document export safety gate, and `session-and-recovery.spec.ts`: real sign-
out (the server must answer 401 afterwards, and the page continues to the
identity provider's end-session URL, with that external hop stubbed), recovery
from a failed identity request, and a failed optional source upload during
document creation whose warning must survive navigation to the new document.
The latter two inject server failures with `page.route`; none makes a model
call.

The real AI journey is opt-in. Start `brownie-worker` with the same local
configuration as the API, but with the `brownie_worker` database role, then
run:

```sh
BROWNIE_E2E_INCLUDE_AI=1 npx playwright test golden-path.ai
```

This spends a real OpenAI call using a synthetic transcript. It checks apply,
accept, validation, approval, both browser downloads, the DOCX's title, date,
attendees, and action-item content, plus the PDF file signature. The DOCX
inspection requires `unzip` on `PATH`. It does not prove Word layout or PDF
text/layout fidelity.

### Deliberate E2E boundaries

The free success-path specs do not drive grounded extraction, because it needs
a real `BROWNIE_OPENAI_API_KEY` call and a running `brownie-worker` process.
Content-control-tag detection during template teaching is deterministic DOCX
structure parsing, so `template-teaching.spec.ts` can exercise that flow
without a model call.

There is no manual field-value editing UI yet. A document's Content pane can
review and lock an existing value, but cannot type one. An empty document
therefore cannot reach a real populated and successfully exported state
through the UI alone without a real model call or a manual API request outside
the browser. `document-validation-guard.spec.ts` covers the other safe half:
required fields block export and the UI does not offer approval.

### Automated accessibility scans

Both layers run in CI-style automation but cover different things. The
`jest-axe` component tests under `src/**/__tests__` scan a component's markup
in isolation with `jsdom`; the `@axe-core/playwright` scans in `e2e/` scan a
real page in a browser with real layout during a user journey. Neither
substitutes for screen-reader testing by a person.
