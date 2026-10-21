#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/classes
find src/main/java -name '*.java' | sort > build/main-sources.txt
java -m jdk.compiler/com.sun.tools.javac.Main --release 17 -Xlint:all -Werror -d build/classes @build/main-sources.txt
exec java -cp build/classes com.oshokry.shortener.Main
