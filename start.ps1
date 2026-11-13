# Starts SmartCodeReview locally: builds the UI on first run (or with -Rebuild), then Quarkus dev mode.
param([switch]$Rebuild)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
if ($Rebuild -or -not (Test-Path "backend/src/main/resources/META-INF/resources/index.html")) {
    Push-Location frontend
    if (-not (Test-Path node_modules)) { npm ci }
    npm run build
    Pop-Location
}
Set-Location backend
.\mvnw.cmd quarkus:dev "-Dquarkus.console.enabled=false"
