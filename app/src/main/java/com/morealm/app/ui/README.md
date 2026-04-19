# ui/ — 纯 UI 层

所有 Composable 函数。只从 presentation 读状态，不包含业务逻辑。

- `reader/`     — 阅读器页面 + 设置面板 + TTS 面板
  - `renderer/` — 渲染器（Canvas / WebView / NativeText）
- `shelf/`      — 书架页面 + 书籍卡片 + 文件夹卡片
- `search/`     — 搜索页面
- `settings/`   — 设置页面（阅读设置/日志查看/替换规则）
- `profile/`    — 个人页（统计/主题编辑/WebDAV/关于）
- `source/`     — 书源管理页面
- `theme/`      — 主题定义（MoRealmTheme + 内置主题）
- `navigation/` — 导航（MainActivity + AppNavHost + BottomTab）
- `component/`  — 可复用 UI 组件
- `listen/`     — 听书页面（开发中）

职责边界：
- ✅ 从 ViewModel 读 StateFlow
- ✅ 调 ViewModel 方法响应交互
- ❌ 不直接调 domain 层
- ❌ 不包含业务逻辑
