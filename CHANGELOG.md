# Changelog

本项目（MoRealm 墨境）的变更记录。
格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [SemVer](https://semver.org/lang/zh-CN/) 子集。

每次发版前：把 `[Unreleased]` 中的内容挪到下方新版本号 section（标题为 `## [X.Y.Z] - YYYY-MM-DD`），然后 `git tag vX.Y.Z && git push --tags` 触发 `release.yml` 自动发布到 GitHub Releases。release.yml 会从本文件抽取对应 section 作为 release notes，section 缺失会构建失败。

## [Unreleased]

### Added
- 设置里加了「自动分组」总开关，默认关闭 —— 之前没启用也会被自动分组的问题没了；想用就去「我的 → 自动分组规则」打开
- 导入单本书有反馈提示了 —— 成功、已在书架、不支持的格式三种情况都会弹 snackbar
- MOBI / AZW3 漫画书能正常显示图片了 —— 按需读取原文件，不占额外存储空间，500MB 漫画也不会让本地缓存翻倍
- MOBI / AZW3 导入时自动提取封面图
- **漫画专属阅读模式** —— 导入 MOBI/AZW3/CBZ 时自动识别漫画书（读文件头几 KB 就能判断，不解全文不卡 UI），打开后走独立的漫画阅读器：图片贴边无内边距，上下零间距条漫，进度条按图片张数显示（"3 / 50"），单击屏幕中央切换顶栏/底栏
- 漫画阅读器进度条复用小说阅读器的胶囊样式 —— 渐变填充 + 嵌入页码反差色显示，跟小说体验统一
- 漫画支持两种翻页模式 —— 底栏 Tab 切换「条漫」（垂直滚动）和「翻页」（左右滑动 + 点击左/右翻页 / 点中央菜单），切换 Tab 时位置同步不丢

### Fixed
- 自动分组阈值形同虚设 —— 设置阈值 3 时实际 1 本就被分组了；现在严格按阈值守门
- 阅读器调字号时文字重叠糊一片的问题 —— 现在拖完字号会等新排版完成一次性切换，中间不会出现错位
- 异形屏 / 挖孔屏顶栏上方空白过大 —— 书架、搜索、我的 三个 Tab 都修了
- 阅读器目录字体偏小、灰得看不清 —— 字号调大一档，颜色加深增强对比度
- 仿真翻页过程中点击中央触发菜单时画面/进度错位 —— 之前 force-commit 翻页只更新了底栏数据但画面停在旧页；现在画面也跟着切到新页
- 漫画检测引入前导入的旧 MOBI/AZW3 书也能自动识别为漫画了 —— 打开时主动检测一次（只读文件头几 KB 不卡），命中后写回标记，下次打开秒走漫画阅读器

## [1.2] - 2026-05-10

UI 全面重设计版本 ✨

聚焦视觉重构、阅读体验细节、若干长期 bug 修复。
继 1.1 稳态优化后，1.2 把首屏书架、tab 栏、日志、进度条、搜索结果几大视觉重灾区
全部按 Material 3 Expressive 重做了一遍，体感"焕然一新"。
DB schema 不变（兼容 1.1 数据库直接覆盖升级）。

### Added
- **竖排版 Phase 1** 排版引擎层加入 `Axis` 抽象 + `vertical/` 渲染目录骨架；EpubParser 已保留 `<rt>` 振假名标签 + `readingDirection` 偏好（实际 UI 入口在后续版本接通）
- **首页书架"我的书架"标题行** 在「继续阅读」卡片下方，左标题右双按钮（排序 + 切换视图），与 顶栏 greeting 形成清晰视觉分隔
- **顶栏副文本显示今日阅读时长** 「今日已阅读 X 小时 Y 分钟」替代原"享受阅读时光"，从 ReadStatsRepository 取当天数据
- **搜索结果来源标签 chip 化** 文本源 primary 容器、非文本源 error 容器；与 SourceTag 视觉系统统一

### Changed
- **进度条全新设计** 阅读器底部进度条填充段从「嵌入式」（左圆角右直角）改为「独立胶囊」（两端都圆角）+ 横向渐变（亮紫 → primary），凸出立体光感
- **Tab 栏（PillNavigationBar）全新设计**
  - 半透明胶囊 surface alpha 0.62 → 0.88 + 顶部细高光 + 软阴影 + spring 滑动 dot 指示器
  - 选中 icon glow 软色 radial brush 圆 + 弹性按压（spring scale 0.92 ↔ 1.0）
  - 性能：state 读下沉到 placement / render 阶段，避免 60fps 重组；brush 用 drawWithCache 缓存；动画期间 0 次 GC
- **日志屏 UI 全新设计**
  - 每条日志第一行重排：`[Level chip 底色加粗] + Tag(SemiBold) + Spacer + Time(Mono 右对齐)`，扫日志视觉重点前移
  - WARN 及以上的 message 文本染 level color，INFO/DEBUG 保持 onSurface
  - 删「详细日志记录」开关行（无用）
- **阅读控制栏重排** 进度条上、按钮下；按钮容器 32dp → 44dp（接近 Material3 标准触摸目标 48dp）；章节标题与进度去除分隔符「·」避免误判同级
- **顶栏 / overflow 按钮整理** 排序按钮从顶栏移到「我的书架」标题行；overflow 内的「切换视图」也移到标题行；同一动作单一入口

### Fixed
- **滚动模式当前页号自动更新** SCROLL 模式底部 InfoBar 的 `page / progress / page_progress` 三个 slot 之前硬编码 `pageIndex=0, pageCount=0` 全 fallback 到 chapter_progress；现在从 LazyScrollSection 首段 charPos 反查 `TextChapter.getPageIndexByCharIndex(charPos)`，跟随滚动 60fps 实时更新
- **音量键翻页未生效修复**（commit `6c4087c`）
- **仿真翻页快翻"走三步退一步"**（commit `9bd4a56` "fix(reader): 仿真快翻 base race"）
- **个别书源闪退修复**（多个 commit；含 P0 书源补齐 + AnalyzeRule WebJs 模式接通 BackstageWebView）
- **首装时主题跟随系统暗色态**（commit `d071ccc`）
- **拖动滑块时菜单栏自己消失** `onSeekFullBook` 内 `hideControls`（commit `ecf1312`）
- **拖动滑块改 conflate + 单 worker 串行避免章预览风暴**（commit `d7f4c10`）
- **注/批 SVG 超大字 + 拖动期间 sliderValue/thumb 分裂**（commit `3091cd8`）

### Performance
- PillNavigationBar dot offset / scale 从顶层 `by` 委托改为 lambda 内读 State，每次动画 invalidation 只触发 placement/render 阶段，跳过 composition + measure + layout
- PillNavigationBar dot glow / icon glow 的 Brush 用 `drawWithCache` 缓存到 size 变化前，60fps 期间 0 次 GC

## [1.1] - 2026-05-07

稳态优化版本 🌿

对照 1.0 正式版，集中处理一批阅读体验、滑块手感与稳定性短板。

### Added
- **正文支持目录链接跳转** 章节内引用其它章节的链接可直接点开
- **书架视图模式持久化** 列表 / 网格切换持久化到 DataStore，重启 App 保留上次选择
- **关于页"贡献者"入口** 基于 `assets/contributors.json` 列出社区贡献成员，含维度标签 / 加入时间 / 链接，配合根目录 `CONTRIBUTING.md` 入榜规则

### Changed
- 「一键还原」从「轻还原」扩展为完整出厂：覆盖颜色 / 字体 / 字号 / 行距 / 段距 / 边距 / 翻页方式 / 翻页动画 / 屏幕方向 / 划词可选 / 繁简模式 / 状态栏 / 章节名 / 时间电量 / 屏幕亮度 / 音量键 / 耳机键 / 4 个 tap zone / 6 个 header & footer slot / 标题对齐 / 阅读区背景图 / 选区菜单顺序；5 个内置预设（preset_paper 等）一并刷回出厂参数
- 书名按数字自然排序，「第2章 / 第10章 / 第11章」不再被字典序排成「第10章 / 第11章 / 第2章」
- 「收纳」菜单语义调整，更贴近用户对该入口功能的直觉

### Fixed
- 拖底部进度条松手 thumb 不再先弹回再恢复 — seek preview 延迟到 ViewModel 真值流到目标值后再清
- 上下 / 左右 / 段距 slider 松手「弹回再回来」消失 — preview 改为等 StateFlow emit 到目标值再清空
- SCROLL 模式同章 reload 走轻路径 — 拖底部进度条到当前章不同位置不再清窗口重 fetch
- SCROLL 模式间距实时 — 上下 / 左右 / 段距 slider 实时反映到正文（ChapterWindowSource.relayoutAll 用最新 layoutInputs 重排所有已加载章节）
- 阅读器顶栏 top bar 间距过大问题修复
- 连点繁→简→繁 章内位置不再累计回退（anchor 锁 + 2s 清）
- 并发繁简切换造成「切完又变回去」修复（Mutex 串行化）
- 繁简转换异常不再静默吞错（s2t / t2s 失败时打 warn 日志）
- 全文搜索闪退 / 书内搜索修复
- 部分书源乱码修复
- Legado 数据移植闪退修复

## [1.0.0] - 2026-05-04

首个正式版。基于 Jetpack Compose + Material 3 重写自定义阅读渲染层（Canvas 录制缓存 + 仿真 / 滚动 / 滑动 / 覆盖四种翻页），对齐 Legado 书源生态，附带成熟的 TTS / WebDav / 批注体系。

### Added
- **本地书** TXT / EPUB / UMD（含 EPUB 章节预缓存、首章预热、缓存失效策略）
- **网络书源** 兼容 Legado JSON 书源；五种解析模式：JSoup/CSS、XPath、JSONPath、JS、Regex
- **TTS 朗读** 系统 TTS / Edge TTS / HTTP TTS 三引擎；自定义朗读规则、章节切换通知栏 MediaStyle 控制、章节预渲染队列
- **主题与排版** 自定义字体、行距、段距、背景图、日 / 夜模式独立设置；五套预置阅读样式
- **批注** 高亮 / 书签 / 笔记；段级 mini-menu 反查
- **WebDav 同步** 进度 + 阅读统计 + 可选完整数据
- **后台书架刷新** 进入书架自动 upToc 检查新章节，"N 新"角标
- **书源一键检测** 4 步检测单例，错误持久化到 BookSource.errorMsg
- **检查更新按钮** 「我的 → 关于 → 检查更新」拉 GitHub Releases API
- **CD 自动发版流程** `git tag v*` 触发 `.github/workflows/release.yml` 自动校验 tag、抽取 release notes、跑测试、签名打包、附 APK
- `CHANGELOG.md` + `docs/release-setup.md` 发版手册

### Changed
- `.github/workflows/ci.yml` LICENSE 检查改为「GPL-3.0 或 Commercial License 声明」，匹配双许可证模型
- 进度持久化迁移到 StateFlow snapshot 流（`combine + distinctUntilChanged + debounce(300ms)`），翻一页的 saveProgress 调用从 5 次合并到 1 次，连带 WebDav 上传频率同步降低 3-5 倍
- AnalyzeByJSonPath 把 `Missing property in path` 与 `No results for path` 同等降级为 debug，减少日志噪音

### Fixed
- 仿真翻页 `0%→2%→0%` scroll 反弹 — 经 debounce 自然吞掉
- 单一坏书源 JS 规则失败把 worker 钉死 7+ 秒 — `(bookSourceUrl, scriptHash)` 连续失败 ≥ 5 次 + 30s 内直接短路熔断
- PageTurnFlicker 分页流式产页时同 key 连发 19 行 SKIPPED 日志 — 1s 节流 + 累计被压制次数

## [1.0.0-alpha1] - 2026-05-03

### Added
- 首个 alpha 版本（基线）。功能集合见项目 README。
