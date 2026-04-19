# domain/ — 业务核心

所有"非 UI"的逻辑都在这里。

- `entity/`     — 数据定义（Book, BookChapter, ReaderStyle 等 Room Entity）
- `db/`         — Room 数据库（AppDatabase + 各 DAO）
- `preference/` — DataStore 偏好设置
- `repository/` — 数据聚合层（组合 DAO + 网络 + 缓存）
- `parser/`     — 格式解析引擎（EPUB/TXT/PDF/CBZ/MOBI）
- `tts/`        — TTS 引擎（系统 TTS / Edge TTS / HTTP TTS）
- `rule/`       — 书源规则引擎（CSS/XPath/JSONPath/Regex）
- `source/`     — 书源导入和在线书籍获取
- `sync/`       — 同步（WebDAV 客户端 + 备份管理器）
- `render/`     — 文本渲染引擎（文字测量 + 分页布局）
