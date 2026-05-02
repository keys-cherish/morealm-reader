# 发版操作手册（CD）

本项目通过 `.github/workflows/release.yml` 自动化发版。本文是给项目维护者看的「**第一次发版前必读**」与「**每次发版操作清单**」。

---

## 一次性配置：GitHub Secrets

发版前必须在仓库 `Settings → Secrets and variables → Actions → New repository secret` 添加以下 4 个 secret：

| Secret 名 | 取值 |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | keystore 文件的 base64 编码 |
| `RELEASE_STORE_PASSWORD` | keystore 密码 |
| `RELEASE_KEY_ALIAS` | key alias |
| `RELEASE_KEY_PASSWORD` | key 密码 |

### 生成 keystore（如果还没有）

```bash
keytool -genkey -v \
  -keystore release.keystore \
  -alias morealm \
  -keyalg RSA -keysize 2048 \
  -validity 10000
# 按提示设密码、组织信息。alias 可自定义，记下来填到 RELEASE_KEY_ALIAS
```

> ⚠️ **生成后立刻备份 `release.keystore` 到离线安全位置**。一旦丢失将无法继续以同 applicationId 发版（用户需卸载重装），事关重大。

### 把 keystore 转 base64

Linux / macOS：
```bash
base64 -w0 release.keystore | pbcopy   # macOS（直接进剪贴板）
base64 -w0 release.keystore            # Linux（手动复制输出）
```

Windows PowerShell：
```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.keystore")) | clip
```

把输出整段粘贴到 `RELEASE_KEYSTORE_BASE64` secret 的值里（**不要有换行**）。

### `.gitignore` 检查

仓库 `.gitignore` 已包含 `*.jks` / `*.keystore` / `local.properties`，**严禁**把 keystore 或密码 commit 到仓库。

---

## 每次发版操作清单

### 1. bump versionName

编辑 `app/build.gradle.kts`：
```kotlin
defaultConfig {
    versionCode = 2                       // ← 必须 +1（Play 商店 / 升级判定都看它）
    versionName = "1.0.0-alpha2"          // ← 改成新版本号
}
```

> 版本号约定：`major.minor.patch[-preRelease]`。`-alpha1/-alpha2/-beta1/-rc1` 会被 release.yml 自动标记为预发布。

### 2. 写 CHANGELOG.md

把 `CHANGELOG.md` 顶部 `## [Unreleased]` section 中的内容**剪切**到下面新增的 `## [1.0.0-alpha2] - YYYY-MM-DD` section，给 Unreleased 留空白等下个迭代用。

最小模板：
```markdown
## [Unreleased]

## [1.0.0-alpha2] - 2026-05-15

### Added
- 新功能 A
- 新功能 B

### Fixed
- 修了 XXX 崩溃

### Changed
- 调了 YYY 行为
```

> ⚠️ release.yml 会从 `## [TAG]` 抽到下一个 `## [` 之间作为 release notes，缺这个 section 会让发版构建失败。

### 3. commit & push

```bash
git add app/build.gradle.kts CHANGELOG.md
git commit -m "chore: release 1.0.0-alpha2"
git push origin <你的分支>
```

如果你在 feature 分支，先合并到 main 再打 tag。

### 4. 打 tag 触发发版

```bash
git tag v1.0.0-alpha2
git push --tags
```

> tag 必须以 `v` 开头，后面接的版本号必须与 `versionName` 完全一致（release.yml 第一道 gate 会校验，不一致直接 fail）。

### 5. 看 Actions 跑通

打开 `https://github.com/keys-cherish/morealm-reader/actions/workflows/release.yml`，确认绿勾。失败时按错误信息排查（最常见：tag 与 versionName 不一致 / CHANGELOG 缺 section / 缺 secret）。

### 6. 验收

- 打开 `https://github.com/keys-cherish/morealm-reader/releases`，确认有新 release，附带 `MoRealm-1.0.0-alpha2.apk`，正文是你写的 changelog section。
- 在你的设备上点开应用 → 我的 → 关于 → 检查更新，应该提示「当前已是最新版本」。
- 在一个低版本设备上点检查更新，应该弹出 Dialog 引导下载。

---

## 发版失败回滚

如果 release 已经创建但你发现严重问题：

```bash
# 删除 GitHub release（保留 tag）
gh release delete v1.0.0-alpha2 --yes

# 或同时删除 tag
gh release delete v1.0.0-alpha2 --yes
git push --delete origin v1.0.0-alpha2
git tag -d v1.0.0-alpha2

# 修完后重新走 1-4
```

---

## CI gate 概览（release.yml 内置 5 道闸门）

| 闸门 | 失败原因 | 修法 |
|---|---|---|
| ① tag 与 versionName 一致 | 改了 tag 没改 gradle / 反之 | 同步两边后重打 tag |
| ② CHANGELOG.md 有对应 section | 忘记写 changelog | 补上 `## [X.Y.Z]` section |
| ③ 单元测试通过 | 测试挂了 | 修测试 |
| ④ 4 个签名 secret 齐全 | 漏配 secret | 补 secret |
| ⑤ APK 实际产出 | 签名配置错 / Gradle 编译错 | 看 log |

任何一道挂了都不会发版，安全。
