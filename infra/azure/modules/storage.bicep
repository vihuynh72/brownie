// Two storage accounts, deliberately not one.
//
// The first holds people's files. The second holds the record of what has
// been deleted, which exists so that restoring a backup cannot quietly bring
// back something a person asked to have removed. Keeping that record in the
// same account as the files would defeat it: whoever can delete the files
// could delete the record, and restoring the account would roll the record
// back with everything else. So the second account has versioning and a
// retention policy of its own, and nothing in a restore procedure touches it.
//
// Neither account accepts its own access keys. Every caller authenticates as
// a directory identity and is authorized by role, so there is no key to leak,
// rotate, or accidentally paste into a configuration file.

@description('Tags applied to every resource, so cost can be attributed.')
param tags object = {}

@description('Azure region for both accounts.')
param location string

@description('Prefix shared by every resource name in this deployment.')
param namePrefix string

@description('Resource id of the subnet allowed to reach these accounts.')
param appSubnetId string

@description('Days a deleted blob can still be recovered in the file account.')
@minValue(1)
@maxValue(365)
param fileSoftDeleteDays int = 7

@description('Days the deletion record is immutable for. Within this window nothing, including an operator, can change or remove an entry.')
@minValue(1)
@maxValue(146000)
param deletionRecordImmutableDays int = 180

// Storage account names are global, lower-case and at most 24 characters, so
// they cannot simply be the prefix.
var fileAccountName = take('st${replace(namePrefix, '-', '')}${uniqueString(resourceGroup().id)}', 24)
var ledgerAccountName = take('stl${replace(namePrefix, '-', '')}${uniqueString(resourceGroup().id)}', 24)

var commonProperties = {
  minimumTlsVersion: 'TLS1_2'
  supportsHttpsTrafficOnly: true
  allowBlobPublicAccess: false
  allowSharedKeyAccess: false
  publicNetworkAccess: 'Enabled'
  networkAcls: {
    // Enabled above with a default of Deny means: reachable, but only from
    // the places named here. The application subnet is the only one.
    defaultAction: 'Deny'
    bypass: 'AzureServices'
    virtualNetworkRules: [
      {
        id: appSubnetId
        action: 'Allow'
      }
    ]
  }
}

resource fileAccount 'Microsoft.Storage/storageAccounts@2024-01-01' = {
  name: fileAccountName
  location: location
  tags: tags
  sku: {
    name: 'Standard_LRS'
  }
  kind: 'StorageV2'
  properties: union(commonProperties, {
    accessTier: 'Hot'
  })
}

resource fileBlobService 'Microsoft.Storage/storageAccounts/blobServices@2024-01-01' = {
  parent: fileAccount
  name: 'default'
  properties: {
    // A short recovery window for a blob removed by mistake. It does not
    // weaken deletion: the worker's permanent deletion is what a person asks
    // for, and this window is measured in days, after which the bytes are
    // gone whatever anyone does.
    deleteRetentionPolicy: {
      enabled: true
      days: fileSoftDeleteDays
    }
    containerDeleteRetentionPolicy: {
      enabled: true
      days: fileSoftDeleteDays
    }
  }
}

resource artifactsContainer 'Microsoft.Storage/storageAccounts/blobServices/containers@2024-01-01' = {
  parent: fileBlobService
  name: 'artifacts'
  properties: {
    publicAccess: 'None'
  }
}

resource ledgerAccount 'Microsoft.Storage/storageAccounts@2024-01-01' = {
  name: ledgerAccountName
  location: location
  tags: tags
  sku: {
    name: 'Standard_LRS'
  }
  kind: 'StorageV2'
  properties: union(commonProperties, {
    accessTier: 'Hot'
  })
}

resource ledgerBlobService 'Microsoft.Storage/storageAccounts/blobServices@2024-01-01' = {
  parent: ledgerAccount
  name: 'default'
  properties: {
    // Versioning here is not about convenience: an entry that was overwritten
    // or removed can still be read, which is the whole point of a record that
    // has to outlive the thing it describes.
    isVersioningEnabled: true
    deleteRetentionPolicy: {
      enabled: true
      days: 365
    }
  }
}

resource deletionLedgerContainer 'Microsoft.Storage/storageAccounts/blobServices/containers@2024-01-01' = {
  parent: ledgerBlobService
  name: 'deletion-ledger'
  properties: {
    publicAccess: 'None'
  }
}

// An unlocked time-based policy: entries cannot be changed or deleted inside
// the window, and the policy itself can still be shortened or removed by an
// owner. Locking it is deliberately left as the owner's own decision, because
// a locked policy cannot be undone by anyone, including Microsoft.
resource deletionLedgerImmutability 'Microsoft.Storage/storageAccounts/blobServices/containers/immutabilityPolicies@2024-01-01' = {
  parent: deletionLedgerContainer
  name: 'default'
  properties: {
    immutabilityPeriodSinceCreationInDays: deletionRecordImmutableDays
    allowProtectedAppendWrites: false
  }
}

output fileAccountName string = fileAccount.name
output fileAccountId string = fileAccount.id
output fileAccountBlobEndpoint string = fileAccount.properties.primaryEndpoints.blob
output ledgerAccountName string = ledgerAccount.name
output ledgerAccountId string = ledgerAccount.id
output ledgerAccountBlobEndpoint string = ledgerAccount.properties.primaryEndpoints.blob
