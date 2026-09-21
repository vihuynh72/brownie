// Copy this file to main.bicepparam, fill in the five values that have no
// sensible default, and keep the copy out of version control: it names your
// subscription's addresses and your machine's public address. The two secrets
// are read from the environment rather than written here at all.
//
//   cp main.example.bicepparam main.bicepparam
//   export BROWNIE_DB_ADMIN_PASSWORD="$(openssl rand -base64 30)"
//   export BROWNIE_HOST_SSH_PUBLIC_KEY="$(cat ~/.ssh/brownie_pilot.pub)"
//
// Then read the change report before creating anything:
//
//   ./scripts/azure-what-if.sh rg-brownie-pilot
//
using './main.bicep'

// Must match the resource group's own region, and must be a region where
// every service in this deployment is available.
param location = 'westus2'

// Becomes part of every resource name. Short: some Azure names are limited
// to 24 characters and this has a unique suffix appended to them.
param namePrefix = 'brownie-pilot'

// Globally unique within the region. The address people will visit is
// https://<this>.<region>.cloudapp.azure.com, and the certificate is issued
// for exactly that name, so changing it later means a new certificate.
param dnsLabel = 'CHANGE-ME-brownie-pilot'

// Your own public address, so that you can reach SSH and write the secrets
// into the key vault. Find it with: curl -s https://api.ipify.org
// Leave it empty to allow neither, and administer the host through the
// portal's serial console instead.
param adminSourceAddressPrefix = 'CHANGE-ME/32'

// The repository whose workflows are allowed to publish an image and restart
// the host. Nothing else can obtain a token, and neither identity may create
// or change a resource.
param githubRepository = 'CHANGE-ME/brownie'
param githubEnvironment = 'pilot'

// GitHub does not always put the repository's NAME in the token it issues;
// for some repositories it puts numeric ids instead, as in
// repo:owner@165195947/name@1358914467:environment:pilot. A deployment that
// trusts only the name form then refuses every workflow with AADSTS700213,
// which reads like a misconfigured credential and is not. Supply both ids and
// both forms are trusted. Find them with:
//   gh api repos/<owner>/<name> --jq '{owner: .owner.id, repo: .id}'
param githubOwnerId = ''
param githubRepositoryId = ''

// Where the budget warnings go. Cost data is hours behind, so these are an
// early warning, not a stop.
param budgetContactEmails = ['CHANGE-ME@example.com']

// The month the budget counts from, as YYYY-MM-01. Set it once, to the month
// you first deploy in, and leave it: without it a deployment in a later month
// asks the service to move an existing budget's start date, which it can
// refuse, failing the whole deployment for a reason that has nothing to do
// with what you were changing.
param budgetStartDate = '2026-09-01'

// The ceiling for everything in this resource group. Set it to what you have
// actually decided to spend, not to what the estimate came to: its whole
// purpose is to tell you when reality and the decision have diverged.
param monthlyBudgetAmount = 60

// Two cores and four gibibytes. Smaller does not hold the virus scanner and a
// render at the same time; larger is the main thing that changes the bill.
param hostSize = 'Standard_B2als_v2'

// Neither of these is written to this file. The password is generated once
// and put into the key vault immediately after the deployment; the public key
// is the half of an SSH key pair that is safe to hand out.
param databaseAdministratorPassword = readEnvironmentVariable('BROWNIE_DB_ADMIN_PASSWORD')
param hostAdminPublicKey = readEnvironmentVariable('BROWNIE_HOST_SSH_PUBLIC_KEY')

// Your own directory object id, so that you can write the four secrets.
// Find it with: az ad signed-in-user show --query id -o tsv
param vaultAdministratorObjectId = ''

// The machine's own preparation comes from infra/azure/host/cloud-init.yaml
// by default, so there is nothing to set here. Override it only to deploy a
// machine that prepares itself differently.
