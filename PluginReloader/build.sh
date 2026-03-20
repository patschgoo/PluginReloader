#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT_DIR"

OUT_DIR="$ROOT_DIR/build"
CLASS_DIR="$OUT_DIR/classes"
JAR_PATH="$OUT_DIR/PluginReloader.jar"
BKT_JAR="$ROOT_DIR/../libs/craftbukkit-1060 bukkit.jar"

rm -rf "$OUT_DIR"
mkdir -p "$CLASS_DIR"

javac -source 1.8 -target 1.8 -cp "$BKT_JAR" -d "$CLASS_DIR" $(find src -name "*.java")
cp plugin.yml "$CLASS_DIR/plugin.yml"
jar cf "$JAR_PATH" -C "$CLASS_DIR" .

echo "Built: $JAR_PATH"
