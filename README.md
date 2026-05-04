<p align="center">
  <img src="docs/app-icon.png" width="120" alt="墨境 MoRealm" />
</p>

<h1 align="center">墨境 MoRealm</h1>

<p align="center">
  <strong>一款现代化的 Android 电子书阅读器</strong><br>
  基于 Jetpack Compose · 兼容 Legado 书源 · GPL-3.0 + 商业双许可
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white" />
  <img src="https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white" />
  <img src="https://img.shields.io/badge/Compose-Material3-4285F4?logo=jetpackcompose&logoColor=white" />
  <img src="https://img.shields.io/badge/Min%20SDK-26-3DDC84" />
  <img src="https://img.shields.io/badge/License-GPL--3.0%20%2F%20Commercial-blue" />
</p>

<p align="center">
  <a href="https://github.com/keys-cherish/morealm-reader/releases/latest"><strong>下载最新版 APK ↓</strong></a>
  &nbsp;·&nbsp;
  <a href="docs/user-guide.md">使用指南</a>
  &nbsp;·&nbsp;
  <a href="CHANGELOG.md">更新日志</a>
  &nbsp;·&nbsp;
  <a href="#反馈与贡献">反馈</a>
</p>

---

## 目录

- [功能亮点](#功能亮点)
- [技术栈](#技术栈)
- [系统要求](#系统要求)
- [构建](#构建)
- [项目结构](#项目结构)
- [使用 Legado 书源](#使用-legado-书源)
- [反馈与贡献](#反馈与贡献)
- [致谢](#致谢)
- [许可证](#许可证)

---

## 功能亮点

### 阅读体验
- **多格式支持** — EPUB / TXT / PDF / MOBI / AZW3 / CBZ / CBR
- **自研 Canvas 排版** — 段落级 LazyColumn 瀑布流引擎，长章节顺滑滚动
- **5 种翻页动画** — 平移 / 覆盖 / 仿真 / 上下滚动 / 无动画，按章节预排版
- **精细排版控制** — 字体 / 字号 / 行距 / 段距 / 页边距 / 繁简转换 / 自定义 CSS
- **5 套阅读预设** — 纸质 / 护眼 / 海蓝 / 暖黄 / 墨白，自动适配日夜模式
- **可配置选区菜单** — 按钮顺序 / 显示项均可自定义
- **5 色高亮系统** — 选区→DB 持久化，跨设备恢复
- **物理键翻页** — 音量键 / 蓝牙翻页器 / 耳机键全键位

### 听书 (TTS)
- **多引擎可切** — Edge TTS（流式 + 本地 LRU 缓存）/ 系统 TTS / OpenAI / 自定义 API
- **MediaStyle 通知** — 书名 / 章节 / 封面 / 上一段 / 暂停 / 下一段 / 倒计时
- **+10 分钟 倒计时** — 通知栏直接续费睡眠模式

> 📌 TTS 仍在持续打磨，部分远端引擎稳定性受网络环境影响，问题请提 issue。

### 书源与搜索
- **兼容 Legado 书源** — CSS / XPath / JSONPath / Regex 四模式规则引擎
- **书源管理** — 分组 chip（不分组 / 分组名 / 域名 / 类型），状态持久化
- **CheckSource 一键检测** — 失败原因持久化，下次打开仍可见
- **后台刷新书架** — 并发拉所有 WEB 书目录，新章节红色「N 新」角标
- **搜索元信息** — 结果卡片显示「最新章节 / 字数 / 分类」
- **搜索历史** — 自动记录，可清空

### 同步与备份
- **WebDAV 全套** — 备份 / 恢复 / 进度同步 / 自动调度，对照 Legado 接口
- **本地 ZIP 备份** — 可勾选导出类别（书籍 / 书源 / 进度 / 主题 / 阅读样式）
- **多设备防覆盖** — 备份文件名带设备名后缀
- **历史版本恢复** — 列出云端 backup_*.zip 可挑时间点恢复
- **DB 升级前自动备份** — 新版本崩库不丢数据

### 主题与外观
- **6 套内置主题** — 墨境 / 纸上 / 赛博朋克 / 森林 / 深夜 / 墨水屏
- **自定义主题** — 完整的 ThemeEntity，支持导入导出 / Legado 主题包
- **全局背景图** — 4 个 Tab 共享，可调透明度 / 模糊度
- **日夜双背景图** — 阅读器单独配置

### 多标签 / 自动归类
- **多标签系统** — book_tags 多对多 + tag_definitions 词表（v17 schema）
- **15 个内置题材标签** — 修真 / 玄幻 / 都市 / 言情 等，关键词可编辑
- **5 层评分瀑布** — 用户关键词 → 元数据 → 简介 → 标题 → 来源/格式
- **自动建文件夹** — 题材累计 ≥ 3 本时自动建（带 emoji），用户手建文件夹永不被自动归类引擎改动

### 其它
- **Legado 一键搬家** — 书源 / 书籍 / 进度 / 主题一次性接管
- **章节缓存导出 TXT** — 含书名 / 作者 / 简介 / 净化后正文
- **检查更新** — 拉 GitHub Releases 比对版本，弹 Dialog 展示 release notes
- **侧滑返回** — 全 App 一致的手势

> 📖 完整使用指南请查看 [docs/user-guide.md](docs/user-guide.md)
> 🔄 历次发版的详细变更见 [CHANGELOG.md](CHANGELOG.md)

---

## 技术栈

| 层 | 选型 |
|---|---|
| UI | Jetpack Compose · Material 3 · Compose Navigation |
| 状态 | StateFlow / SharedFlow · Hilt DI |
| 数据 | Room · DataStore · Coil 图片缓存 |
| 网络 | OkHttp（自签证书容错）· QuickJS · Jsoup |
| TTS | Media3 ExoPlayer · Edge TTS WSS · MediaCodec 流式解码 |
| 排版 | 自研 Canvas 渲染（CanvasRenderer / LazyScrollRenderer / SimulationReadView） |
| 异步 | Kotlin Coroutines + Mutex 单飞 |
| 持续集成 | GitHub Actions（CI 测试 + LICENSE 校验 + tag 触发自动发版） |

---

## 系统要求

- **Android 8.0 (API 26)** 及以上
- **JDK 17**（构建时）
- **Gradle 8.x**（项目自带 wrapper，无需手动装）

---

## 构建

```bash
git clone https://github.com/keys-cherish/morealm-reader.git
cd morealm-reader

# Debug
./gradlew assembleDebug          # macOS / Linux
gradlew.bat assembleDebug        # Windows

# Release（需要签名密钥）
./gradlew assembleRelease

# 跑测试（pre-push hook 也会触发）
./gradlew test
```

输出位置：

```
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

> 📌 Release 构建需要在 `~/.gradle/gradle.properties` 或环境变量里配置签名信息，
> 详见 [docs/release-setup.md](docs/release-setup.md)。

---

## 项目结构

```
app/src/main/java/com/morealm/app/
├── domain/                  # 业务核心
│   ├── analyzeRule/         # 书源规则引擎（CSS / XPath / JSONPath / Regex）
│   ├── webbook/             # 网络书：BookList / BookContent / WebBook
│   ├── db/                  # Room：AppDatabase（version 28+）
│   ├── entity/              # 实体：Book / Chapter / ThemeEntity / BookTag …
│   ├── preference/          # AppPreferences（DataStore 包装）
│   ├── repository/          # Repository 层
│   └── sync/                # WebDavClient / 备份 / 恢复
├── presentation/            # ViewModel / Controller
│   ├── reader/              # ReaderChapterController / TtsController …
│   └── shelf/               # ShelfRefreshController（后台书架刷新）
├── ui/                      # Compose 屏幕
│   ├── reader/              # 阅读器主体 + renderer/
│   ├── shelf/ profile/ ...  # 各功能屏幕
│   ├── theme/               # MoRealmTheme / BuiltinThemes
│   └── navigation/          # AppNavHost / PillNavigationBar
├── service/                 # 前台 Service：TtsService / CacheBookService
└── di/                      # Hilt 模块
```

更细的关键文件路径见各模块的 KDoc。

---

## 使用 Legado 书源

MoRealm 兼容 Legado 3.x 的书源格式。

1. 从社区拿到一份 `.json` 书源
2. **我的 → 书源管理 → 右上 + → 从 JSON 导入**（也支持粘贴 / 链接）
3. 导入后在书源列表里启用想用的书源
4. 回书架，**右上 + → 网络搜索**，输入关键词

> 📌 如果某个书源在 Legado 能用、在 MoRealm 不能用，请提 issue 附上书源 JSON
> 与 Legado 的截图对比。

---

## 反馈与贡献

- **Issue / Bug 报告** — [GitHub Issues](https://github.com/keys-cherish/morealm-reader/issues)
- **Pull Request 欢迎** — 大改动建议先开 issue 讨论方向，避免来回
- **测试约定** — `pre-commit` hook 跑快速测试，`pre-push` 跑完整单元测试，请勿用 `--no-verify` 跳过
- **提交规范** — 推荐 [Conventional Commits](https://www.conventionalcommits.org/)（`feat:` / `fix:` / `refactor:` / `docs:` …）

> 项目还在积极迭代中，部分功能（尤其 TTS / 部分书源解析）仍在打磨阶段，
> 欢迎在 issue 里告诉我们你遇到的具体问题。

---

## 致谢

- [Legado](https://github.com/gedoor/legado) — 书源生态、规则引擎设计、备份格式参考
- [Microsoft Edge TTS](https://learn.microsoft.com/azure/cognitive-services/speech-service/) — 远程语音合成
- [Material 3](https://m3.material.io/) — 视觉系统
- [Jsoup](https://jsoup.org/) / [QuickJS](https://bellard.org/quickjs/) / [OkHttp](https://square.github.io/okhttp/) — 解析与网络

---

## 许可证

本项目采用 **双许可模式**：

- **开源使用** — [GPL-3.0](LICENSE)：个人、学习、开源项目免费使用，
  但衍生作品必须以同等条款开源
- **商业使用** — 需要获取商业许可：闭源分发、商业产品集成等场景请联系作者

详见 [LICENSE](LICENSE)。

---

<p align="center">
  <sub>Made with ☕ by 光坂镇 · 觉得有帮助的话给个 ⭐ 就是最好的鼓励</sub>
</p>
