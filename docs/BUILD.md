# 构建与发布

## 0. 一次性准备

| 平台 | 需要 |
| --- | --- |
| Android | JDK 17（Temurin）、Android SDK Platform 34 + Build-Tools 34.0.0 |
| Desktop | Node 20+、Linux 上打 `.deb` 需 `fakeroot`、打 Windows 包在 Linux 上需 `wine`（CI 用 windows-latest 原生跑） |
| iOS | macOS 14 + Xcode 15 + `brew install xcodegen` |
| Server | Node 20+ |

## 1. Android

```bash
cd android
./gradlew :app:assembleDebug          # 本地调试，可直接 adb install
./gradlew :app:assembleRelease        # 未签名（无 keystore 时）
./gradlew :app:bundleRelease          # AAB，上架 Google Play 用
```

### 签名

```bash
keytool -genkeypair -v -keystore lumocast-release.jks -keyalg RSA -keysize 4096 \
        -validity 10000 -alias lumocast
```

在 `android/keystore.properties`（**已 gitignore**）里：

```properties
storeFile=/absolute/path/lumocast-release.jks
storePassword=***
keyAlias=lumocast
keyPassword=***
```

CI 里用 secrets：`ANDROID_KEYSTORE_BASE64` / `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD`。
见 `.github/workflows/android.yml`。

### 上架清单（Google Play）

- [ ] `targetSdk 34`、`minSdk 26`
- [ ] 前台服务类型声明 `mediaProjection` + `dataSync`（Android 14 强制）
- [ ] `FOREGROUND_SERVICE_MEDIA_PROJECTION` 权限 + 运行时 `createScreenCaptureIntent()`
- [ ] 数据安全表单：**不收集任何数据**（如实填写，这是卖点）
- [ ] 隐私政策 URL：`docs/PRIVACY.md` 发布到 GitHub Pages
- [ ] 分级：3+；无广告声明

## 2. 桌面（Windows / Linux / macOS）

```bash
cd desktop
npm ci
npm start                    # 开发
npm run dist:linux           # AppImage + deb + rpm
npm run dist:win             # NSIS 安装包 + portable exe
npm run dist:mac             # dmg（arm64 + x64）
```

产物在 `desktop/release/`。`electron-builder.yml` 已配好：

- Linux：`AppImage`（免安装）、`deb`、`rpm`；图标 `icon.png` 512²
- Windows：`nsis`（含开始菜单 + 桌面快捷方式）+ `portable`；**未签名**时 SmartScreen 会警告，
  正式发行需 EV 代码签名证书（`CSC_LINK` / `CSC_KEY_PASSWORD`）
- macOS：`dmg`，`hardenedRuntime: true` + `entitlements.mac.plist`，公证用 `notarytool`

### macOS 公证

```bash
xcrun notarytool submit release/LumoCast-1.0.0-arm64.dmg \
  --apple-id "$APPLE_ID" --team-id "$TEAM_ID" --password "$APP_PASSWORD" --wait
xcrun stapler staple release/LumoCast-1.0.0-arm64.dmg
```

## 3. iOS

```bash
cd ios
xcodegen generate
open LumoCast.xcodeproj        # Scheme: LumoCast → 真机 / 模拟器
```

- Bundle ID `com.lumocast.viewer`
- `Info.plist` 已含 `NSLocalNetworkUsageDescription` 与 `NSBonjourServices`（缺了 iOS 14+ 收不到局域网广播）
- 打包 ipa：`xcodebuild archive ... -exportArchive`（`docs/ExportOptions.plist`）
- 分发：TestFlight（推荐）或 Ad Hoc；App Store 审核需说明「仅局域网接收，无内容分发」

## 4. 服务端（可选）

```bash
cd server
npm ci
cp .env.example .env         # 填 STRIPE_SECRET / LICENSE_PRIVATE_KEY / WEBHOOK_SECRET
npm start
```

只做三件事：Stripe Checkout → Webhook → 用私钥签 license JSON。**不做流媒体中继**，
所以不需要带宽成本，也能私有化部署。

## 5. 一键全平台

```bash
./scripts/build-all.sh            # 检测本机能力，跳过缺失的工具链
./scripts/build-all.sh --android  # 只打 Android
```

## 6. 发版流程

```bash
# 1. 改 android/app/build.gradle.kts 的 versionCode/versionName 与 desktop/package.json 的 version
# 2. 更新 CHANGELOG.md
# 3. 打 tag 触发 CI
git tag v1.0.0 && git push origin v1.0.0
```

`release.yml` 会在 tag 上并行跑：Android APK（签名）、Linux/Windows/macOS 安装包、
把全部产物 + `checksums.txt` 附到 GitHub Release，并把 release notes 从 CHANGELOG 里截出来。

## 7. 常见问题

| 问题 | 解法 |
| --- | --- |
| `./gradlew` 权限错误 | `chmod +x android/gradlew` |
| Gradle 卡在下载 | 配 `~/.gradle/gradle.properties` 里的代理，或改用国内镜像 |
| 打包后 AppImage 打不开 | `chmod +x *.AppImage`，若报 FUSE 缺失：`--appimage-extract-and-run` |
| Windows 上 `npm run dist:linux` 失败 | 不支持交叉打 deb，交给 CI 的 ubuntu job |
| iOS 模拟器收不到设备 | 模拟器与手机不在同一网段，必须用真机测发现 |
