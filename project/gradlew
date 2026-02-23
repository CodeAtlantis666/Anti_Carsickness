#!/usr/bin/env sh
set -e

WRAPPER_JAR="$(dirname "$0")/gradle/wrapper/gradle-wrapper.jar"
if [ -f "$WRAPPER_JAR" ]; then
  exec "${JAVA_HOME:-/usr}/bin/java" -jar "$WRAPPER_JAR" "$@"
fi

if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi

echo "[ERROR] gradle-wrapper.jar not found and no global gradle command available."
exit 1