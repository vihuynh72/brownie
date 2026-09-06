#!/usr/bin/env bash

set -u

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
repository_root="$(CDPATH= cd -- "$script_dir/.." && pwd)"

# shellcheck source=../tooling/versions.env
. "$repository_root/tooling/versions.env"

failures=0

pass() {
  printf 'PASS: %s\n' "$1"
}

info() {
  printf 'INFO: %s\n' "$1"
}

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  failures=1
}

check_exact() {
  label="$1"
  expected="$2"
  actual="$3"

  if [ "$actual" = "$expected" ]; then
    pass "$label $actual"
  else
    fail "$label expected $expected, found ${actual:-missing}"
  fi
}

if command -v java >/dev/null 2>&1; then
  java_output="$(java -version 2>&1)"
  java_version="$(printf '%s\n' "$java_output" | sed -n 's/.*version "\([^"]*\)".*/\1/p' | sed -n '1p')"
  check_exact "Java version" "$BROWNIE_JAVA_VERSION" "$java_version"

  if printf '%s\n' "$java_output" | grep -Fq "$BROWNIE_JAVA_VENDOR"; then
    pass "Java vendor $BROWNIE_JAVA_VENDOR"
  else
    fail "Java vendor expected $BROWNIE_JAVA_VENDOR"
  fi

  if printf '%s\n' "$java_output" | grep -Fq "build $BROWNIE_JAVA_RELEASE"; then
    pass "Java release $BROWNIE_JAVA_RELEASE"
  else
    fail "Java release expected $BROWNIE_JAVA_RELEASE"
  fi
else
  fail "Java is missing"
fi

if command -v javac >/dev/null 2>&1; then
  javac_version="$(javac -version 2>&1 | awk '{print $2}')"
  check_exact "javac version" "$BROWNIE_JAVA_VERSION" "$javac_version"
else
  fail "javac is missing"
fi

if command -v node >/dev/null 2>&1; then
  node_version="$(node --version | sed 's/^v//')"
  check_exact "Node version" "$BROWNIE_NODE_VERSION" "$node_version"
else
  fail "Node is missing"
fi

if command -v npm >/dev/null 2>&1; then
  npm_version="$(npm --version)"
  check_exact "npm version" "$BROWNIE_NPM_VERSION" "$npm_version"
else
  fail "npm is missing"
fi

nvmrc_version="$(tr -d '[:space:]' < "$repository_root/.nvmrc")"
check_exact ".nvmrc" "$BROWNIE_NODE_VERSION" "$nvmrc_version"

if [ -x "$repository_root/backend/mvnw" ]; then
  maven_version="$("$repository_root/backend/mvnw" -version 2>&1 | awk '/Apache Maven/ {print $3; exit}')"
  check_exact "Maven Wrapper version" "$BROWNIE_MAVEN_VERSION" "$maven_version"
else
  info "Maven Wrapper is not present. Target $BROWNIE_MAVEN_VERSION is recorded."
fi

info "Spring Boot $BROWNIE_SPRING_BOOT_VERSION and Spring AI $BROWNIE_SPRING_AI_VERSION are verified when their build descriptors are introduced."
info "Development platform recorded as $BROWNIE_DEVELOPMENT_PLATFORM."

if [ "$failures" -ne 0 ]; then
  exit 1
fi
