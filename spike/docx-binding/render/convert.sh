#!/usr/bin/env bash
# Batch-renders every filled "qualified" DOCX fixture to PDF by calling the trusted launcher
# (launch-job.sh) once per file. All the actual container hardening lives in that one script;
# this loop exists only to save typing across the fixture set.
set -euo pipefail

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
module_root="$(CDPATH= cd -- "$script_dir/.." && pwd)"
input_dir="$module_root/target/spike-output"
image_tag="brownie-spike-renderer:pinned"

echo "Building pinned renderer image..."
docker build -q -t "$image_tag" "$script_dir" >/dev/null

shopt -s nullglob
files=("$input_dir"/qualified-*-filled.docx)
if [ ${#files[@]} -eq 0 ]; then
  echo "No filled qualified documents found in $input_dir -- run SpikeRunner first." >&2
  exit 1
fi

failures=0
for file in "${files[@]}"; do
  name="$(basename "$file" .docx)"
  echo "Rendering $name..."
  if ! "$script_dir/launch-job.sh" "$file"; then
    echo "FAILED: $name" >&2
    failures=1
  fi
done

if [ "$failures" -ne 0 ]; then
  echo "One or more conversions failed; see above." >&2
  exit 1
fi

echo "Rendered ${#files[@]} document(s) into $module_root/target/render-output"
