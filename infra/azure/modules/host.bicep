// The one machine Brownie runs on.
//
// Why a virtual machine and not a managed container service: every document
// this application renders is rendered inside a throwaway container the API
// starts with no network and a read-only mount, and that mechanism needs a
// container runtime the application can talk to. A managed container platform
// does not give a container one, so hosting there would mean rebuilding
// rendering as an asynchronous job with its input and output handed over
// through storage -- a different design, with a container start added to
// every preview, replacing one that is measured at half a second. A single
// small host keeps the mechanism that already works and the timings that were
// already measured.
//
// It is deliberately one machine. Two would each enforce their own request
// limits and their own ceiling on simultaneous renders, because both live in
// one process's memory; making those shared is work that buys nothing until
// there is more traffic than one machine can carry.

@description('Tags applied to every resource, so cost can be attributed.')
param tags object = {}

@description('Azure region for the host.')
param location string

@description('Prefix shared by every resource name in this deployment.')
param namePrefix string

@description('Resource id of the subnet the host sits in.')
param appSubnetId string

@description('Virtual machine size. Two cores and four gibibytes is the smallest size that holds the API, the worker, the proxy, the virus scanner and one renderer at the same time.')
param vmSize string = 'Standard_B2als_v2'

@description('Administrative user name on the host. Not an application account.')
param adminUsername string

@description('Public SSH key for that user. Password sign-in is off, so this is the only way in over SSH.')
param adminPublicKey string

@description('The label that becomes <label>.<region>.cloudapp.azure.com, the address a browser uses and the name the certificate is issued for.')
param dnsLabel string

@description('Cloud-init document that prepares the host, base64 encoded. Empty leaves a bare machine.')
param cloudInitBase64 string = ''

@description('Gibibytes of managed disk for the operating system, images and staging directories.')
@minValue(32)
@maxValue(1024)
param osDiskSizeGB int = 32

resource publicAddress 'Microsoft.Network/publicIPAddresses@2024-05-01' = {
  name: 'pip-${namePrefix}'
  location: location
  tags: tags
  sku: {
    name: 'Standard'
  }
  properties: {
    publicIPAllocationMethod: 'Static'
    dnsSettings: {
      domainNameLabel: dnsLabel
    }
  }
}

resource networkInterface 'Microsoft.Network/networkInterfaces@2024-05-01' = {
  name: 'nic-${namePrefix}'
  location: location
  tags: tags
  properties: {
    ipConfigurations: [
      {
        name: 'ipconfig'
        properties: {
          subnet: {
            id: appSubnetId
          }
          privateIPAllocationMethod: 'Dynamic'
          publicIPAddress: {
            id: publicAddress.id
          }
        }
      }
    ]
  }
}

resource host 'Microsoft.Compute/virtualMachines@2024-11-01' = {
  name: 'vm-${namePrefix}'
  location: location
  tags: tags
  identity: {
    // The host's own identity is how it reads its secrets and pulls its
    // images. Nothing on it holds a stored credential for either.
    type: 'SystemAssigned'
  }
  properties: {
    hardwareProfile: {
      vmSize: vmSize
    }
    storageProfile: {
      imageReference: {
        publisher: 'Canonical'
        offer: 'ubuntu-24_04-lts'
        sku: 'server'
        // Unlike a container image, a machine image version is a starting
        // point that the machine patches away from on its own. Pinning one
        // would pin the security updates out, so the newest is taken at
        // creation and the machine keeps itself current afterwards.
        version: 'latest'
      }
      osDisk: {
        createOption: 'FromImage'
        diskSizeGB: osDiskSizeGB
        managedDisk: {
          storageAccountType: 'StandardSSD_LRS'
        }
        deleteOption: 'Delete'
      }
    }
    osProfile: {
      computerName: 'brownie'
      adminUsername: adminUsername
      linuxConfiguration: {
        disablePasswordAuthentication: true
        ssh: {
          publicKeys: [
            {
              path: '/home/${adminUsername}/.ssh/authorized_keys'
              keyData: adminPublicKey
            }
          ]
        }
        patchSettings: {
          patchMode: 'AutomaticByPlatform'
          assessmentMode: 'AutomaticByPlatform'
        }
      }
      customData: empty(cloudInitBase64) ? null : cloudInitBase64
    }
    securityProfile: {
      // Secure boot and a virtual TPM cost nothing and make a firmware-level
      // tamper visible rather than silent.
      securityType: 'TrustedLaunch'
      uefiSettings: {
        secureBootEnabled: true
        vTpmEnabled: true
      }
    }
    networkProfile: {
      networkInterfaces: [
        {
          id: networkInterface.id
        }
      ]
    }
  }
}

output hostId string = host.id
output hostName string = host.name
output hostPrincipalId string = host.identity.principalId
output publicFqdn string = publicAddress.properties.dnsSettings.fqdn
output publicIpAddress string = publicAddress.properties.ipAddress
