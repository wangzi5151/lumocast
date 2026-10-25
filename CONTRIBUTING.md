# 贡献指南

## 最快的上手方式

```bash
git clone https://github.com/wangzi5151/lumocast
cd lumocast

# 1. 协议改动一定要先跑这个（17 项 round-trip，1 秒）
node scripts/protocol-test.mjs

# 2. 安卓端
cd android && gradle :app:assembleDebug

# 3. 桌面端
cd desktop && npm install && npm start
```

没有 Android SDK 也能改协议和桌面端 —— 这是最省事的贡献路径。

## 改协议的铁律

`Protocol.kt`、`protocol.js`、`Protocol.swift` **必须逐字节一致**。

1. 先改 `web/js/protocol.js`（它最好测）
2. 跑 `node scripts/protocol-test.mjs`，测试通过
3. 照着逐行改 Kotlin / Swift
4. 在 `scripts/protocol-test.mjs` 里加一条「已知向量」测试，写死期望的十六进制

字段位置、大小端、字节序错一个，症状都是「黑屏但不报错」，非常难查。所以宁可多写测试。

## 代码风格

- Kotlin：4 空格缩进，`ktlint` 默认规则，Compose 函数用 CamelCase
- JS：2 空格，ESM，无框架、无构建步骤（`web/` 里的文件必须能直接被浏览器加载）
- Swift：4 空格，`swiftformat` 默认规则
- 注释写**为什么**，不写**做什么**。`// 取反是因为…` 有意义，`// 这里是 if` 没意义

## 提交信息

```
feat(android): 支持 1440p 编码
fix(protocol): 修正分片重组时的边界检查
perf(host): 合并头与负载为单次 write
docs: 补充音画同步的设计取舍
```

## 提交前自查

- [ ] `node scripts/protocol-test.mjs` 全绿
- [ ] 真机跑过（模拟器测不出编码器/播放器的真实行为）
- [ ] 没有引入新依赖，或者能说清为什么必须引入
- [ ] 改了 UI 的话附截图

## 我们不接受

- 把码流中转到云端的改动（违背产品定位）
- 加广告、加遥测、加账号体系
- 引入 React/Vue 等前端框架（`web/` 是零构建的）
- 用 Gradle 插件以外的方式改构建（保持 `gradle assembleRelease` 一条命令能出包）

## 分支与 PR

- 从 `main` 切 `feat/xxx` 或 `fix/xxx`
- 一个 PR 只做一件事
- CI 会跑 Android 编译 + 协议测试，两个都要绿
