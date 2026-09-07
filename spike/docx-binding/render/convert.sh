#!/usr/bin/env bash
# Renders every filled "qualified" DOCX fixture to PDF using the pinned renderer image built
# from this directory's Dockerfile. One disposable container per file: each `docker run --rm`
# starts from the pristine image, so there is no LibreOffice profile or lock state left over
# from a previous conversion to contaminate the next one.
set -euo pipefail

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
module_root="$(CDPATH= cd -- "$script_dir/.." && pwd)"
input_dir="$module_root/target/spike-output"
output_dir="$module_root/target/render-output"
image_tag="brownie-spike-renderer:pinned"

# A portable stand-in for GNU `timeout`, which macOS does not ship. Kills the named container
# directly (rather than signaling the `docker run` client, which is not reliably propagated)
# if it outlives the deadline, so a wedged LibreOffice process cannot hang the whole batch.
run_with_container_timeout() {
  local secs="$1"
  local container_name="$2"
  shift 2
  "$@" &
  local pid=$!
  (sleep "$secs" && docker kill "$container_name" >/dev/null 2>&1) &
  local watchdog=$!
  local status=0
  wait "$pid" 2>/dev/null || status=$?
  kill "$watchdog" 2>/dev/null || true
  wait "$watchdog" 2>/dev/null || true
  return "$status"
}

mkdir -p "$output_dir"

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
  container_name="brownie-render-$$-$RANDOM"
  echo "Rendering $name..."
  if ! run_with_container_timeout 60 "$container_name" docker run --rm --name "$container_name" \
      -v "$file:/in/$name.docx:ro" \
      -v "$output_dir:/out" \
      "$image_tag" \
      soffice --headless --norestore --nolockcheck --nodefault \
        -env:UserInstallation=file:///home/renderer/.lo-profile \
        --convert-to pdf --outdir /out "/in/$name.docx" >/dev/null; then
    echo "FAILED: $name" >&2
    failures=1
  fi
done

if [ "$failures" -ne 0 ]; then
  echo "One or more conversions failed; see above." >&2
  exit 1
fi

echo "Rendered ${#files[@]} document(s) into $output_dir"
