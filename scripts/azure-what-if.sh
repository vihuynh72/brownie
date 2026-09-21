#!/usr/bin/env bash
# Prints what a deployment would change in an Azure resource group, without
# changing anything.
#
#   ./scripts/azure-what-if.sh <resource-group>
#
# Read the report before running azure-deploy.sh. On a first run every line
# says "Create"; on a later run the only lines that should appear are the ones
# you meant to change, and anything else is a surprise worth understanding
# before it happens.
#
# Needs: the Azure CLI, signed in (az login) with the subscription you intend
# selected (az account show), and infra/azure/main.bicepparam filled in from
# main.example.bicepparam. The two secrets that file reads come from the
# environment:
#
#   export BROWNIE_DB_ADMIN_PASSWORD="$(openssl rand -base64 30)"
#   export BROWNIE_HOST_SSH_PUBLIC_KEY="$(cat ~/.ssh/brownie_pilot.pub)"
#
# This command reads only. It cannot create, change or delete a resource.

set -euo pipefail

script_dir="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
repository_root="$(CDPATH='' cd -- "$script_dir/.." && pwd)"

if [ $# -ne 1 ]; then
  printf 'usage: %s <resource-group>\n' "$0" >&2
  exit 64
fi
resource_group="$1"

if ! command -v az >/dev/null 2>&1; then
  printf 'The Azure CLI is not installed. See https://learn.microsoft.com/cli/azure/install-azure-cli\n' >&2
  exit 69
fi

parameter_file="$repository_root/infra/azure/main.bicepparam"
if [ ! -f "$parameter_file" ]; then
  printf 'No %s. Copy infra/azure/main.example.bicepparam to it and fill it in first.\n' "$parameter_file" >&2
  exit 66
fi

for variable in BROWNIE_DB_ADMIN_PASSWORD BROWNIE_HOST_SSH_PUBLIC_KEY; do
  if [ -z "${!variable:-}" ]; then
    printf '%s is not set; the parameter file reads it from the environment. See the comments at the top of this script.\n' "$variable" >&2
    exit 78
  fi
done

account="$(az account show --query '[name, id]' -o tsv 2>/dev/null | paste -sd' ' -)" || {
  printf 'Not signed in. Run: az login\n' >&2
  exit 77
}
printf 'Subscription: %s\n' "$account"
printf 'Resource group: %s\n\n' "$resource_group"

# --no-pretty-print is deliberately not used: the point of this step is a
# report a person reads.
az deployment group what-if \
  --resource-group "$resource_group" \
  --template-file "$repository_root/infra/azure/main.bicep" \
  --parameters "$parameter_file" \
  --result-format FullResourcePayloads
