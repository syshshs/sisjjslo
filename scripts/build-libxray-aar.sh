#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${ROOT}/.native/libXray"
OUT="${ROOT}/app/libs/libXray.aar"

command -v git >/dev/null || { echo "git is required"; exit 1; }
command -v go >/dev/null || { echo "Go is required"; exit 1; }
command -v python3 >/dev/null || { echo "python3 is required"; exit 1; }

mkdir -p "${ROOT}/.native"
if [[ ! -d "${WORK}/.git" ]]; then
  git clone --depth 1 https://github.com/XTLS/libXray.git "${WORK}"
else
  git -C "${WORK}" fetch --depth 1 origin main
  git -C "${WORK}" reset --hard origin/main
fi

cd "${WORK}"
python3 build/main.py android
mkdir -p "$(dirname "${OUT}")"
cp -f libXray.aar "${OUT}"
echo "Built ${OUT}"
