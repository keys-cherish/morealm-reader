# MoRealm UI 设计规范

## 设计理念

**书房暗调 + 纸张暖色 + 内容优先 + 零装饰**

MoRealm 是一个阅读器，不是社交 App。所有视觉决策服务于一个目标：**让用户专注于内容**。
装饰性元素（极光、毛玻璃、渐变）会分散注意力，应当消除。

### 设计关键词
- 内容优先（Content-First）
- 温暖沉稳（Warm & Grounded）
- 书卷气息（Literary）
- 克制精致（Restrained Elegance）

### 不做的事
- 不用极光/粒子/渐变背景
- 不用毛玻璃卡片（仅底部导航使用轻度 blur）
- 不用高饱和强调色（紫色、霓虹绿等）
- 不用过度动画（> 300ms 的装饰性动画）
- 不用 emoji 作为图标

---

## 色彩系统

### 暗色主题（默认：墨夜）

| Token | 值 | 用途 |
|-------|-----|------|
| `--bg` | `#0C0C0F` | 页面背景 |
| `--bg-card` | `#161619` | 卡片/列表项背景 |
| `--text-1` | `#E8E6E3` | 主要文字 |
| `--text-2` | `#8A8680` | 次要文字 |
| `--text-3` | `#4A4744` | 占位/禁用文字 |
| `--accent` | `#C4956A` | 强调色（书页暖棕） |
| `--border` | `rgba(255,255,255,0.06)` | 边框 |

### 亮色主题（纸上）

| Token | 值 | 用途 |
|-------|-----|------|
| `--bg` | `#F5F0E8` | 页面背景（米色纸张） |
| `--bg-card` | `#FFFDF8` | 卡片背景 |
| `--text-1` | `#2C2520` | 主要文字 |
| `--accent` | `#A0714A` | 强调色 |

### 强调色选择依据

放弃紫色 `#8B5CF6`，选择书页暖棕 `#C4956A`。原因：
- 紫色是科技/社交/游戏的色调，与阅读场景格格不入
- 暖棕色联想：皮革书封、木质书架、烛光、牛皮纸
- 对比度：暖棕在深色背景上 contrast ratio 5.2:1（满足 WCAG AA）

---

## 底部导航栏：悬浮药丸

### 设计决策

| 原方案 | 新方案 | 原因 |
|--------|--------|------|
| Material3 `NavigationBar` | 自定义悬浮药丸 | Material 默认导航栏视觉过重，贴底无悬浮感 |
| 填充式图标 | 线条式图标（stroke-width 1.8） | 更轻盈，与阅读器的"克制"调性一致 |
| 标准 80dp 高度 | 紧凑 56dp + 圆角 100dp | 药丸形状，悬浮于页面底部 |
| 全宽 | 左右各缩进 24dp | 与页面边缘脱离，产生悬浮感 |

### 规格

```
┌─────────────────────────────────────────┐
│  距底: 20dp                              │
│  左右边距: 24dp                           │
│  高度: 56dp                              │
│  圆角: 100dp (完全药丸形)                  │
│  背景: 半透明 + blur(20dp)                │
│  边框: 1dp rgba(255,255,255,0.06)        │
│  阴影: 0 8dp 32dp rgba(0,0,0,0.25)      │
│                                          │
│  图标: 22dp, stroke-width 1.8            │
│  标签: 10sp, weight 500                  │
│  选中色: --accent (#C4956A)              │
│  未选中色: --text-3 (#4A4744)            │
│  选中指示器: 4dp 圆点，底部 -6dp          │
└─────────────────────────────────────────┘
```

### 交互

- 点击切换 tab，无额外动画
- 选中项颜色变化 + 底部小圆点指示器
- 内容区域底部需要 padding 100dp 避免被遮挡

### Compose 实现要点

```kotlin
// 悬浮药丸导航栏 — 替代 Material3 NavigationBar
Box(
    modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 24.dp, bottom = 20.dp)
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(100.dp))
            .background(navBgColor)  // 半透明 + blur
            .border(1.dp, borderColor, RoundedCornerShape(100.dp))
            .shadow(elevation, RoundedCornerShape(100.dp)),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            PillNavItem(tab, selected = tab == currentTab, onClick = { ... })
        }
    }
}
```

---

## 书封面卡片

### 书脊效果

用 `::after` 伪元素（Compose 中用 `drawBehind`）在封面右侧画 3-4dp 宽的渐变阴影，模拟实体书书脊。

### 封面底部渐变

用 `::before` 伪元素在封面底部 40% 区域画透明→黑色渐变，让白色书名文字在深色封面上可读。

---

## 间距系统

采用 4dp 基础单位：

| Token | 值 | 用途 |
|-------|-----|------|
| xs | 6dp | 标签内边距 |
| sm | 12dp | 组件内间距 |
| md | 20dp | 页面水平边距 |
| lg | 32dp | 章节间距 |
| xl | 48dp | 页面顶部留白 |

---

## 字号阶梯

| 级别 | 大小 | 用途 |
|------|------|------|
| caption | 10sp | 来源标签、时间戳 |
| label | 11sp | 状态文字、辅助信息 |
| body-sm | 12sp | 次要正文、作者名 |
| body | 14sp | 主要正文 |
| title | 15sp | 卡片标题 |
| heading | 22sp | 页面标题 |
| display | 24sp | 大标题 |

---

## HTML 预览

设计稿预览位于 `preview/1.htm`，包含：
- 书架页（继续阅读 + 网格）
- 发现页（搜索 + 书源状态面板 + 结果列表）
- 听书页（播放器）
- 我的页（统计 + 主题切换）
- 悬浮药丸导航栏
- 4 套主题：墨夜 / 纸上 / 赛博 / 森林
