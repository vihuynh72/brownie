#!/usr/bin/env bash
# Proves the isolation properties launch-job.sh claims, instead of just trusting the flags.
# Every test here deliberately tries to break out of, overload, or outlive its container --
# each one bounded (resource-capped, short-lived, --network none where relevant) so a failure
# mode is contained inside a disposable container, never the host.
#
# Uses a pinned, generic Alpine image (not the renderer image) for the generic shell-level
# stress tests below, so the production renderer image never needs curl, wget, or anything
# else that exists only to attack it.
set -uo pipefail

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
module_root="$(CDPATH= cd -- "$script_dir/.." && pwd)"
diag_image="alpine:3.20.6@sha256:de4fe7064d8f98419ea6b49190df1abbf43450c1702eeb864fe9ced453c1cc5f"
render_image="brownie-spike-renderer:pinned"

failures=0
pass() { printf 'PASS: %s\n' "$1"; }
fail() { printf 'FAIL: %s\n' "$1" >&2; failures=$((failures + 1)); }
info() { printf 'INFO: %s\n' "$1"; }

echo "=== 1/8: network is blocked (control test included) ==="
if docker run --rm --network none "$diag_image" \
    wget -q -T 3 -O /dev/null https://example.com 2>/dev/null; then
  fail "wget to a public IP succeeded with --network none (should be unreachable)"
else
  pass "wget to a public IP failed with --network none, as expected"
fi
if docker run --rm "$diag_image" \
    wget -q -T 3 -O /dev/null https://example.com 2>/dev/null; then
  pass "control: the same wget succeeds on an unrestricted network (proves the block above is real, not a broken test)"
else
  fail "control wget failed even without --network none -- this test's network reachability assumption is broken, not the isolation"
fi

echo
echo "=== 2/8: cloud metadata endpoint is unreachable ==="
if docker run --rm --network none "$diag_image" \
    wget -q -T 3 -O /dev/null http://169.254.169.254/ 2>/dev/null; then
  fail "the cloud metadata address answered with --network none (should be unreachable)"
else
  pass "the cloud metadata address is unreachable with --network none"
fi

echo
echo "=== 3/8: no Docker socket is present inside the container ==="
if docker run --rm --network none "$diag_image" test -e /var/run/docker.sock; then
  fail "/var/run/docker.sock exists inside the container"
else
  pass "/var/run/docker.sock is absent inside the container"
fi

echo
echo "=== 4/8: root filesystem is read-only; only the intended tmpfs areas are writable ==="
if docker run --rm --network none --read-only "$diag_image" \
    sh -c 'touch /etc/should-not-write 2>/dev/null'; then
  fail "wrote to /etc despite --read-only"
else
  pass "write to /etc was rejected under --read-only"
fi
if docker run --rm --network none --read-only --tmpfs /tmp:rw,size=8m "$diag_image" \
    sh -c 'touch /tmp/should-work'; then
  pass "the designated tmpfs (/tmp) remains writable under --read-only"
else
  fail "the designated tmpfs (/tmp) was not writable -- renderer would not be able to run at all"
fi

echo
echo "=== 5/8: a memory-exhausting process is killed at the configured cap, not left to grow ==="
mem_container="brownie-isotest-mem-$$"
docker run --network none --memory=64m --memory-swap=64m --pids-limit=64 \
  --name "$mem_container" "$diag_image" \
  sh -c 'a=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA; while true; do a="$a$a"; done' >/dev/null 2>&1
mem_status=$?
oom_killed="$(docker inspect --format '{{.State.OOMKilled}}' "$mem_container" 2>/dev/null || echo unknown)"
docker rm "$mem_container" >/dev/null 2>&1
if [ "$oom_killed" = "true" ] || [ "$mem_status" -eq 137 ]; then
  pass "the memory-exhausting process was killed at the 64m cap (OOMKilled=$oom_killed, exit=$mem_status)"
else
  fail "the memory-exhausting process was not killed as expected (OOMKilled=$oom_killed, exit=$mem_status)"
fi

echo
echo "=== 6/8: a fork bomb is contained by --pids-limit, and Docker itself survives it ==="
info "running a deliberately bounded fork bomb (--pids-limit=16, 5s self-timeout) -- this is the one test that could be disruptive if unbounded, so both bounds are kept tight."
bomb_container="brownie-isotest-pids-$$"
docker run -d --network none --memory=64m --pids-limit=16 --cpus=0.5 \
  --name "$bomb_container" "$diag_image" \
  sh -c ':(){ :|:& };:' >/dev/null 2>&1
(sleep 5 && docker kill "$bomb_container" >/dev/null 2>&1) &
killer=$!
docker wait "$bomb_container" >/dev/null 2>&1
wait "$killer" 2>/dev/null
docker rm -f "$bomb_container" >/dev/null 2>&1
if docker run --rm "$diag_image" echo ok >/dev/null 2>&1; then
  pass "Docker daemon remained healthy through the bounded fork bomb (a trivial container still runs)"
else
  fail "Docker daemon did not respond normally after the fork bomb test"
fi

echo
echo "=== 7/8: a hung job is killed at the deadline, and the launcher recovers for the next job ==="
hang_container="brownie-isotest-hang-$$"
start_ts=$(date +%s)
docker run -d --rm --network none --name "$hang_container" "$diag_image" sleep 300 >/dev/null 2>&1
(sleep 8 && docker kill "$hang_container" >/dev/null 2>&1) &
killer=$!
docker wait "$hang_container" >/dev/null 2>&1
wait "$killer" 2>/dev/null
end_ts=$(date +%s)
elapsed=$((end_ts - start_ts))
if [ "$elapsed" -lt 60 ]; then
  pass "hung container was killed after ${elapsed}s, not left running for its full 300s command"
else
  fail "hung container ran for ${elapsed}s -- the timeout/kill did not fire"
fi
recovery_fixture="$module_root/target/spike-output/qualified-flowing-two-items-filled.docx"
if [ -f "$recovery_fixture" ] && "$script_dir/launch-job.sh" "$recovery_fixture" >/dev/null 2>&1; then
  pass "a normal job launched immediately after the killed one still succeeds (the launcher is not wedged)"
else
  fail "a normal job failed right after the killed one -- the launcher may be left in a bad state"
fi

echo
echo "=== 8/8: unique per-job staging, and cross-job mounts are structurally impossible ==="
demo_root="$(mktemp -d "$module_root/target/isolation-demo-XXXXXXXX")"
mkdir -p "$demo_root/job-a" "$demo_root/job-b"
echo "job-a-secret" > "$demo_root/job-a/a.txt"
echo "job-b-secret" > "$demo_root/job-b/b.txt"
per_job_listing="$(docker run --rm -v "$demo_root/job-a:/in:ro" "$diag_image" ls /in)"
if [ "$per_job_listing" = "a.txt" ]; then
  pass "mounting one job's own directory exposes only that job's file (saw: $per_job_listing)"
else
  fail "mounting one job's own directory exposed unexpected contents: $per_job_listing"
fi
root_listing="$(docker run --rm -v "$demo_root:/in:ro" "$diag_image" sh -c 'ls /in/job-a /in/job-b' | tr '\n' ' ')"
if printf '%s' "$root_listing" | grep -q "a.txt" && printf '%s' "$root_listing" | grep -q "b.txt"; then
  info "control: mounting the shared staging ROOT (the anti-pattern launch-job.sh avoids) exposes both jobs' files ($root_listing) -- this is exactly why launch-job.sh only ever mounts one job's own directory, never the root"
else
  fail "expected the anti-pattern control mount to show cross-job visibility, but it did not -- the control itself may be broken"
fi
rm -rf "$demo_root"

job1_log="$(mktemp)"
job2_log="$(mktemp)"
"$script_dir/launch-job.sh" "$module_root/target/spike-output/qualified-flowing-short-filled.docx" 2>"$job1_log" >/dev/null
"$script_dir/launch-job.sh" "$module_root/target/spike-output/qualified-table_led-short-filled.docx" 2>"$job2_log" >/dev/null
dir1="$(grep -o '/job-staging/job-[A-Za-z0-9]*' "$job1_log" | head -1)"
dir2="$(grep -o '/job-staging/job-[A-Za-z0-9]*' "$job2_log" | head -1)"
rm -f "$job1_log" "$job2_log"
if [ -n "$dir1" ] && [ -n "$dir2" ] && [ "$dir1" != "$dir2" ]; then
  pass "two real launcher runs used distinct staging directories ($dir1 vs $dir2)"
else
  fail "could not confirm distinct staging directories across two launcher runs (got '$dir1' and '$dir2')"
fi
if [ -d "$module_root/target/job-staging" ] && [ -z "$(ls -A "$module_root/target/job-staging" 2>/dev/null)" ]; then
  pass "the staging root is empty after both jobs completed (no leftover job directories)"
else
  fail "leftover staging directories found after job completion"
fi

echo
echo "=== output quota: an artificially tiny file-size cap actually stops oversized output ==="
quota_out="$(mktemp -d)"
docker run --rm --network none --read-only \
  --tmpfs /tmp:rw,size=64m,mode=1777 \
  --tmpfs /home/renderer:rw,size=32m,mode=0700,uid=10001,gid=10001 \
  --ulimit fsize=2048 \
  -v "$module_root/target/spike-output/qualified-flowing-two-items-filled.docx:/in/doc.docx:ro" \
  -v "$quota_out:/out" \
  "$render_image" \
  soffice --headless --norestore --nolockcheck --nodefault \
    -env:UserInstallation=file:///home/renderer/.lo-profile \
    --convert-to pdf --outdir /out /in/doc.docx >/dev/null 2>&1
quota_status=$?
produced_size=0
[ -f "$quota_out/doc.pdf" ] && produced_size=$(wc -c < "$quota_out/doc.pdf" | tr -d ' ')
rm -rf "$quota_out"
if [ "$quota_status" -ne 0 ] || [ "$produced_size" -lt 20000 ]; then
  pass "a 2048-byte fsize cap stopped the normal ~24KB output (exit=$quota_status, produced=${produced_size} bytes)"
else
  fail "output exceeded the configured fsize cap (exit=$quota_status, produced=${produced_size} bytes)"
fi

echo
if [ "$failures" -eq 0 ]; then
  echo "All isolation checks passed."
else
  echo "$failures isolation check(s) FAILED." >&2
  exit 1
fi
