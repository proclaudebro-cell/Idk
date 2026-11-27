#!/usr/bin/env bash
# Runs the JVM unit tests (pure logic: links, export import, queue, window geometry) without Gradle.
# Needs a prior tools/build-local.sh run (uses its compiled classes).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KOTLIN_DIR="${KOTLIN_DIR:-/root/toolchain/kotlin}"
JUNIT_DIR="${JUNIT_DIR:-/root/toolchain/junit}"
ANDROID_JAR_COMPILE="${ANDROID_JAR_COMPILE:-/root/dl-android36/android.jar}"
BUILD_TYPE="${BUILD_TYPE:-debug}"
MAIN_CLASSES="$ROOT/build-local/$BUILD_TYPE/classes"
OUT="$ROOT/build-local/test-classes"
STDLIB="$(ls "$KOTLIN_DIR"/kotlin-stdlib-[0-9]*.jar | head -1)"
KCP="$(ls "$KOTLIN_DIR"/*.jar | tr '\n' ':')"
JUNIT="$(ls "$JUNIT_DIR"/*.jar | tr '\n' ':')"
rm -rf "$OUT" && mkdir -p "$OUT"
java -Xmx2g -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 \
  -classpath "$MAIN_CLASSES:$ANDROID_JAR_COMPILE:$STDLIB:$JUNIT" -d "$OUT" "$ROOT/app/src/test/java" 2>&1 | { grep -v '^Picked up' || true; }
TESTS=$(cd "$OUT" && find . -name '*Test.class' | sed 's|^\./||; s|\.class$||; s|/|.|g' | sort)
# android.jar is deliberately not on the runtime classpath: the tested classes must not need Android.
RESULT=$(java -cp "$OUT:$MAIN_CLASSES:$STDLIB:$JUNIT" org.junit.runner.JUnitCore $TESTS 2>&1 | { grep -v '^Picked up' || true; })
echo "$RESULT"
echo "$RESULT" | grep -q '^OK (' || { echo "TESTS FAILED"; exit 1; }
