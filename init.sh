#!/usr/bin/env bash
#
# init.sh — Verification entry point for chess-backend-java.
#
# This script is the single source of truth for "does this project work?".
# A passing run is the only acceptable evidence that a feature is done.
#
# Steps:
#   1. Sanity checks (Java, Maven wrapper, required files)
#   2. feature_list.json invariants (at most one in_progress feature)
#   3. Compile
#   4. Unit + integration tests (Testcontainers requires Docker running)
#   5. Verify (Maven's verify phase = full lifecycle)
#
# Exit code 0 = green. Any non-zero = stop and read the output.

set -euo pipefail

# --- Colors (only when stdout is a TTY) ---
if [ -t 1 ]; then
  GREEN='\033[0;32m'
  YELLOW='\033[1;33m'
  RED='\033[0;31m'
  NC='\033[0m'
else
  GREEN=''
  YELLOW=''
  RED=''
  NC=''
fi

info() { printf "${YELLOW}==>${NC} %s\n" "$1"; }
ok() { printf "${GREEN}✔${NC}  %s\n" "$1"; }
fail() {
  printf "${RED}✘${NC}  %s\n" "$1" >&2
  exit 1
}

# --- Step 1: Sanity ---
info "Sanity checks"

if ! command -v java >/dev/null 2>&1; then
  fail "java not found on PATH"
fi

JAVA_MAJOR=$(java -version 2>&1 | head -n1 | awk -F'"' '{print $2}' | cut -d. -f1)
if [ "${JAVA_MAJOR}" -lt 21 ]; then
  fail "Java 21+ required (found: ${JAVA_MAJOR})"
fi
ok "Java ${JAVA_MAJOR} found"

if [ ! -x "./mvnw" ]; then
  fail "Maven wrapper (./mvnw) not found or not executable"
fi
ok "Maven wrapper present"

for f in pom.xml CLAUDE.md AGENTS.md feature_list.json; do
  if [ ! -f "${f}" ]; then
    fail "Required file missing: ${f}"
  fi
done
ok "Required files present"

# --- Step 2: feature_list.json invariants ---
info "feature_list.json invariants"

if ! command -v jq >/dev/null 2>&1; then
  fail "jq is required to validate feature_list.json. Install: brew install jq"
fi

IN_PROGRESS_COUNT=$(jq '[.[] | select(.status == "in_progress")] | length' feature_list.json)
if [ "${IN_PROGRESS_COUNT}" -gt 1 ]; then
  fail "More than one feature is in_progress (${IN_PROGRESS_COUNT}). Only one is allowed at a time."
fi
ok "At most one feature in_progress (${IN_PROGRESS_COUNT})"

PENDING_COUNT=$(jq '[.[] | select(.status == "pending")] | length' feature_list.json)
DONE_COUNT=$(jq '[.[] | select(.status == "done")] | length' feature_list.json)
info "Feature counts — pending: ${PENDING_COUNT}, in_progress: ${IN_PROGRESS_COUNT}, done: ${DONE_COUNT}"

# --- Step 3: Compile ---
info "Compile"
./mvnw -q -DskipTests clean compile
ok "Compile succeeded"

# --- Step 4 & 5: Verify (runs unit + integration tests) ---
info "Test + verify"
./mvnw -q verify
ok "Verify succeeded"

# --- Done ---
echo
printf "${GREEN}All checks passed.${NC}\n"
