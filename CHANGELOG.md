# Changelog

本项目（MoRealm 墨境）的变更记录。
格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [SemVer](https://semver.org/lang/zh-CN/) 子集。

每次发版前：把 `[Unreleased]` 中的内容挪到下方新版本号 section（标题为 `## [X.Y.Z] - YYYY-MM-DD`），然后 `git tag vX.Y.Z && git push --tags` 触发 `release.yml` 自动发布到 GitHub Releases。release.yml 会从本文件抽取对应 section 作为 release notes，section 缺失会构建失败。

## [Unreleased]

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
