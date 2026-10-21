#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/test-classes
find src/main/java src/test/java -name '*.java' | sort > build/test-sources.txt
java -m jdk.compiler/com.sun.tools.javac.Main --release 17 -Xlint:all -Werror -d build/test-classes @build/test-sources.txt
exec java -ea -cp build/test-classes com.oshokry.shortener.ShortenerTests
