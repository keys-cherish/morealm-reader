# presentation/ — ViewModel 层

管理 UI 状态，桥接 domain 数据给 UI 使用。

- `reader/`   — 阅读器状态（章节/进度/TTS/设置）
- `shelf/`    — 书架状态（书籍列表/导入/排序/分组）
- `search/`   — 搜索状态（书源搜索结果）
- `theme/`    — 主题状态（切换/自动日夜/导入导出）
- `source/`   — 书源管理状态（导入/启用/删除）
- `settings/` — 设置状态（阅读设置/替换规则）
- `profile/`  — 个人页状态（统计/备份/WebDAV）

职责边界：
- ✅ 从 domain 读数据，转成 StateFlow
- ✅ 处理用户意图，调 domain 方法
- ❌ 不操作任何 UI 组件
- ❌ 不知道用什么 Composable 渲染
