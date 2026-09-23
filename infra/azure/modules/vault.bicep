// Where the secrets a hosted Brownie needs are kept: the three database
// passwords, the sign-in client secret and the model key, and, when people may
// connect a Google account, Google's client secret and the key that encrypts
// the tokens Google hands back. Nothing reads them
// from here at request time -- the host reads them once when it starts and
// passes them to the processes as environment variables -- so the vault's job
// is to be the one place they exist, with a record of who read them and a way
// to replace one without editing a file on a machine.
//
// Access is by role, not by access policy: a policy list is a second
// permission system that drifts from the first. Purge protection is on
// because a vault that can be permanently deleted takes the only copy of the
// sign-in secret with it.

@description('Tags applied to every resource, so cost can be attributed.')
param tags object = {}

@description('Azure region for the vault.')
param location string

@description('Prefix shared by every resource name in this deployment.')
param namePrefix string

@description('Resource id of the subnet allowed to reach the vault.')
param appSubnetId string

@description('Object id of the person or identity that administers the vault contents. Leave empty to assign no administrator here and grant it separately.')
param administratorObjectId string = ''

@description('A single public address, in CIDR form, allowed to reach the vault from outside the network. Without one, the secrets cannot be written from the owner machine at all.')
param administratorSourceAddressPrefix string = ''

var keyVaultSecretsOfficerRoleId = 'b86a8fe4-44ce-4948-aee5-eccb2c155cd7'

resource vault 'Microsoft.KeyVault/vaults@2024-11-01' = {
  name: take('kv-${namePrefix}-${uniqueString(resourceGroup().id)}', 24)
  location: location
  tags: tags
  properties: {
    tenantId: subscription().tenantId
    sku: {
      family: 'A'
      name: 'standard'
    }
    enableRbacAuthorization: true
    enableSoftDelete: true
    softDeleteRetentionInDays: 90
    enablePurgeProtection: true
    publicNetworkAccess: 'Enabled'
    networkAcls: {
      defaultAction: 'Deny'
      bypass: 'AzureServices'
      virtualNetworkRules: [
        {
          id: appSubnetId
        }
      ]
      // The trusted-services bypass above covers Azure's own services, not a
      // person at a keyboard: with no address listed here, writing a secret
      // from a laptop is refused even by an owner, and the vault is
      // unusable. So the one administrative address is allowed in, the same
      // one that may reach SSH. Leaving it empty is a deliberate choice to
      // administer the vault only from inside the network.
      ipRules: empty(administratorSourceAddressPrefix) ? [] : [
        {
          value: administratorSourceAddressPrefix
        }
      ]
    }
  }
}

resource administrator 'Microsoft.Authorization/roleAssignments@2022-04-01' = if (!empty(administratorObjectId)) {
  scope: vault
  name: guid(vault.id, administratorObjectId, keyVaultSecretsOfficerRoleId)
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', keyVaultSecretsOfficerRoleId)
    principalId: administratorObjectId
  }
}

output vaultName string = vault.name
output vaultId string = vault.id
output vaultUri string = vault.properties.vaultUri
