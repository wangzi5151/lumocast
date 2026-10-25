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

---

## 6. CI 一键出包（推荐）

仓库自带两条 workflow，**不需要本地装 Android SDK / Xcode**：

### 6.1 日常验证（`.github/workflows/ci.yml`）

push 到 `main` 自动触发，跑三件事：

| Job | 内容 | 耗时 |
| --- | --- | --- |
| 协议一致性 | `node scripts/protocol-test.mjs`（17 项） | ~10s |
| Android APK | `gradle :app:assembleRelease`，产物上传为 artifact | ~3min |
| 桌面端可加载 | 语法检查 + `desktop/web` 装配验证 | ~15s |

### 6.2 发版（`.github/workflows/release.yml`）

推一个 tag 就出全平台安装包：

```bash
git tag -a v1.0.1 -m "修了什么"
git push origin v1.0.1
```

四个平台并行构建，全部完成后由 `publish` job 汇总到 GitHub Release：

```
android → LumoCast-<ver>.apk
linux   → LumoCast-<ver>.AppImage, lumocast_<ver>_amd64.deb
windows → LumoCast-<ver>-x64.exe, LumoCast-<ver>-portable.exe
macos   → LumoCast-<ver>-arm64-mac.zip, LumoCast-<ver>-x64-mac.zip
```

`checksums.txt` 会一起附在 Release 里。

### 6.3 正式签名（可选）

CI 默认用 debug key 签名（**能直接安装**，适合内测）。要正式签名，在仓库 Settings → Secrets 里加：

| Secret | 说明 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 release.jks` 的结果 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 口令 |
| `ANDROID_KEY_ALIAS` | key 别名 |
| `ANDROID_KEY_PASSWORD` | key 口令 |

加完之后 release 流程会自动切到正式签名，APK 就可以覆盖安装旧版本（签名不同会导致「应用未安装」）。

---

## 7. iOS 端（需要 Mac）

```bash
cd ios
brew install xcodegen        # 只需一次
xcodegen generate            # 从 project.yml 生成 LumoCast.xcodeproj
open LumoCast.xcodeproj
```

然后在 Xcode 里：

1. **Signing & Capabilities** → 选你的 Team（免费 Apple ID 也可以，7 天有效期）
2. 连上 iPhone，选中设备，⌘R

真机第一次启动会弹「LumoCast 想要查找并连接到本地网络上的设备」→ 必须点**允许**，否则收不到 UDP 广播（可用二维码/手输 IP 绕过）。

> 为什么 iOS 没有预编译包：iOS 侧载需要 Apple 开发者签名，无法像 Android 那样给一个通用 APK。
> 如果你有开发者账号，可以归档后走 TestFlight 分发给自己和朋友。
