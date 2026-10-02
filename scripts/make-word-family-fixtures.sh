#!/usr/bin/env bash
# Regenerates the sample forms in fixtures/public/forms/ from their sources.
#
#   ./scripts/make-word-family-fixtures.sh
#
# The sources are hand-written flat OpenDocument files in
# fixtures/public/forms/src/ (plain XML, so a change to a form can be read in
# review). Each one is converted to .doc (Word 97), .rtf, .odt, .ott and .docx
# by the renderer image, one network-disabled container per file, under the
# same limits the application uses. The results are committed, so tests never
# need to run this; run it again only after changing a source.
#
# The image is brownie-spike-renderer:pinned unless BROWNIE_RENDER_IMAGE names
# another (the same variable the application and the Docker tests read). Build
# it first with:
#   docker build -t brownie-spike-renderer:pinned spike/docx-binding/render
#
# Two formats the converter accepts have no sample here. LibreOffice cannot
# write Word 95 or Apple Pages files, so neither can be made from a source;
# real ones have to come from Word 95 and Pages themselves.

set -euo pipefail

script_dir="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
repository_root="$(CDPATH='' cd -- "$script_dir/.." && pwd)"
source_dir="$repository_root/fixtures/public/forms/src"
target_dir="$repository_root/fixtures/public/forms"
image="${BROWNIE_RENDER_IMAGE:-brownie-spike-renderer:pinned}"
output_max_bytes=20971520
deadline_seconds=60

if ! image_id="$(docker image inspect --format '{{.Id}}' "$image" 2>/dev/null)"; then
  printf 'The image %s is not on this machine. Build it first (see the top of this script).\n' "$image" >&2
  exit 69
fi
printf 'Converting with %s (%s)\n' "$image" "$image_id"

# extension, then the export filter that writes it
formats=(
  "doc:MS Word 97"
  "rtf:Rich Text Format"
  "odt:writer8"
  "ott:writer8_template"
  "docx:MS Word 2007 XML"
)

job_root="$(mktemp -d)"
cleanup() {
  rm -rf -- "$job_root"
}
trap cleanup EXIT

convert() {
  local source="$1" extension="$2" filter="$3"
  local name job container status
  name="$(basename -- "$source" .fodt)"
  job="$(mktemp -d "$job_root/job-XXXXXXXX")"
  mkdir "$job/in" "$job/out"
  cp -- "$source" "$job/in/input.fodt"
  chmod 0444 "$job/in/input.fodt"
  # The container runs as its own non-root user, which has to be able to write here.
  chmod 0777 "$job/out"
  container="brownie-fixture-$$-$RANDOM"

  (sleep "$deadline_seconds" && docker kill "$container" >/dev/null 2>&1) &
  local watchdog=$!
  set +e
  docker run --rm --name "$container" \
    --network none \
    --read-only \
    --tmpfs /tmp:rw,size=64m,mode=1777 \
    --tmpfs /home/renderer:rw,size=32m,mode=0700,uid=10001,gid=10001 \
    --memory=512m --memory-swap=512m \
    --pids-limit=128 \
    --cpus=1 \
    --cap-drop=ALL \
    --security-opt no-new-privileges \
    --ulimit "fsize=$output_max_bytes" \
    -v "$job/in:/in:ro" \
    -v "$job/out:/out" \
    "$image_id" \
    soffice --headless --norestore --nolockcheck --nodefault \
      -env:UserInstallation=file:///home/renderer/.lo-profile \
      "--infilter=OpenDocument Text Flat XML" \
      --convert-to "$extension:$filter" --outdir /out /in/input.fodt >/dev/null 2>&1
  status=$?
  set -e
  kill "$watchdog" 2>/dev/null || true
  wait "$watchdog" 2>/dev/null || true

  local output="$job/out/input.$extension"
  if [ "$status" -ne 0 ] || [ -L "$output" ] || [ ! -f "$output" ] || [ ! -s "$output" ]; then
    printf 'FAILED: %s to .%s (status %s)\n' "$name" "$extension" "$status" >&2
    return 1
  fi
  cp -- "$output" "$target_dir/$name.$extension"
  printf '  %s.%s\n' "$name" "$extension"
}

shopt -s nullglob
sources=("$source_dir"/*.fodt)
if [ ${#sources[@]} -eq 0 ]; then
  printf 'No sources in %s.\n' "$source_dir" >&2
  exit 66
fi

for source in "${sources[@]}"; do
  for format in "${formats[@]}"; do
    convert "$source" "${format%%:*}" "${format#*:}"
  done
done
printf 'Wrote %d files to %s\n' "$(( ${#sources[@]} * ${#formats[@]} ))" "$target_dir"
