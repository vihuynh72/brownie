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
cd backend
export JAVA_HOME="$PWD/../.toolchains/jdk-21.0.12.1+1/Contents/Home"
./mvnw -q -DskipTests install
./mvnw -pl brownie-api spring-boot:run
```

`install` builds every module and puts the result in your local Maven
cache; the run command on its own only rebuilds `brownie-api` and takes
`brownie-core`, `brownie-storage` and `brownie-ai` from that cache, so
run `install` again whenever any of those change (a "class path resource
... cannot be opened" error at startup is the sign that you skipped it).
The pinned JDK lives in `.toolchains/`; a newer system Java starts the
app too, but everything here is built and tested on 21.

Keep the clone outside any cloud-synced folder (iCloud Drive's Desktop
and Documents, OneDrive, Dropbox). A build writes thousands of files under
`target/`, and a sync client answers with conflict copies named
`something 2`; a duplicated `BrownieApiApplication 2.class` makes the run
fail with "Unable to find a single main class". If that happens, delete
every `* 2` copy, run `./mvnw clean`, then `install` again.

The API listens on 8081, matching `BROWNIE_PUBLIC_ORIGIN` and the callback
registered with the identity provider; health checks answer separately on
8090 (`curl http://localhost:8090/actuator/health`).

Login (`BROWNIE_OIDC_ISSUER`/`CLIENT_ID`/`CLIENT_SECRET`) requires an Entra
External ID tenant and app registration that only the project owner can
create. Until those three are set in `.env`,
`brownie-api` will fail to start on the `local` profile; the `test` profile
used by `mvnw test`/`verify` does not need them. Once configured:

- Start login: open `http://localhost:8081/oauth2/authorization/entra`,
  or use the web app's own `/signin` page, which is where every address
  that needs a workspace sends a signed-out visitor. That page remembers
  which address interrupted them, in this browser tab only, and goes on
  to it once the session exists -- the callback itself always lands on
  the home address, so the destination cannot travel through the
  provider round trip in the URL.
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
cd backend && ./mvnw -q -DskipTests install && ./mvnw -pl brownie-api spring-boot:run
```

The `.env` setup above is required. `playwright.config.ts` starts the Vite
dev server itself; it does not start Postgres, Azurite, ClamAV, or
`brownie-api`. To run the suite beside an API and dev server you already
have open, start a second API on other ports (`SERVER_PORT=18081
MANAGEMENT_SERVER_PORT=18090`) and set `BROWNIE_API_ORIGIN` (the dev
server's proxy target), `BROWNIE_E2E_BACKEND_ORIGIN` (session seeding) and
`BROWNIE_E2E_WEB_PORT` (a free dev-server port) for the run.

### Sign-in in E2E tests

This app has no in-app login form. Signing in is a full redirect to a real
Entra External ID tenant, which these tests do not have credentials to drive
through, and no test double stands in for it. `global-setup.ts` instead calls
`TestSupportAuthController`'s `/test-support/sessions` route, which creates
the same kind of session as a real login (including provisioning and cookies)
without an identity-provider round trip. That route exists only when
`BROWNIE_ENVIRONMENT=local` or `test` *and* `BROWNIE_TEST_SUPPORT_TOKEN` is
set; it then requires that same value in an `X-Test-Support-Token` header and
answers only loopback clients. Put any random string in your ignored `.env`
(see `.env.example`), start `brownie-api` with it, and export it in the shell
that runs Playwright; `global-setup.ts` refuses to run without it. On the
`local` profile the API also listens on `127.0.0.1` only. None of this is
reachable against a `pilot` or `production` deployment.

The seeded subject defaults to `e2e-playwright`, which reuses the same
workspace every time. Set `BROWNIE_E2E_SUBJECT` to something new to get a
fresh workspace instead. That is worth doing after the Azurite container has
been recreated: the old workspace's stored template bytes lived in the
container that went away, so validation and export fail on it with a stored
content error until a sign-in repairs the built-in templates.

### Coverage and the paid golden path

The default run (`npm run test:e2e`) covers template teaching, the empty-
document export safety gate, a document filled in entirely by hand and
exported (`manual-editing.spec.ts` reads the typed values back out of the
downloaded DOCX), and `session-and-recovery.spec.ts`: real sign-out (the
server must answer 401 afterwards, and the page continues to the identity
provider's end-session URL, with that external hop stubbed), recovery from a
failed identity request, and a failed optional source upload during document
creation whose warning must survive navigation to the new document. The
latter two inject server failures with `page.route`; none makes a model call.

`shell-and-auth.spec.ts` covers the navigation around every page: a
signed-out visitor following the upload action or the trash bin is sent to
`/signin` carrying where they were going, and is returned there once a
session exists (the provider hop is stubbed, the session is a real one);
the sidebar collapses, stays collapsed across a reload, and reopens; at
phone width it becomes a drawer that opens, closes on Escape, returns focus
to its own button, and never makes the page scroll sideways.

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

A document's Content pane edits every field the template defines, including
repeated rows, and saves a moment after typing stops (or on "Save now") as a
new revision against the exact revision the page last loaded, with saving,
saved, and conflict states and a warning before leaving with unsaved work;
`manual-editing.spec.ts` drives that from an empty document to a downloaded
export, and `document-validation-guard.spec.ts` covers the other half: an
empty document's required fields block export and the UI does not offer
approval. The Rules tab lists, read-only, the accepted rules of the
document's template version; `GET /api/v1/capabilities` reports the upload
limit and supported formats, which every upload control shows before a file
is chosen.

The Assist tab has a composer for a few bounded requests: draft from the
attached sources, change a field to a value, shorten or rewrite a text
field, explain a validation finding. `POST .../assist/interpret` reads the
text into one command and reports its scope (the field and what it holds,
or the finding) without doing anything; `POST .../assist/execute` then
runs exactly that against the revision on screen. A change or a rewrite
comes back as a patch proposal for the ordinary accept step, an
explanation is text, and free text is answered with what Brownie can do.
Only rewrite and explain call the model, one bounded call each.

Beside the editor, a Preview pane draws the latest compiled PDF of the
document with PDF.js (`pdfjs-dist`), page by page; it picks up whatever
compilation already exists for the current content (validation compiles
too), and "Generate preview" renders one on demand through the isolated
renderer. A value Assist filled carries an Evidence marker that opens the
cited passage from the attached source through the document's own
evidence route; Brownie can show where a value came from, not yet where it
lands on the page.

### Automated accessibility scans

The two layers cover different things. The `jest-axe` component tests under
`src/**/__tests__` scan a component's markup in isolation with `jsdom` and
run in CI with the rest of the unit suite (`npm test`); the
`@axe-core/playwright` scans in `e2e/` scan a real page in a browser with real
layout during a user journey and run only locally, because CI does not yet
start the backend stack the browser suite needs. Neither substitutes for
screen-reader testing by a person.

## Backend tests

`./mvnw -B clean verify` in `backend/` runs every module's tests. Most of
`brownie-api`'s and `brownie-worker`'s tests start real Postgres, Azurite, and
ClamAV containers through Testcontainers, so Docker must be running, and the
compile, validation, and export tests render through the pinned isolated
LibreOffice image, which a fresh clone has to build once first:

```sh
docker build -t brownie-spike-renderer:pinned spike/docx-binding/render
```

CI builds that same image on every run before it runs the backend suite.
Use `clean` rather than a bare `test`: the multi-module build does not
reliably notice a dependency module's stale compiled classes, and an
incremental run can fail on code that is actually correct.
