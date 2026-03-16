#!/bin/sh
# Gradle wrapper script - generated
APP_HOME="$(cd "$(dirname "$0")" && pwd)"
exec "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" "$@" 2>/dev/null || \
  java -jar "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" "$@"
