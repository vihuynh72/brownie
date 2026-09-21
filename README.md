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

`brownie-worker` carries out what the API only records: it deletes what
has been in the trash too long, removes the files of anything deleted for
good, tidies unused files, and runs model jobs. Start it in a second
terminal, with the same `.env` but its own database login:

```sh
set -o allexport
source .env
set +o allexport
cd backend
export JAVA_HOME="$PWD/../.toolchains/jdk-21.0.12.1+1/Contents/Home"
BROWNIE_DB_USERNAME=brownie_worker BROWNIE_DB_PASSWORD=brownie_worker_local_only \
  ./mvnw -pl brownie-worker spring-boot:run
```

After pulling or changing code, stop both, run `./mvnw -q -DskipTests
clean install` again, and start both. A page that answers "the Brownie
server that answered is older than this page" means the API was not
restarted; reloading the page cannot fix that.

`Web server failed to start. Port 8081 was already in use.` means an older
API is still running, often from a terminal that has since been closed.
Find it and stop it by process id:

```sh
lsof -nP -iTCP:8081 -sTCP:LISTEN          # which process, since when
kill $(lsof -nP -iTCP:8081 -sTCP:LISTEN -t | sort -u)
```

The API applies any new database migrations as it starts, and it does that
*before* it takes the port: a start that then fails on the port has already
migrated the database, so the next start finds nothing left to apply.

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

## Trash, deletion, and file housekeeping

A document's row on the home page has a "Move to the trash" action. A
trashed document answers on no route and accepts no change, its unfinished
jobs are cancelled, and the Trash Bin restores it exactly as it was for
`BROWNIE_TRASH_RETENTION_DAYS` days (30 when unset; the web app reads the
number from `GET /api/v1/capabilities`). "Delete forever" in the Trash Bin,
or that period running out, deletes it for good: every database row that
belongs to it is removed in one transaction, so access ends the moment the
request returns, and the object keys of its files (compiled and validated
output, job output, the per-run objects, and any source file nothing else
uses) are queued. `POST /api/v1/workspaces/{id}/deletions` with
`{"scope":"WORKSPACE"}` deletes the caller's own workspace the same way,
with the caller's identity record, and ends every session of that person;
signing in again starts a new, empty workspace. In the web app that is the
"Delete everything" part of the **Your data** page: it asks for the phrase
`delete my workspace` to be typed, deletes, and lands on the sign-in page
with a line saying it worked.

**Your data** (in the sidebar) is where a person reads what Brownie keeps,
for how long, and who else sees any of it: the model provider and model
name, that sign-in is Microsoft's, that every upload is scanned. Every
period on it comes from `GET /api/v1/data-practices`, which reads the same
settings the worker acts on, so the page cannot promise a period the system
does not keep. It also shows this month's model use against the workspace's
allowance and the kinds and size of file accepted. `BROWNIE_SUPPORT_CONTACT`
is who the page tells people to ask; unset, it says nobody has been named
yet.

What remains afterwards is the deletion ledger (`deletion_request`,
`deletion_blob_task`): ids, states, times, counts, and opaque object keys,
never a title or a filename. No application database login can write it;
both runtime roles reach it only through database routines.

`brownie-worker` is the only process that removes a deleted document's
stored files. Every 30 seconds it carries out trash whose time has run
out, removes queued objects, and marks a request `VERIFIED` once a recount
finds no row and no queued object left. With no worker running, a document
deleted for good is already unreadable, and its files wait in the queue
until a worker starts. Every ten minutes the worker also tidies files
nobody will come back to: an upload abandoned for a day is refused, a scan
cut short by a crash is returned to the state it can be retried from,
refused or quarantined files lose their stored bytes after a day (the row
stays as a record), and a ready file that nothing has referred to for a
day is refused and removed. The periods are the
`brownie.worker.retention.*` properties. Four of them are what the
**Your data** page tells people, so they are set through one environment
variable each that both programs read: `BROWNIE_RETENTION_ABANDONED_UPLOAD`,
`BROWNIE_RETENTION_REFUSED_FILE`, `BROWNIE_RETENTION_UNUSED_FILE` and
`BROWNIE_RETENTION_AUDIT_RECORD` (ISO-8601 durations such as `PT24H` or
`P90D`), beside `BROWNIE_TRASH_RETENTION_DAYS`. Setting the worker's
property directly would change what happens without changing what people
are told.

## Model allowance, audit record, and operating the system

**What a model request may cost.** Every request to the model is written
to a ledger (`model_usage`) before it is sent: which workspace, which
person, which run, the model, the prompt version, and the rate card the
estimate was priced with. The amount held is released when the answer
comes back and the real token counts replace it. A request that would go
past an allowance is refused before it is sent, so a limit is never
discovered by overspending it. There are three allowances, all
configuration (see `.env.example`): one run (6 requests, $0.10, counted
across every attempt and resume of that run's job), one workspace for the
calendar month in UTC ($2.00), and every workspace together for the month
($15.00). One attempt is also held to 40,000 input and 8,000 output
tokens, which are not configuration. The amount held for a request is its
whole input, response schema included, at a quarter of a token per ASCII
character and a token and a half per character of anything else, plus the
most it may write back. `brownie-api` refuses an Assist rewrite, and a new
extraction run whose first request would not fit, with
`429 USAGE_LIMIT_REACHED`, and says which allowance; a replay of a start
it already accepted still gets that start's receipt. `brownie-worker` ends
a run with a usage code instead of retrying it. `GET
/api/v1/workspaces/{id}/usage` reports a workspace's own month and only
whether the shared allowance is used up, never what anyone else spent. A
held amount whose request never reported back is kept as spent after 30
minutes, which errs on the side of the budget. A network failure, a 429
or a 5xx from the provider is retried at most twice with a growing,
jittered pause inside the same attempt, each try reserved like any other
request; a retry the allowance will not pay for is not made, and the run
is tried again later as the transient failure it was. A refusal, a
malformed answer or a cancelled run is not retried. The provider's own
client is configured to send each request once
(`spring.ai.openai.max-retries: 0`), so nothing reaches the provider that
the ledger did not reserve first.

**The audit record.** `audit_event` holds the actions someone may later
need to account for: a document trashed, restored, or deleted for good; a
workspace deleted; a document exported; a job started again by hand; a
support grant given or revoked. A row is ids, an action, a time, the
request's correlation id and a few counts or codes, never a title, a
filename or a field value, and it is written in the same transaction as
the action it describes. Neither application login can change or remove a
row. The worker removes rows older than 90 days
(`brownie.worker.retention.ledger-maintenance.audit-retention`).

**Starting a run again.** `POST /api/v1/workspaces/{id}/jobs/{jobId}/retry`
puts a job that ended `DEAD` or `FAILED` back in the queue with a fresh run
of attempts and a new deadline; the Assist tab offers it as "Try this run
again". It refuses a job whose document has changed or is in the trash
since the job began (`409 JOB_TARGET_STALE`), because its result could
never be accepted; start a new extraction instead.

**Support grants.** A workspace's owner can record permission for support
to act in that workspace, for `METADATA` or `CONTENT`, for one to seven
days, and take it back while it is open
(`/api/v1/workspaces/{id}/support-grants`).
A grant holds no free text. Nothing in the application reads a workspace
on support's behalf today; the grant is the record such access must find
first. The web app has no screen for these routes, by decision: until
something reads a grant, a screen for granting would promise access control
that does not exist yet.

**How the system is doing.** One query, run as the database owner, answers
with a single row of counts and no content: queued, running, waiting and
recently dead jobs; the age of the oldest queued job; leases that have run
out; open and overdue trash; deletions that failed or await verification;
stored files still queued for removal; uploads, long scans and quarantined
files; this month's model requests and cost; stale and unsettled
reservations; open support grants; and the last day's audit events. No
application login may run it.

```sh
docker compose -f infra/local/compose.yaml exec postgres \
  psql -U brownie_migration -d brownie -x -c "SELECT * FROM operations_summary()"
```

## Backup, restore, and when something it depends on is down

**A backup is never the last step.** A backup holds everything as of the
moment it was taken, including whatever is deleted for good afterwards,
so restoring one brings deleted documents back. To make a deletion survive
that, `brownie-worker` copies every carried-out deletion out of the
database, into a blob container of its own (`deletion-ledger`, one small
JSON object per deletion: ids, times and counts, nothing a person wrote),
and a deletion is only marked `VERIFIED` once that copy exists. Started
with `BROWNIE_WORKER_MODE=replay-deletions`, the worker does one thing and
ends: it applies every recorded deletion to the database it is pointed at,
prints `DELETION_REPLAY entries=… replayed=… absent=… pending=…`, and exits
0 only if nothing is left pending. In that mode nothing on a timer runs, so
it cannot claim a job from a database that still holds work for documents
it is about to remove. An entry names what it removed by id and by the
moment it was created, and is applied to exactly that and nothing else: a
restore also winds id sequences back, so an id alone can come to mean a
different document, and one that merely shares a deleted one's id is never
touched.

```sh
./scripts/backup-local.sh /path/to/new-directory
./scripts/restore-drill-local.sh /path/to/that-directory
```

`backup-local.sh` dumps the database while it runs and copies the blob
store's files, stopping the blob store for the few seconds that takes.
`restore-drill-local.sh` rehearses a restore without touching the
development stack: a throwaway Postgres, the dump restored into it, every
session ended, the deletion record applied, a report of what changed, and
the throwaway removed (`--keep` leaves it up, and so does a replay that did
not finish). It needs `brownie-worker` built, and refuses a jar built
before the worker could replay, because that worker would ignore the mode
and start serving. It reads the deletion record from the development
stack's blob store unless `BROWNIE_LOCAL_STORAGE_CONNECTION` names another;
that must be the store of the stack the backup came from. A real restore
follows the same order, and the API is started only after the replay has
exited 0. Restoring the blob store as well puts the
deletion record back in time with it; deletions made after that backup are
then unknown to the replay, which is why a hosted deployment has to keep
that container somewhere a restore of the rest does not reach.

**When a dependency is down**, a person is told so and nothing restarts:

| What is down | What a request gets | Afterwards |
| --- | --- | --- |
| Database | `503 DATABASE_UNAVAILABLE` with `Retry-After: 5`, from a filter outside everything else, because the session lookup is the first thing to fail; management health answers 503 | Both services reconnect by themselves |
| Blob store | `503 STORAGE_UNAVAILABLE` within about a second on everything that stores or reads a file (uploads, downloads, previews, reading a source, starting or resuming a run, preparing, checking or exporting a document, activating a template); everything that needs only the database keeps working | The same request succeeds; the worker's sweeps run again on their next pass |
| Virus scanner | `503 SCANNER_UNAVAILABLE` on completing an upload, and on anything that stores a file Brownie made itself (it is scanned too); the file stays `QUARANTINED` and cannot be read | Completing the upload again scans it; someone who first signed in during the outage, or while the renderer was down or busy, gets their built-in templates finished on their next sign-in |
| Model provider | A network failure, 429 or 5xx is retried inside the run and then by the queue; a rejected key ends the run at once as `DEAD` with `MODEL_TRANSPORT_REJECTED`, and what was held for the request stays counted | Once the key is right, "Try this run again" (or the retry route) restarts the same run |

**Changing a credential.** The API's and the worker's database passwords:
`ALTER ROLE … PASSWORD …` as the database's administrator, put the new
value in the process's environment, restart it. A running process keeps
working on the connections it already holds and fails as they are
replaced, so restart promptly; started with the old password it refuses to
start. Sessions live in the database and survive the restart. To end every
session at once, `TRUNCATE spring_session CASCADE` as the database owner;
every browser is signed out on its next request. The model key and the
sign-in client secret are changed at their providers, then in the
environment, then both processes are restarted, and only then is the old
value revoked.

## Deploying it somewhere other than a laptop

Everything below is optional: Brownie runs locally with nothing deployed, and
nothing here happens by itself. Publishing an image and deploying it are both
started by hand.

**The shape.** One small Linux machine runs four containers -- a proxy that is
the public face, the API, the worker, and the virus scanner -- beside a
managed PostgreSQL server that has no public address, two blob storage
accounts, a key vault, a container registry and a log workspace. Rendering
stays what it already is: the API starts a throwaway container per render,
with no network, a read-only root and a memory limit, and throws it away. That
is why this is a machine rather than a managed container platform, which gives
a container no runtime of its own to do that with.

The proxy is what makes the application and the API one origin, which the
session cookie and the sign-in callback both depend on. It terminates TLS with
a certificate issued for the machine's own `<label>.<region>.cloudapp.azure.com`
name, renewed daily by a timer, and it passes the forwarded scheme, host and
caller address from what it observed rather than from what the caller claimed.

**What is where.**

| Path | What it is |
| --- | --- |
| `infra/azure/main.bicep` and `infra/azure/modules/` | Every resource, identity, network rule and budget, deployed against one resource group |
| `infra/azure/main.example.bicepparam` | Copy to `main.bicepparam` and fill in; it is ignored by git because it names your own addresses |
| `infra/azure/host/cloud-init.yaml` | How the machine prepares itself, once, when it is created |
| `infra/azure/host/compose.yaml` | What runs on it |
| `infra/azure/host/deploy.sh` | Runs on the machine: reads the secrets, pulls the images, migrates, starts, checks, rolls back if the check fails |
| `scripts/azure-what-if.sh` | Prints what a deployment would change, and changes nothing |
| `scripts/azure-deploy.sh` | Creates or updates the resources. This one spends money |
| `scripts/verify-deployment.sh` | Checks a running deployment from outside, over its public address |
| `scripts/verify-deployment-on-host.sh` | Checks what only the machine can see, including that the renderer really is confined |
| `.github/workflows/publish-images.yml` | Builds and pushes the four images, by digest, and scans them |
| `.github/workflows/deploy-pilot.yml` | Puts a published revision on the machine, or puts the previous one back |
| `.github/workflows/pilot-hours.yml` | Starts the machine and the database in the morning, stops them at night |

**Setting it up, once.**

1. Sign in with the Azure CLI and select the subscription you intend.
2. `cp infra/azure/main.example.bicepparam infra/azure/main.bicepparam` and
   fill in the five values it asks for. Export the two secrets it reads from
   the environment (a generated database password, your SSH public key).
3. `./scripts/azure-what-if.sh <resource-group>` and read the report.
4. `./scripts/azure-deploy.sh <resource-group>`. Creating the role
   assignments needs a role that can grant roles (Owner, or User Access
   Administrator alongside Contributor); plain Contributor gets partway and
   stops.
5. Put five secrets in the key vault it created: `db-api-password`,
   `db-worker-password`, `db-migration-password`, `oidc-client-secret`,
   `openai-api-key`. The machine reads them at start-up and stores none of
   them.
6. Create the three database roles on the new server, from the machine, using
   `infra/local/postgres/init/01-app-roles.sql` as the shape, with the
   passwords you just stored. The server's own administrator exists to do this
   and nothing else.
7. Add the deployed callback `https://<name>/login/oauth2/code/entra` to the
   identity provider's app registration, beside the local one.
8. Set the repository variables the workflows read (`AZURE_TENANT_ID`,
   `AZURE_SUBSCRIPTION_ID`, `AZURE_CLIENT_ID_PUBLISH`, `AZURE_CLIENT_ID_DEPLOY`,
   `AZURE_RESOURCE_GROUP`, `BROWNIE_REGISTRY`, `BROWNIE_VM_NAME`,
   `BROWNIE_DB_SERVER_NAME`, `BROWNIE_SERVER_NAME`, `BROWNIE_VAULT_NAME`,
   `BROWNIE_DB_HOST`, `BROWNIE_STORAGE_ENDPOINT`,
   `BROWNIE_DELETION_RECORD_ENDPOINT`, `BROWNIE_OIDC_ISSUER`,
   `BROWNIE_OIDC_CLIENT_ID`, `BROWNIE_INVITED_ADDRESSES`,
   `BROWNIE_SUPPORT_CONTACT`, `BROWNIE_CERTIFICATE_CONTACT`), and create a
   GitHub environment named `pilot`. None of these is a secret; there is no
   Azure credential in this repository at all, because the two identities are
   federated to this repository and that environment.

**Releasing.** Run *Publish images*, note the revision it reports, then run
*Deploy to the pilot host* with that revision. The deployment migrates the
database with the new image before anything serves it, so a failed migration
leaves the running release untouched; then it replaces the containers, waits
for them to be healthy, issues or renews the certificate, and checks the
result from outside. If the check fails it puts the previous images back. The
same workflow's `rollback` choice does that on demand.

**Who can sign in.** The identity provider's own sign-up is open to anyone who
reaches the address, so a deployed Brownie requires an invitation:
`BROWNIE_INVITED_ADDRESSES` is the list of whole addresses that may sign in,
and it is on by default wherever this is deployed. With the gate on and nobody
listed, nobody signs in, including you. Someone who is refused is told plainly
that this Brownie is open to invited people only and that trying again will
not change it.

**Hours.** The machine and the database are the only charges of any size
billed by the hour, and running them only while people are actually using
Brownie is most of the difference between fitting a small budget and not. Set
the repository variable `PILOT_HOURS` to `on` and the schedule in
`pilot-hours.yml` starts them in the morning and stops them at night; the
times in that file are UTC, and they are the hours to publish to the people
invited. Outside them the address does not answer, which is why it is a
published hour rather than a surprise.

**What to run after deploying.**

```bash
BROWNIE_BASE_URL=https://<name> ./scripts/verify-deployment.sh
# and, on the machine itself:
sudo ./scripts/verify-deployment-on-host.sh
```

The first checks TLS, the redirect from plain HTTP, the security headers, that
the API is reached under the same address, that nothing answers without a
session, that the management endpoint is not public, that a sign-in asks the
provider to return to this address over HTTPS, and that one caller cannot make
unlimited requests. The second checks that every container is healthy, that
both programs are ready (their readiness includes the database, the blob store
and the scanner), that the renderer image is the approved one and that a
renderer container has no network, cannot write outside its own temporary
space, is not root and is given neither our configuration nor a container
runtime.

**What those scripts cannot check, and you must.** Sign in with a real
account, upload a file, generate a draft, edit it, export it, delete the
document, and confirm the trash behaves. Do it with two unrelated accounts and
confirm neither sees the other's documents. Those need a person and a real
sign-in, and they are the last step before anyone is invited.

**Operating it.** `pilot_summary()` answers what the invited group actually
did -- how many came back on a second day, how many documents were started and
exported, how often a person rewrote what the model proposed, what failed and
what it cost -- and `operations_summary()` answers whether anything is stuck.
Both are queries an operator runs; no route serves them and neither runtime
role may execute them.

```bash
psql -U brownie_migration -d brownie -x -c "SELECT * FROM pilot_summary(30)"
```

## Limits that protect the host and the people on it

**What an upload must be.** A file is classified by its bytes, never by
its name or a declared type, and is quarantined until it has been scanned.
A Word package is walked before any parser sees it: at most 500 parts and
200 MiB expanded; no part named twice (two readers could otherwise
disagree about which one is the document, and part names differ only by
case); no part that declares a document type, which is the door to entity
expansion and external entities; no part nested more than 256 elements
deep; no package that has expanded more than 200 to 1 past 8 MiB, asked
both of the package as a whole and of the parts that each expanded that far
added together (so that neither something incompressible put in front, nor
cutting the same content into many small parts, hides it); and no
relationship that points at something on a network other than as an
ordinary hyperlink, which is how a document asks whoever opens it to fetch
a remote template or object. What counts as local is a short list (a
`file:` address with one or three slashes, a drive path, a bare relative
path) and everything else counts as a network, because the ways of naming
a network location are open-ended. A relationship to a path on the
author's own disk is left alone, because nearly every document written in
Word has one. The parts read this way are the ones named as XML (`.xml`,
`.rels`, and the two endings the .NET packaging library uses); anything
else is read only by the document library, which refuses a document type
and entity expansion itself.

A PDF is read only if it really has at most 200 pages (they are counted,
not taken from what the file declares), and then under a budget that is
charged as the reading goes: every time a page's content, a form or a
font is about to be opened it is first expanded with nothing kept, and its
size counted, so a form drawn a thousand times costs a thousand times.
Past 128 MiB in total, two million characters, or 250,000 characters on
one page, the whole file is refused, with the reason recorded, and none of
it is kept. The rendered PDF that comes back from the sandbox is read under
the same budget. What this does not bound is the PDF library opening the
file in the first place: the file's own index may be compressed, and the
library expands that in memory before anything here can count it. Uploads
are limited to 10 MiB, by signed-in people only, which is what stands in
front of that today.

**What one person may ask for in a minute.** Counted per signed-in person
(per address for anyone not signed in, an IPv6 address by its first 64
bits), in this process's memory, which is
the right size for one API instance: 30 requests that start paid model
work, 30 that start a render, 120 upload requests, 300 other changes, 1,200
reads, and 120 of anything when not signed in
(`brownie.rate-limit.per-minute.*`; `brownie.rate-limit.enabled=false`
turns it off). Past that the answer is `429 RATE_LIMITED` with
`Retry-After`. The limiter runs inside the sign-in filter chain, straight
after the session has said who is asking, so the requests that chain
answers by itself (starting a sign-in, a request with no session, a failed
CSRF check) are counted as well; only health checks and the error page are
not. The event stream counts as a read, and is also limited by how many
one person may have open. Any request body over 1 MiB
(`brownie.web.max-body-bytes`), whatever it calls itself, is answered
`413 CONTENT_TOO_LARGE`; only the route that receives a file is exempt,
and it keeps its own, larger limit. Form bodies, which the server reads
itself, are held to the same size by the server's own setting, and
multipart bodies are not accepted at all, since no route takes one.

**Renders.** At most two renders run at once (`brownie.render.max-concurrent`);
others wait their turn, in order, for up to 20 seconds
(`brownie.render.max-wait`) and are then answered `503 RENDERER_BUSY`.
The renderer's image is looked up once per render and run by its content
address, which is also written into the record of what produced the
output; set `brownie.render.expected-image-id` to a `sha256:` id and no
other image is ever run. What the sandbox leaves behind is read as if the
sandbox had been taken over: a symbolic link is never followed, only a
plain file is accepted, no more than the quota is read, and it has to
begin like a PDF.

**Repeats.** Approving again what is already the document's latest
approval, or exporting the same approval a second time, answers with the
record that already exists: no second approval, receipt or audit entry.
Going back to an earlier choice of format is a new decision and a new
approval, because the latest approval is what gets exported. A session
whose person no longer has an identity record is answered
`401 SESSION_NO_LONGER_VALID` and ended by the first route that looks the
person up, which is every route but the two that describe the service
itself (`/api/v1/capabilities` and `/api/v1/data-practices`).

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

`trash-and-deletion.spec.ts` takes one document through its whole removal:
off the home list into the Trash Bin, its own address answering 404, back
out exactly as it was, then deleted for good after one confirmation by
name, with the server's ledger showing the deletion and no title. The
stored files are removed by `brownie-worker`, so that part is covered by
the worker's own integration test rather than by the browser.

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
