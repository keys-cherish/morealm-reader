# Changelog

本项目（MoRealm 墨境）的变更记录。
格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [SemVer](https://semver.org/lang/zh-CN/) 子集。

每次发版前：把 `[Unreleased]` 中的内容挪到下方新版本号 section（标题为 `## [X.Y.Z] - YYYY-MM-DD`），然后 `git tag vX.Y.Z && git push --tags` 触发 `release.yml` 自动发布到 GitHub Releases。release.yml 会从本文件抽取对应 section 作为 release notes，section 缺失会构建失败。

## [Unreleased]

### Added
- **检查更新按钮**：「我的 → 关于 → 检查更新」从 GitHub Releases API 拉 `latest` 与本地版本号比较，发现新版本时弹 Dialog 展示 release notes 并跳转下载页。
- **CD 自动发版流程**：`git tag v*` 触发 `.github/workflows/release.yml`，自动校验 tag 与 `versionName` 一致 → 抽取本文件对应 section → 跑单元测试 → 签名打包 → 创建 GitHub Release 并附 APK。
- `CHANGELOG.md` 文件本身（即此文件）。
- `docs/release-setup.md` —— GitHub Secrets 配置与发版操作手册。

### Changed
- `.github/workflows/ci.yml` LICENSE 检查由「必须含 MIT License」改为「必须含 GPL-3.0 或 Commercial License 声明」，匹配项目实际的双许可证模型。

## [1.0.0-alpha1] - 2026-05-03

### Added
- 首个 alpha 版本（基线）。功能集合见项目 README。
