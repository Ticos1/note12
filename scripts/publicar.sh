#!/usr/bin/env bash
# Compila o APK e publica em apk/ junto com apk/versao.json, que o app consulta para se
# atualizar sozinho. Antes de rodar, suba versionCode/versionName em app/build.gradle.kts.
# Depois: git add apk && git commit && git push.
set -euo pipefail
cd "$(dirname "$0")/.."

codigo=$(sed -nE 's/^\s*versionCode = ([0-9]+).*/\1/p' app/build.gradle.kts)
nome=$(sed -nE 's/^\s*versionName = "([^"]+)".*/\1/p' app/build.gradle.kts)

./gradlew assembleRelease -q "$@"
cp app/build/outputs/apk/release/app-release.apk apk/MultiIdle.apk
sha=$(sha256sum apk/MultiIdle.apk | cut -d' ' -f1)

cat > apk/versao.json <<JSON
{
  "versionCode": $codigo,
  "versionName": "$nome",
  "apk": "https://raw.githubusercontent.com/Ticos1/note12/ccr-226ce79c-dpfygw/apk/MultiIdle.apk",
  "sha256": "$sha"
}
JSON
echo "Publicado: v$nome ($codigo) sha256=$sha"
