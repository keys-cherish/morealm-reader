# MoRealm 项目结构

采用 MVVM 架构，职责分层清晰：

```
domain/      → 数据定义 + 业务逻辑 + 数据操作（不依赖 UI）
presentation/→ ViewModel，管理状态，桥接 domain 和 ui
ui/          → 纯 Composable，只从 presentation 读数据
service/     → Android Service（TTS 前台服务）
di/          → Hilt 依赖注入配置
core/        → 基础设施（日志、文本工具）
```

数据流向：`UI → Presentation → Domain`
