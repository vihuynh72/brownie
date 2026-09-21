// The managed PostgreSQL server. It has no public address: it is placed
// inside the database subnet and answers only to names resolved through the
// private zone, so the only way to reach it is from the application subnet
// next to it.
//
// The server admin created here is not what the application signs in as.
// Brownie's three least-privilege roles (migration, api, worker) are created
// on top of it by the same bootstrap SQL the local stack uses; the admin
// exists to create them and to be the account an operator recovers with.

@description('Tags applied to every resource, so cost can be attributed.')
param tags object = {}

@description('Azure region for the server.')
param location string

@description('Prefix shared by every resource name in this deployment.')
param namePrefix string

@description('Resource id of the delegated subnet the server is placed in.')
param delegatedSubnetId string

@description('Resource id of the private DNS zone the server registers its name in.')
param privateDnsZoneId string

@description('Administrator login for the server itself. Not an application credential.')
param administratorLogin string

@description('Administrator password. Supplied at deployment time and stored in the key vault, never in a parameter file.')
@secure()
param administratorPassword string

@description('Days of automatic backups retained. Seven is the smallest the service offers and the cheapest.')
@minValue(7)
@maxValue(35)
param backupRetentionDays int = 7

@description('Provisioned storage. Thirty-two gibibytes is the smallest size; storage can be grown later but never shrunk.')
param storageSizeGB int = 32

resource server 'Microsoft.DBforPostgreSQL/flexibleServers@2024-08-01' = {
  name: 'psql-${namePrefix}'
  location: location
  tags: tags
  sku: {
    // The smallest burstable size. Brownie's measured working set is a few
    // hundred megabytes of rows; what this size actually limits is how many
    // connections and how much sustained CPU are available, and the pilot's
    // ten participants are far inside both.
    name: 'Standard_B1ms'
    tier: 'Burstable'
  }
  properties: {
    version: '17'
    administratorLogin: administratorLogin
    administratorLoginPassword: administratorPassword
    storage: {
      storageSizeGB: storageSizeGB
      autoGrow: 'Disabled'
    }
    backup: {
      backupRetentionDays: backupRetentionDays
      // Geo-redundant backups double the backup bill and protect against a
      // region loss, which is not a risk an invite pilot carries.
      geoRedundantBackup: 'Disabled'
    }
    highAvailability: {
      mode: 'Disabled'
    }
    network: {
      delegatedSubnetResourceId: delegatedSubnetId
      privateDnsZoneArmResourceId: privateDnsZoneId
      publicNetworkAccess: 'Disabled'
    }
    authConfig: {
      // Password authentication stays on because Brownie's three roles are
      // database roles with passwords, not directory identities.
      passwordAuth: 'Enabled'
      activeDirectoryAuth: 'Disabled'
    }
  }
}

// Encryption in transit is not negotiable, and the service's default allows
// a client to fall back to an unencrypted connection. Turning the fallback
// off is a server parameter, not a property of the server itself.
resource requireEncryption 'Microsoft.DBforPostgreSQL/flexibleServers/configurations@2024-08-01' = {
  parent: server
  name: 'require_secure_transport'
  properties: {
    value: 'ON'
    source: 'user-override'
  }
}

resource database 'Microsoft.DBforPostgreSQL/flexibleServers/databases@2024-08-01' = {
  parent: server
  name: 'brownie'
  properties: {
    charset: 'UTF8'
    collation: 'en_US.utf8'
  }
}

output serverName string = server.name
output serverFullyQualifiedDomainName string = server.properties.fullyQualifiedDomainName
output databaseName string = database.name
output serverId string = server.id
