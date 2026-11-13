#!/usr/bin/env sh
# Starts SmartCodeReview locally: builds the UI on first run (or with --rebuild), then Quarkus dev mode.
set -e
cd "$(dirname "$0")"
if [ "$1" = "--rebuild" ] || [ ! -f backend/src/main/resources/META-INF/resources/index.html ]; then
  (cd frontend && { [ -d node_modules ] || npm ci; } && npm run build)
fi
cd backend && exec ./mvnw quarkus:dev -Dquarkus.console.enabled=false
