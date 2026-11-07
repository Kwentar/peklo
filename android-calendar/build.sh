#!/bin/sh
set -eu
cd "$(dirname "$0")"
if [ -z "${JAVA_HOME:-}" ] && [ -d tooling/jdk-17.0.21+9/Contents/Home ]; then
 export JAVA_HOME="$PWD/tooling/jdk-17.0.21+9/Contents/Home"
fi
if [ -d tooling/gradle-8.9 ]; then
 export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$PWD/tooling/gradle-cache}"
 exec tooling/gradle-8.9/bin/gradle :app:assembleDebug :app:testDebugUnitTest --console=plain "$@"
fi
exec gradle :app:assembleDebug :app:testDebugUnitTest --console=plain "$@"
