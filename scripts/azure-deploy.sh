#!/usr/bin/env bash
# Creates or updates the Azure resources Brownie runs on. This one spends
# money.
#
#   ./scripts/azure-what-if.sh <resource-group>   # read the report first
#   ./scripts/azure-deploy.sh  <resource-group>
#
# It asks you to type the resource group's name back before it starts, because
# pointing this at the wrong group is the one mistake that cannot be undone by
# running it again.
#
# Creating the role assignments in this template needs a role that can grant
# roles (Owner, or User Access Administrator alongside Contributor). An
# ordinary Contributor deployment fails partway through, having created the
# resources but none of the permissions between them.
#
# Afterwards, put the secrets in the key vault it created; the host reads
# them at start-up and holds no stored credential of its own. The deployment
# prints the vault's address and the addresses of everything else the
# application is configured with.

set -euo pipefail

script_dir="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
repository_root="$(CDPATH='' cd -- "$script_dir/.." && pwd)"

if [ $# -ne 1 ]; then
  printf 'usage: %s <resource-group>\n' "$0" >&2
  exit 64
fi
resource_group="$1"

parameter_file="$repository_root/infra/azure/main.bicepparam"
if [ ! -f "$parameter_file" ]; then
  printf 'No %s. Copy infra/azure/main.example.bicepparam to it and fill it in first.\n' "$parameter_file" >&2
  exit 66
fi
for variable in BROWNIE_DB_ADMIN_PASSWORD BROWNIE_HOST_SSH_PUBLIC_KEY; do
  if [ -z "${!variable:-}" ]; then
    printf '%s is not set; the parameter file reads it from the environment.\n' "$variable" >&2
    exit 78
  fi
done

account="$(az account show --query '[name, id]' -o tsv 2>/dev/null | paste -sd' ' -)" || {
  printf 'Not signed in. Run: az login\n' >&2
  exit 77
}
printf 'Subscription: %s\n' "$account"
printf 'This creates or changes real resources in %s and starts charging for them.\n' "$resource_group"
printf 'Type the resource group name to continue: '
read -r confirmation
if [ "$confirmation" != "$resource_group" ]; then
  printf 'Nothing was done.\n' >&2
  exit 75
fi

az deployment group create \
  --resource-group "$resource_group" \
  --name "brownie-$(date -u +%Y%m%dT%H%M%SZ)" \
  --template-file "$repository_root/infra/azure/main.bicep" \
  --parameters "$parameter_file" \
  --query 'properties.outputs' \
  -o json
