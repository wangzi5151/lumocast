#!/usr/bin/env bash
# 一条命令本地出全部产物（需要：JDK 17、Android SDK、Node 18+）
set -euo pipefail
cd "$(dirname "$0")/.."

echo "==> 协议一致性测试"
node scripts/protocol-test.mjs

echo "==> Android APK"
(cd android && gradle :app:assembleRelease)
mkdir -p dist
find android/app/build/outputs/apk/release -name '*.apk' -exec cp {} dist/ \;

echo "==> 桌面端资源"
node scripts/prepare-desktop.mjs
(cd desktop && npm install --no-audit --no-fund)

case "$(uname -s)" in
  Darwin) (cd desktop && npx electron-builder --mac --publish never) ;;
  Linux)  (cd desktop && npx electron-builder --linux --publish never) ;;
  *)      (cd desktop && npx electron-builder --win --publish never) ;;
esac

cp desktop/release/*.{AppImage,deb,rpm,dmg,exe} dist/ 2>/dev/null || true
echo "==> 完成，产物在 dist/ 和 desktop/release/"
