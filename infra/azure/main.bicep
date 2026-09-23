// Everything Brownie needs in order to run somewhere other than a laptop,
// for a small invited group of people.
//
// Deployed against one resource group. Nothing here is created anywhere else,
// and nothing outside the group is changed, so the whole deployment can be
// removed by deleting the group -- with the two exceptions that survive on
// purpose: a key vault with purge protection is recoverable for ninety days,
// and the deletion record's container refuses to be changed inside its
// retention window. Both are deliberate.
//
// Read in order: the network comes first because the database and the host
// both sit inside it; storage, the vault and the registry are independent of
// each other; the host comes after the network; access is last because it
// needs the host's identity to exist before it can grant it anything.
//
// The identities are federated to GitHub but are deliberately not allowed to
// create resources. Provisioning is something the owner does from their own
// machine, after reading a change report.

targetScope = 'resourceGroup'

@description('Azure region. Every resource is created here.')
param location string = resourceGroup().location

@description('Prefix shared by every resource name. Lower case, letters and hyphens.')
@minLength(3)
@maxLength(16)
param namePrefix string = 'brownie-pilot'

@description('Administrative user name on the host.')
param hostAdminUsername string = 'brownieadmin'

@description('Public SSH key for that user.')
param hostAdminPublicKey string

@description('A single address allowed to reach SSH, in CIDR form. Empty means nothing may.')
param adminSourceAddressPrefix string = ''

@description('Label that becomes <label>.<region>.cloudapp.azure.com: the address people visit and the name the certificate is issued for. Globally unique within the region.')
param dnsLabel string

@description('Administrator login for the database server. Not an application account.')
param databaseAdministratorLogin string = 'brownieadmin'

@description('Administrator password for the database server. Passed at deployment time; never written to a parameter file.')
@secure()
param databaseAdministratorPassword string

@description('Object id of whoever administers the vault contents. Empty grants nobody here.')
param vaultAdministratorObjectId string = ''

@description('GitHub repository in owner/name form whose workflows may publish images and restart the host.')
param githubRepository string

@description('GitHub environment a workflow must run in to obtain a token.')
param githubEnvironment string = 'pilot'

@description('The numeric owner and repository ids GitHub may put in its token subject. Without them a deployment trusts only the plain-name form, and a workflow whose token carries ids is refused with AADSTS700213. Find them with: gh api repos/<owner>/<name> --jq "{owner: .owner.id, repo: .id}".')
param githubOwnerId string = ''
param githubRepositoryId string = ''

@description('Monthly ceiling for this resource group, in the billing currency.')
param monthlyBudgetAmount int = 60

@description('Addresses that receive budget warnings.')
param budgetContactEmails array

@description('First day of the month the budget counts from, as YYYY-MM-01. It defaults to the month of this deployment, which is right for a first one; set it in the parameter file so later deployments do not ask to move it.')
param budgetStartDate string = utcNow('yyyy-MM-01')

@description('Cloud-init document for the host, base64 encoded. It defaults to the one in this repository, which prepares the machine and starts nothing: at first boot no image has been published yet.')
param hostCloudInitBase64 string = loadFileAsBase64('host/cloud-init.yaml')

@description('Host size. Changing this is the main way to change what hosting costs.')
param hostSize string = 'Standard_B2als_v2'

var tags = {
  application: 'brownie'
  environment: 'pilot'
  owner: githubRepository
  purpose: 'invite-pilot'
}

module network 'modules/network.bicep' = {
  name: 'network'
  params: {
    tags: tags
    location: location
    namePrefix: namePrefix
    adminSourceAddressPrefix: adminSourceAddressPrefix
  }
}

module storage 'modules/storage.bicep' = {
  name: 'storage'
  params: {
    tags: tags
    location: location
    namePrefix: namePrefix
    appSubnetId: network.outputs.appSubnetId
  }
}

module vault 'modules/vault.bicep' = {
  name: 'vault'
  params: {
    location: location
    namePrefix: namePrefix
    appSubnetId: network.outputs.appSubnetId
    administratorObjectId: vaultAdministratorObjectId
    administratorSourceAddressPrefix: adminSourceAddressPrefix
    tags: tags
  }
}

module registry 'modules/registry.bicep' = {
  name: 'registry'
  params: {
    tags: tags
    location: location
    namePrefix: namePrefix
  }
}

module observability 'modules/observability.bicep' = {
  name: 'observability'
  params: {
    tags: tags
    location: location
    namePrefix: namePrefix
  }
}

module data 'modules/data.bicep' = {
  name: 'data'
  params: {
    tags: tags
    location: location
    namePrefix: namePrefix
    delegatedSubnetId: network.outputs.databaseSubnetId
    privateDnsZoneId: network.outputs.databaseDnsZoneId
    administratorLogin: databaseAdministratorLogin
    administratorPassword: databaseAdministratorPassword
  }
}

module host 'modules/host.bicep' = {
  name: 'host'
  params: {
    tags: tags
    location: location
    namePrefix: namePrefix
    appSubnetId: network.outputs.appSubnetId
    vmSize: hostSize
    adminUsername: hostAdminUsername
    adminPublicKey: hostAdminPublicKey
    dnsLabel: dnsLabel
    cloudInitBase64: hostCloudInitBase64
  }
}

module access 'modules/access.bicep' = {
  name: 'access'
  params: {
    tags: tags
    location: location
    namePrefix: namePrefix
    hostPrincipalId: host.outputs.hostPrincipalId
    vaultName: vault.outputs.vaultName
    registryName: registry.outputs.registryName
    fileAccountName: storage.outputs.fileAccountName
    ledgerAccountName: storage.outputs.ledgerAccountName
    hostName: host.outputs.hostName
    databaseServerName: data.outputs.serverName
    githubRepository: githubRepository
    githubEnvironment: githubEnvironment
    githubOwnerId: githubOwnerId
    githubRepositoryId: githubRepositoryId
  }
}

module budget 'modules/budget.bicep' = {
  name: 'budget'
  params: {
    monthlyAmount: monthlyBudgetAmount
    contactEmails: budgetContactEmails
    startDate: budgetStartDate
  }
}

@description('The address people visit.')
output publicFqdn string = host.outputs.publicFqdn

@description('The address to point a browser at before a certificate exists.')
output publicIpAddress string = host.outputs.publicIpAddress

@description('Where images are published and pulled from.')
output registryLoginServer string = registry.outputs.loginServer

@description('The database host name, resolvable only inside the network.')
output databaseFqdn string = data.outputs.serverFullyQualifiedDomainName

@description('Where the secrets live.')
output vaultUri string = vault.outputs.vaultUri

@description('Storage endpoints the application is configured with.')
output fileStorageEndpoint string = storage.outputs.fileAccountBlobEndpoint
output deletionRecordStorageEndpoint string = storage.outputs.ledgerAccountBlobEndpoint

@description('Client ids the GitHub workflows sign in with. Neither is a secret.')
output publishClientId string = access.outputs.publishClientId
output deployClientId string = access.outputs.deployClientId

@description('Tags applied to this deployment, for cost reporting.')
output resourceTags object = tags
