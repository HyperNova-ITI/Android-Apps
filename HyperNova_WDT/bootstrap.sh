#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LAUNCHER="${ROOT}/../HyperNova_Launcher_Task_01"

if [[ ! -f "${LAUNCHER}/gradlew" ]]; then
  echo "ERROR: Launcher Gradle wrapper not found at:"
  echo "  ${LAUNCHER}/gradlew"
  exit 1
fi

cp "${LAUNCHER}/gradlew" "${ROOT}/gradlew"
chmod +x "${ROOT}/gradlew"

rm -rf "${ROOT}/gradle/wrapper"
mkdir -p "${ROOT}/gradle"
cp -a "${LAUNCHER}/gradle/wrapper" "${ROOT}/gradle/"

echo "Gradle wrapper copied from HyperNova Launcher."
echo
echo "Build UI:"
echo "  cd ${ROOT}"
echo "  ./gradlew :app:assembleDebug"
