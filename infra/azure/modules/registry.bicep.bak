// The registry the three application images are published to and the host
// pulls them from, by digest.
//
// The basic tier has no network rules of its own -- restricting a registry to
// a virtual network needs the premium tier, which costs ten times as much and
// would be most of the hosting budget. What protects it instead is that
// anonymous pull is off and its own administrator account is disabled, so the
// only way in is a directory identity holding a role: the host may pull, the
// deployment identity may push, and nothing else has either.

@description('Tags applied to every resource, so cost can be attributed.')
param tags object = {}

@description('Azure region for the registry.')
param location string

@description('Prefix shared by every resource name in this deployment.')
param namePrefix string

@description('Days an untagged manifest is kept before the registry removes it. Keeps the included storage from filling with superseded layers.')
@minValue(1)
@maxValue(365)
param untaggedManifestRetentionDays int = 30

resource registry 'Microsoft.ContainerRegistry/registries@2025-04-01' = {
  name: take('cr${replace(namePrefix, '-', '')}${uniqueString(resourceGroup().id)}', 50)
  location: location
  tags: tags
  sku: {
    name: 'Basic'
  }
  properties: {
    adminUserEnabled: false
    anonymousPullEnabled: false
    policies: {
      retentionPolicy: {
        status: 'enabled'
        days: untaggedManifestRetentionDays
      }
    }
  }
}

output registryName string = registry.name
output registryId string = registry.id
output loginServer string = registry.properties.loginServer
