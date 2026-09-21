// Who may do what, expressed once.
//
// Three identities exist and none of them is a person: the host, which reads
// its own secrets and pulls its own images; a publishing identity, which may
// push an image and nothing else; and a deployment identity, which may
// restart the host with a new image and read the resource group to check what
// it did. Neither of the last two can change infrastructure, create a role
// assignment, or read a secret -- provisioning stays an action the owner
// takes deliberately from their own machine, with a change report in front of
// them first.
//
// Both belong to GitHub through a federated credential rather than a stored
// secret: a workflow proves which repository and which environment it is
// running in, and receives a token that lasts minutes. There is no password
// in the repository's settings to leak or to rotate.

@description('Tags applied to every resource, so cost can be attributed.')
param tags object = {}

@description('Azure region for the identities.')
param location string

@description('Prefix shared by every resource name in this deployment.')
param namePrefix string

@description('Principal id of the host, which reads secrets, pulls images and reaches storage.')
param hostPrincipalId string

@description('Name of the key vault the host reads secrets from.')
param vaultName string

@description('Name of the registry the host pulls from and the publishing identity pushes to.')
param registryName string

@description('Name of the storage account holding people files.')
param fileAccountName string

@description('Name of the storage account holding the deletion record.')
param ledgerAccountName string

@description('Name of the host, so the deployment identity can be scoped to that one machine.')
param hostName string

@description('Name of the database server, so that it can be stopped and started on a schedule without granting anything else over it.')
param databaseServerName string

@description('GitHub repository in owner/name form whose workflows may act as these identities.')
param githubRepository string

@description('Name of the GitHub environment a workflow must be running in to obtain a token. An environment can require a reviewer, which is what keeps a merge from deploying by itself.')
param githubEnvironment string = 'pilot'

@description('The numeric owner id and repository id GitHub puts in its token subject, as in repo:owner@123/name@456. GitHub issues this form for some repositories instead of the plain-name form, and a credential that expects the wrong one is refused with AADSTS700213. Leave both empty to trust only the plain-name form. Find them with: gh api repos/<owner>/<name> --jq "{owner: .owner.id, repo: .id}".')
param githubOwnerId string = ''
param githubRepositoryId string = ''

// Which subject a token carries is GitHub's choice, not ours, and it differs
// between repositories. Rather than guess, both forms are trusted when the ids
// are supplied: the tokens are otherwise identical, both are bound to the same
// one repository and the same one environment, and trusting the form that is
// never issued grants nothing to anybody.
var trustIdQualifiedSubject = !empty(githubOwnerId) && !empty(githubRepositoryId)
var githubOwnerAndRepo = trustIdQualifiedSubject
  ? '${split(githubRepository, '/')[0]}@${githubOwnerId}/${split(githubRepository, '/')[1]}@${githubRepositoryId}'
  : githubRepository

var acrPull = '7f951dda-4ed3-4680-a7ca-43fe172d538d'
var acrPush = '8311e382-0749-4cb8-b61a-304f252e45ec'
var keyVaultSecretsUser = '4633458b-17de-408a-b874-0445c86b69e6'
var storageBlobDataContributor = 'ba92f5b4-2d11-453d-a403-e96b0029c9fe'
var virtualMachineContributor = '9980e02c-c2be-4d73-94e8-173b1dc7cf3c'
var reader = 'acdd72a7-3385-48ef-bd42-f606fba81ae7'

resource vault 'Microsoft.KeyVault/vaults@2024-11-01' existing = {
  name: vaultName
}

resource registry 'Microsoft.ContainerRegistry/registries@2025-04-01' existing = {
  name: registryName
}

resource fileAccount 'Microsoft.Storage/storageAccounts@2024-01-01' existing = {
  name: fileAccountName
}

resource ledgerAccount 'Microsoft.Storage/storageAccounts@2024-01-01' existing = {
  name: ledgerAccountName
}

resource host 'Microsoft.Compute/virtualMachines@2024-11-01' existing = {
  name: hostName
}

resource databaseServer 'Microsoft.DBforPostgreSQL/flexibleServers@2024-08-01' existing = {
  name: databaseServerName
}

resource publishIdentity 'Microsoft.ManagedIdentity/userAssignedIdentities@2024-11-30' = {
  name: 'id-${namePrefix}-publish'
  location: location
  tags: tags
}

resource deployIdentity 'Microsoft.ManagedIdentity/userAssignedIdentities@2024-11-30' = {
  name: 'id-${namePrefix}-deploy'
  location: location
  tags: tags
}

resource publishFederation 'Microsoft.ManagedIdentity/userAssignedIdentities/federatedIdentityCredentials@2024-11-30' = {
  parent: publishIdentity
  name: 'github-${githubEnvironment}'
  properties: {
    issuer: 'https://token.actions.githubusercontent.com'
    subject: 'repo:${githubRepository}:environment:${githubEnvironment}'
    audiences: ['api://AzureADTokenExchange']
  }
}

resource publishFederationById 'Microsoft.ManagedIdentity/userAssignedIdentities/federatedIdentityCredentials@2024-11-30' = if (trustIdQualifiedSubject) {
  parent: publishIdentity
  name: 'github-${githubEnvironment}-by-id'
  properties: {
    issuer: 'https://token.actions.githubusercontent.com'
    subject: 'repo:${githubOwnerAndRepo}:environment:${githubEnvironment}'
    audiences: ['api://AzureADTokenExchange']
  }
  dependsOn: [
    // Two credentials on one identity cannot be written at the same time.
    publishFederation
  ]
}

resource deployFederation 'Microsoft.ManagedIdentity/userAssignedIdentities/federatedIdentityCredentials@2024-11-30' = {
  parent: deployIdentity
  name: 'github-${githubEnvironment}'
  properties: {
    issuer: 'https://token.actions.githubusercontent.com'
    subject: 'repo:${githubRepository}:environment:${githubEnvironment}'
    audiences: ['api://AzureADTokenExchange']
  }
}

resource deployFederationById 'Microsoft.ManagedIdentity/userAssignedIdentities/federatedIdentityCredentials@2024-11-30' = if (trustIdQualifiedSubject) {
  parent: deployIdentity
  name: 'github-${githubEnvironment}-by-id'
  properties: {
    issuer: 'https://token.actions.githubusercontent.com'
    subject: 'repo:${githubOwnerAndRepo}:environment:${githubEnvironment}'
    audiences: ['api://AzureADTokenExchange']
  }
  dependsOn: [
    // Two credentials on one identity cannot be written at the same time.
    deployFederation
  ]
}

resource hostReadsSecrets 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  scope: vault
  name: guid(vault.id, hostPrincipalId, keyVaultSecretsUser)
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', keyVaultSecretsUser)
    principalId: hostPrincipalId
    principalType: 'ServicePrincipal'
  }
}

resource hostPullsImages 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  scope: registry
  name: guid(registry.id, hostPrincipalId, acrPull)
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', acrPull)
    principalId: hostPrincipalId
    principalType: 'ServicePrincipal'
  }
}

resource hostWritesFiles 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  scope: fileAccount
  name: guid(fileAccount.id, hostPrincipalId, storageBlobDataContributor)
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', storageBlobDataContributor)
    principalId: hostPrincipalId
    principalType: 'ServicePrincipal'
  }
}

resource hostWritesDeletionRecord 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  scope: ledgerAccount
  name: guid(ledgerAccount.id, hostPrincipalId, storageBlobDataContributor)
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', storageBlobDataContributor)
    principalId: hostPrincipalId
    principalType: 'ServicePrincipal'
  }
}

resource publishPushesImages 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  scope: registry
  name: guid(registry.id, publishIdentity.id, acrPush)
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', acrPush)
    principalId: publishIdentity.properties.principalId
    principalType: 'ServicePrincipal'
  }
}

resource deployRestartsHost 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  scope: host
  name: guid(host.id, deployIdentity.id, virtualMachineContributor)
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', virtualMachineContributor)
    principalId: deployIdentity.properties.principalId
    principalType: 'ServicePrincipal'
  }
}

resource deployReadsResourceGroup 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  scope: resourceGroup()
  name: guid(resourceGroup().id, deployIdentity.id, reader)
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', reader)
    principalId: deployIdentity.properties.principalId
    principalType: 'ServicePrincipal'
  }
}

// Running the pilot during published hours is most of what keeps it inside
// the budget: a host and a database that are stopped sixteen hours a day cost
// a third of what they cost running. Nothing built in grants only that, and
// Contributor over the server would also allow deleting it, so this is the
// three actions and nothing else.
resource databasePowerRole 'Microsoft.Authorization/roleDefinitions@2022-04-01' = {
  name: guid(resourceGroup().id, 'database-power')
  properties: {
    roleName: 'Brownie database power (${namePrefix})'
    description: 'Start and stop the database server. No read of data, no change to the server, no delete.'
    type: 'CustomRole'
    assignableScopes: [
      resourceGroup().id
    ]
    permissions: [
      {
        actions: [
          'Microsoft.DBforPostgreSQL/flexibleServers/read'
          'Microsoft.DBforPostgreSQL/flexibleServers/start/action'
          'Microsoft.DBforPostgreSQL/flexibleServers/stop/action'
        ]
        notActions: []
        dataActions: []
        notDataActions: []
      }
    ]
  }
}

resource deployStopsDatabase 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  scope: databaseServer
  name: guid(databaseServer.id, deployIdentity.id, databasePowerRole.id)
  properties: {
    roleDefinitionId: databasePowerRole.id
    principalId: deployIdentity.properties.principalId
    principalType: 'ServicePrincipal'
  }
}

output publishClientId string = publishIdentity.properties.clientId
output deployClientId string = deployIdentity.properties.clientId
