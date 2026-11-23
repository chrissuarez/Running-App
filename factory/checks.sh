#!/usr/bin/env bash
# The repo's checks. Exit 0 only when the branch is fit to review.
#
# The gate runs this script from the main checkout with the job's worktree as
# the working directory. Keep it fast, deterministic and non-interactive: no
# watch modes, no prompts, nothing that rewrites tracked files.
#
# JVM unit tests only. NEVER add connectedAndroidTest, connectedCheck,
# installDebug or anything else that talks to a device: on Chris's phone
# Gradle uninstalls the app first, which wipes his run history.
#
# Needs local.properties (sdk.dir and API keys); factory/bin/job copies the
# main checkout's copy into each worktree. gradlew may lack its exec bit, so
# it is called through bash.
set -euo pipefail

if [ ! -f local.properties ]; then
  echo "local.properties is missing in $(pwd). Copy it from the main checkout." >&2
  exit 1
fi

bash ./gradlew --no-daemon testDebugUnitTest
