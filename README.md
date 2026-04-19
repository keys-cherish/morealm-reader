# 墨境 MoRealm

一款现代化的 Android 电子书阅读器，基于 Jetpack Compose 构建。

## 特性

**阅读体验**
- Compose Canvas 渲染引擎（TXT/EPUB），原生翻页动画
- WebView 渲染引擎（可切换），完整 CSS/HTML 排版支持
- 5 套内置阅读预设（纸质/护眼/海蓝/暖黄/墨白），自动适配日夜模式
- 繁简转换、自定义 CSS 注入、双页模式（平板横屏）
- 字体/字号/行距/段距/页边距精细调节
- 全文搜索、书签、目录搜索、正文替换净化
- 屏幕方向锁定、亮度独立调节、自动翻页

**格式支持**
- EPUB — epublib 随机访问解析，封面/元数据瞬间提取
- TXT — 512KB 块读取，skip 跳转，超大章节自动拆分
- PDF — PdfRenderer 逐页渲染，封面提取
- CBZ/CBR — 漫画格式，图片自然排序
- MOBI/AZW3 — 基础支持

**TTS 朗读**
- 系统 TTS + Edge TTS（21 个中文神经语音）+ HTTP TTS API
- 通知栏控制、音频焦点、睡眠定时器
- 朗读跟随滚动、音量键切换段落
- 引擎连续失败自动回退

**书源**
- 规则引擎支持 CSS/XPath/JSONPath/Regex 四种模式
- 兼容 Legado 书源格式导入
- 完整管道：搜索 → 书籍详情 → 目录 → 正文

**书架**
- 文件夹封面拼图预览
- 书籍/文件夹置顶、名称排序
- 批量管理、自定义分组
- 两阶段导入（即时显示 + 后台补全元数据）

**同步与备份**
- WebDAV 云同步（标准 PROPFIND XML、文件列表、异步 IO）
- 本地 ZIP 备份/恢复（书架/进度/书源/主题/阅读预设）

**主题**
- 6 套内置主题，150ms 动画渐变切换
- 自定义主题编辑器（背景色/文字色/强调色）
- 主题导入/导出（JSON 格式，兼容 Legado）

**日志系统**
- 多 Sink 架构：Logcat + 内存环形缓冲 + 滚动文件
- 异步文件写入（专用线程 + BlockingQueue）
- ANR 看门狗 + 崩溃报告 + Activity 生命周期监控
- 应用内日志查看器

## 技术栈

- Kotlin + Jetpack Compose
- Room + DataStore
- Hilt 依赖注入
- OkHttp + Jsoup + JsoupXpath + JsonPath
- epublib（ParcelFileDescriptor 随机访问）
- Media3 MediaSession
- Coil 图片加载

## 构建

```bash
# Windows
gradlew.bat assembleDebug

# macOS / Linux
./gradlew assembleDebug
```

APK 输出路径：`app/build/outputs/apk/debug/app-debug.apk`

## 许可证

MIT License
