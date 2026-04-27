<p align="center">
  <img src="docs/app-icon.png" width="120" alt="墨境 MoRealm" />
</p>

<h1 align="center">墨境 MoRealm</h1>

<p align="center">
  <strong>一款现代化的 Android 电子书阅读器</strong><br>
  基于 Jetpack Compose · 兼容 Legado 书源 · MIT 开源
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white" />
  <img src="https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white" />
  <img src="https://img.shields.io/badge/Compose-Material3-4285F4?logo=jetpackcompose&logoColor=white" />
  <img src="https://img.shields.io/badge/License-MIT-green" />
</p>

---

## 阅读体验

- **Canvas 渲染引擎** — 自研排版，支持全文搜索、书签、目录、正文净化
- **5 种翻页动画** — 仿真翻页（贝塞尔曲线）、滑动、覆盖、上下翻页、连续滚动
- **5 套阅读预设** — 纸质 / 护眼 / 海蓝 / 暖黄 / 墨白，自动适配日夜模式
- **精细排版控制** — 字体 / 字号 / 行距 / 段距 / 页边距 / 繁简转换 / 自定义 CSS
- **独立亮度调节** — 阅读器亮度与系统分离
- **自动翻页** — 可配速度的自动阅读

## 格式支持

| 格式 | 引擎 | 特性 |
|------|------|------|
| **EPUB** | epublib | 随机访问解析，封面/元数据瞬间提取 |
| **TXT** | 自研 | 512KB 块读取，自动目录识别，超大章节拆分 |
| **PDF** | PdfRenderer | 逐页渲染，封面提取 |
| **MOBI/AZW3** | 自研 | KF8 格式解析 |
| **CBZ/CBR** | 自研 | 漫画格式，图片自然排序 |

## TTS 朗读

- **三引擎支持** — 系统 TTS + Edge TTS（21 个中文神经语音）+ HTTP TTS API
- 通知栏控制 · 音频焦点 · 睡眠定时器
- 朗读跟随滚动 · 音量键切换段落
- 引擎连续失败自动回退

## 书源生态

- **四模式规则引擎** — CSS / XPath / JSONPath / Regex
- **兼容 Legado 书源** — 直接导入 Legado JSON 书源格式
- **完整内容管道** — 搜索 → 书籍详情 → 目录 → 正文

## 更多功能

| 模块 | 功能 |
|------|------|
| **书架** | 文件夹分组 · 封面拼图 · 置顶 · 批量管理 · 两阶段导入 |
| **同步** | WebDAV 云备份 · 本地 ZIP 备份/恢复 |
| **主题** | 6 套内置主题 · 自定义编辑器 · Legado 主题导入导出 |
| **日志** | 多 Sink 架构 · ANR 看门狗 · 崩溃报告 · 应用内查看器 |
| **统计** | 年度阅读报告 · 阅读时长追踪 |

## 技术栈

```
Kotlin · Jetpack Compose · Material3
Room · DataStore · Hilt
OkHttp · Jsoup · JsoupXpath · JsonPath
epublib · Media3 · Coil
```

## 架构

```
presentation/          ← ViewModel + Controller（MVVM 胶水层）
├── reader/
│   ├── ReaderViewModel.kt          (~330 行，纯协调)
│   ├── ReaderChapterController.kt  (章节加载/缓存)
│   ├── ReaderProgressController.kt (进度/统计)
│   ├── ReaderNavigationController.kt (翻章/关联书)
│   ├── ReaderSearchController.kt   (全文搜索)
│   ├── ReaderBookmarkController.kt (书签)
│   ├── ReaderContentEditController.kt (编辑/导出)
│   ├── ReaderSettingsController.kt (设置/样式)
│   └── ReaderTtsController.kt      (TTS)
ui/reader/renderer/    ← 渲染层
│   ├── CanvasRenderer.kt           (Compose 组装)
│   ├── PageTurnCoordinator.kt      (翻页状态协调)
│   ├── ScrollRenderer.kt           (连续滚动)
│   ├── SimulationReadView.kt       (仿真翻页·原生View)
│   └── SimulationDrawHelper.kt     (贝塞尔绘制引擎)
domain/                ← 业务逻辑（纯 Kotlin，无 Android 依赖）
```

## 构建

```bash
# 克隆仓库
git clone https://github.com/keys-cherish/morealm-reader.git
cd morealm-reader

# 构建 Debug APK
./gradlew assembleDebug    # macOS / Linux
gradlew.bat assembleDebug  # Windows
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

## 许可证

[MIT License](LICENSE)
