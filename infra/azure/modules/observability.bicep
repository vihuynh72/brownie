// One log workspace, with a ceiling on how much it may take in per day and a
// short retention.
//
// Both settings are cost controls first: ingestion is charged by the
// gibibyte, and an application that starts logging in a loop can spend more
// on its own logs in a day than on everything else in a month. The daily cap
// stops collecting rather than stops charging, which is the right failure for
// a pilot: losing the tail of a day's logs is cheaper than an unbounded bill,
// and the cap being reached is itself worth noticing.

@description('Tags applied to every resource, so cost can be attributed.')
param tags object = {}

@description('Azure region for the workspace.')
param location string

@description('Prefix shared by every resource name in this deployment.')
param namePrefix string

@description('Days logs are kept. Thirty is what the workspace includes at no extra charge.')
@minValue(30)
@maxValue(730)
param retentionInDays int = 30

@description('Gibibytes per day after which collection stops until the next day.')
param dailyQuotaGb int = 1

resource workspace 'Microsoft.OperationalInsights/workspaces@2025-02-01' = {
  name: 'log-${namePrefix}'
  location: location
  tags: tags
  properties: {
    sku: {
      name: 'PerGB2018'
    }
    retentionInDays: retentionInDays
    workspaceCapping: {
      dailyQuotaGb: dailyQuotaGb
    }
    features: {
      // Nothing reads this workspace from outside Azure, so the public
      // ingestion and query paths stay on for the portal's own use while
      // access is decided by role.
      enableLogAccessUsingOnlyResourcePermissions: true
    }
  }
}

output workspaceId string = workspace.id
output workspaceCustomerId string = workspace.properties.customerId
output workspaceName string = workspace.name
