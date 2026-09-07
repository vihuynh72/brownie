#!/usr/bin/env bash
# Trusted job launcher: the only thing in this spike allowed to start a renderer container.
# It takes exactly one argument -- the path to a single input .docx file -- and hardcodes
# everything else: the image, the executable, every mount path, and every isolation flag.
# A caller cannot supply an image name, executable, mount path, privilege flag, or arbitrary
# command; the only input surface this script exposes is "which file do you want converted."
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "usage: launch-job.sh <input.docx>" >&2
  exit 2
fi

input_file="$1"
if [ ! -f "$input_file" ]; then
  echo "input file does not exist: $input_file" >&2
  exit 2
fi

# Resolve to a real, absolute, symlink-free path before trusting it as a mount source, per
# the plan's "validate output paths without following symlinks" requirement.
input_dir_real="$(cd -- "$(dirname -- "$input_file")" && pwd -P)"
base_name="$(basename -- "$input_file")"
input_real="$input_dir_real/$base_name"
if [ ! -f "$input_real" ]; then
  echo "resolved input path is not a regular file: $input_real" >&2
  exit 2
fi
case "$base_name" in
  *.docx) ;;
  *)
    echo "refusing non-.docx input: $base_name" >&2
    exit 2
    ;;
esac

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
module_root="$(CDPATH= cd -- "$script_dir/.." && pwd)"
image_tag="brownie-spike-renderer:pinned"

# A unique staging directory per job. This job's container only ever sees this one directory,
# containing a copy of exactly one file -- never the shared staging root other jobs use, which
# is what actually forecloses a cross-job read, not just a promise not to do one.
staging_root="$module_root/target/job-staging"
mkdir -p "$staging_root"
job_dir="$(mktemp -d "$staging_root/job-XXXXXXXX")"
job_in="$job_dir/in"
job_out="$job_dir/out"
mkdir -p "$job_in" "$job_out"

cp -- "$input_real" "$job_in/$base_name"
chmod 0444 "$job_in/$base_name"

cleanup() {
  rm -rf "$job_dir"
}
trap cleanup EXIT

container_name="brownie-job-$$-$RANDOM"
output_max_bytes=20971520   # 20 MiB ceiling on one rendered artifact.
deadline_seconds=60

echo "job staged at $job_dir (input: $base_name)" >&2

(sleep "$deadline_seconds" && docker kill "$container_name" >/dev/null 2>&1) &
watchdog_pid=$!

set +e
docker run --rm --name "$container_name" \
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
  -v "$job_in:/in:ro" \
  -v "$job_out:/out" \
  "$image_tag" \
  soffice --headless --norestore --nolockcheck --nodefault \
    -env:UserInstallation=file:///home/renderer/.lo-profile \
    --convert-to pdf --outdir /out "/in/$base_name" >&2
run_status=$?
set -e

kill "$watchdog_pid" 2>/dev/null || true
wait "$watchdog_pid" 2>/dev/null || true

output_pdf="$job_out/${base_name%.docx}.pdf"

if [ "$run_status" -ne 0 ]; then
  echo "FAILED: renderer exited with status $run_status (killed by timeout or a resource limit, or a genuine conversion error)" >&2
  exit 1
fi

if [ ! -f "$output_pdf" ]; then
  echo "FAILED: expected output not found at $output_pdf" >&2
  exit 1
fi

actual_size=$(wc -c < "$output_pdf" | tr -d ' ')
if [ "$actual_size" -ge "$output_max_bytes" ]; then
  echo "FAILED: output size ${actual_size} bytes hit the ${output_max_bytes}-byte quota (likely truncated)" >&2
  exit 1
fi

result_dir="$module_root/target/render-output"
mkdir -p "$result_dir"
cp "$output_pdf" "$result_dir/$(basename "$output_pdf")"
echo "OK: $result_dir/$(basename "$output_pdf") ($actual_size bytes)"
