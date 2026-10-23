#!/usr/bin/env bash
# Run Gradle tasks for this app on a Linux build host, not on this Mac.
#
# There is no usable JVM on the Macs this repo is developed on, so this script
# rsyncs the tree to a host with Docker and runs Gradle inside
# eclipse-temurin:17-jdk, the same JDK major version CI uses. The Android SDK
# and the Gradle cache live in persistent directories on the host, so only the
# first run pays the downloads.
#
# It is a faster pre-check, not a replacement for CI. Never report a green run
# here as "CI passed".
#
#   scripts/remote-gradle.sh testDebugUnitTest
#   scripts/remote-gradle.sh 'assembleDebug lintDebug testDebugUnitTest'
#
# CONFIGURATION (required, no defaults)
# -------------------------------------
# Where the build runs is never written in this public repository. Set these in
# the environment, or in scripts/remote-gradle.env (git-ignored, read if
# present). The environment wins over the file.
#
#   PUMPERLY_BUILD_HOST=user@build-host   # ssh target with Docker and rsync
#   PUMPERLY_BUILD_ROOT=/path/on/host     # holds sdk/, gradle/ and src/
#   PUMPERLY_GRADLE_WORKERS=4             # optional, Gradle --max-workers
#
# Give every checkout its own PUMPERLY_BUILD_ROOT: the sync below deletes
# anything in $PUMPERLY_BUILD_ROOT/src that is not in this tree. sdk/ and gradle/
# may be symlinks to directories shared between roots.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

ENV_HOST="${PUMPERLY_BUILD_HOST:-}"
ENV_ROOT="${PUMPERLY_BUILD_ROOT:-}"
ENV_WORKERS="${PUMPERLY_GRADLE_WORKERS:-}"
if [ -f "$HERE/scripts/remote-gradle.env" ]; then
  # shellcheck source=/dev/null
  . "$HERE/scripts/remote-gradle.env"
fi
HOST="${ENV_HOST:-${PUMPERLY_BUILD_HOST:-}}"
ROOT="${ENV_ROOT:-${PUMPERLY_BUILD_ROOT:-}}"
WORKERS="${ENV_WORKERS:-${PUMPERLY_GRADLE_WORKERS:-4}}"
if [ -z "$HOST" ] || [ -z "$ROOT" ]; then
  echo "remote-gradle.sh: set PUMPERLY_BUILD_HOST and PUMPERLY_BUILD_ROOT," >&2
  echo "in the environment or in scripts/remote-gradle.env (see this script's header)." >&2
  exit 2
fi
TASKS="${*:-testDebugUnitTest}"

# .beads is excluded: tracker data never leaves this machine, not even to a
# private host.
echo "==> syncing $HERE -> $HOST:$ROOT/src"
# Results from an earlier run must never be read as this run's, here or there.
REPORT_DIRS="app/build/test-results app/build/reports"
ssh "$HOST" "mkdir -p '$ROOT/src' '$ROOT/sdk' '$ROOT/gradle' && cd '$ROOT/src' && rm -rf $REPORT_DIRS"
(cd "$HERE" && rm -rf $REPORT_DIRS)
rsync -a --delete \
  --exclude '.git' --exclude '.beads' --exclude 'build/' --exclude '.gradle' \
  --exclude 'scripts/remote-gradle.env' --exclude 'local.properties' \
  "$HERE/" "$HOST:$ROOT/src/"

echo "==> gradle $TASKS (max workers $WORKERS)"
status=0
ssh "$HOST" "docker run --rm \
  -v $ROOT/sdk:/sdk \
  -v $ROOT/src:/src \
  -v $ROOT/gradle:/root/.gradle \
  -w /src -e ANDROID_HOME=/sdk -e ANDROID_SDK_ROOT=/sdk \
  eclipse-temurin:17-jdk bash -lc './gradlew --no-daemon --max-workers=$WORKERS $TASKS'" || status=$?

# Bring back the test and lint reports, pass or fail, so the JUnit XML can be
# read here. A BUILD SUCCESSFUL line alone does not say how many tests ran.
for dir in $REPORT_DIRS; do
  if ssh "$HOST" "test -d '$ROOT/src/$dir'"; then
    echo "==> fetching $dir"
    mkdir -p "$HERE/$dir"
    rsync -a --delete "$HOST:$ROOT/src/$dir/" "$HERE/$dir/"
  fi
done

exit "$status"
