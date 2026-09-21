// One virtual network with two subnets: the host that runs Brownie, and the
// database. The database is reachable only from inside this network -- it has
// no public address at all -- which is why the host has to live here too.
//
// Everything inbound is denied except the two ports a browser needs and,
// optionally, SSH from one address the owner names. Outbound is left open:
// the application has to reach the identity provider and the model provider,
// and the one component that must not reach anything (the renderer) is
// confined by the container it runs in, not by this network.

@description('Tags applied to every resource, so cost can be attributed.')
param tags object = {}

@description('Azure region for every resource in this deployment.')
param location string

@description('Prefix shared by every resource name, so one deployment cannot collide with another.')
param namePrefix string

@description('A single public address allowed to reach SSH, in CIDR form (for example 203.0.113.4/32). Empty means SSH is reachable from nowhere and the host is administered through the portal serial console.')
param adminSourceAddressPrefix string = ''

var appSubnetName = 'snet-app'
var databaseSubnetName = 'snet-db'

// The private DNS zone is what makes the database's own hostname resolve
// inside this network. Flexible Server requires the zone to exist and be
// linked before it will accept a private deployment.
resource databaseDnsZone 'Microsoft.Network/privateDnsZones@2024-06-01' = {
  name: '${namePrefix}.private.postgres.database.azure.com'
  location: 'global'
  tags: tags
}

resource databaseDnsLink 'Microsoft.Network/privateDnsZones/virtualNetworkLinks@2024-06-01' = {
  parent: databaseDnsZone
  name: 'link-${namePrefix}'
  location: 'global'
  properties: {
    registrationEnabled: false
    virtualNetwork: {
      id: virtualNetwork.id
    }
  }
}

resource appSecurityGroup 'Microsoft.Network/networkSecurityGroups@2024-05-01' = {
  name: 'nsg-${namePrefix}-app'
  location: location
  tags: tags
  properties: {
    securityRules: concat(
      [
        {
          // Plain HTTP exists only so that a browser typing the address
          // without a scheme is redirected to HTTPS, and so that the
          // certificate can be renewed over the ACME HTTP challenge.
          name: 'allow-http-in'
          properties: {
            priority: 100
            direction: 'Inbound'
            access: 'Allow'
            protocol: 'Tcp'
            sourceAddressPrefix: 'Internet'
            sourcePortRange: '*'
            destinationAddressPrefix: '*'
            destinationPortRange: '80'
          }
        }
        {
          name: 'allow-https-in'
          properties: {
            priority: 110
            direction: 'Inbound'
            access: 'Allow'
            protocol: 'Tcp'
            sourceAddressPrefix: 'Internet'
            sourcePortRange: '*'
            destinationAddressPrefix: '*'
            destinationPortRange: '443'
          }
        }
      ],
      empty(adminSourceAddressPrefix) ? [] : [
        {
          name: 'allow-ssh-from-admin'
          properties: {
            priority: 120
            direction: 'Inbound'
            access: 'Allow'
            protocol: 'Tcp'
            sourceAddressPrefix: adminSourceAddressPrefix
            sourcePortRange: '*'
            destinationAddressPrefix: '*'
            destinationPortRange: '22'
          }
        }
      ],
      [
        {
          // Azure's own default rules already deny inbound internet traffic
          // at a lower priority. This states it above them so that adding a
          // rule later cannot accidentally open everything by sitting in
          // between: anything new has to be given a priority below this one
          // deliberately.
          name: 'deny-everything-else-in'
          properties: {
            priority: 4096
            direction: 'Inbound'
            access: 'Deny'
            protocol: '*'
            sourceAddressPrefix: '*'
            sourcePortRange: '*'
            destinationAddressPrefix: '*'
            destinationPortRange: '*'
          }
        }
      ]
    )
  }
}

resource virtualNetwork 'Microsoft.Network/virtualNetworks@2024-05-01' = {
  name: 'vnet-${namePrefix}'
  location: location
  tags: tags
  properties: {
    addressSpace: {
      addressPrefixes: ['10.20.0.0/16']
    }
    subnets: [
      {
        name: appSubnetName
        properties: {
          addressPrefix: '10.20.1.0/24'
          networkSecurityGroup: {
            id: appSecurityGroup.id
          }
          // Service endpoints are what let the storage account and the key
          // vault refuse every address except this subnet while the host
          // still reaches them over Azure's own network rather than the
          // public internet.
          serviceEndpoints: [
            { service: 'Microsoft.Storage' }
            { service: 'Microsoft.KeyVault' }
          ]
        }
      }
      {
        name: databaseSubnetName
        properties: {
          addressPrefix: '10.20.2.0/24'
          delegations: [
            {
              name: 'postgres'
              properties: {
                serviceName: 'Microsoft.DBforPostgreSQL/flexibleServers'
              }
            }
          ]
        }
      }
    ]
  }
}

output appSubnetId string = '${virtualNetwork.id}/subnets/${appSubnetName}'
output databaseSubnetId string = '${virtualNetwork.id}/subnets/${databaseSubnetName}'
output databaseDnsZoneId string = databaseDnsZone.id
output databaseDnsLinkId string = databaseDnsLink.id
