package com.morealm.app.domain.holiday.greeting

/**
 * DMRG 四轴池子 — B 阅读状态 / C 书籍共鸣 / D 情感闭环。
 *
 * 算法约定：
 *  - **不带打分** — DMRG 用桶定位 + 哈希位移取索引，候选集由上下文 bucket 决定，
 *    桶内任意一条都"够格"，由 seed 哈希位移坍缩出唯一索引。
 *  - **桶设计极扁** — bucket key 是简单枚举，避免运行时构图。每个桶 3-4 条意象。
 *  - **零依赖外部资源** — 全部内联在 dex 中，首次访问后常驻常量池。
 */
internal object DimensionPools {

    // ─────────────────── B 轴：阅读状态 / 时段意象 ───────────────────

    /**
     * B 轴桶 Key — 由 (时段段位, 阅读时长段位) 组合而成。共 12 个桶，每桶 3-4 条。
     *
     * 时段段位：Dawn(0..4)/Morning(5..8)/Noon(9..13)/Afternoon(14..17)/Dusk(18..19)/Night(20..23)
     * 时长段位：Cold(<=5min)/Brief(<=29min)/Deep(>=30min)
     *
     * 当上下文不命中任何桶时，回退到 [STATE_VIBES_FALLBACK]。
     */
    enum class StateBucket {
        NIGHT_DEEP, NIGHT_BRIEF, NIGHT_COLD,
        MORNING_DEEP, MORNING_BRIEF, MORNING_COLD,
        AFTERNOON_DEEP, AFTERNOON_BRIEF, AFTERNOON_COLD,
        DAWN, DUSK, NOON,
    }

    /**
     * B 轴：阅读状态意象。每桶 3-4 条，运行时按 [StateBucket] 取列表，
     * 再用 seed 位移取索引。意象优先保留"懂用户"的细腻感（不堆砌华丽词）。
     */
    val STATE_VIBES: Map<StateBucket, List<String>> = mapOf(
        StateBucket.NIGHT_DEEP to listOf(
            "你已在字里行间深潜了许久",
            "夜色温柔地把你和书页都包了起来",
            "你与故事并肩走过了一段长长的夜路",
            "这一夜你被一本书温柔留下",
        ),
        StateBucket.NIGHT_BRIEF to listOf(
            "夜里翻了几页，心也跟着静下来",
            "你陪一本书度过了一小段夜",
            "夜风经过，你的书页也轻轻动",
        ),
        StateBucket.NIGHT_COLD to listOf(
            "夜深人静，刚刚才翻开新的一页",
            "夜里第一次拿起书的瞬间",
            "你刚把夜色和书页一起摊开",
        ),
        StateBucket.MORNING_DEEP to listOf(
            "清晨被你读成了一段长长的旅程",
            "晨光里你已经走了好远",
            "新的一天被你郑重翻开",
        ),
        StateBucket.MORNING_BRIEF to listOf(
            "清晨陪你翻了几页书",
            "晨光里你和书页都在醒来",
            "新的一天，你已经开始书写",
        ),
        StateBucket.MORNING_COLD to listOf(
            "你刚把今天的第一页翻开",
            "清晨的第一缕书香",
            "晨光初亮，新书初开",
        ),
        StateBucket.AFTERNOON_DEEP to listOf(
            "午后的时光被你慢慢读宽了",
            "你和书页一起度过了漫长的下午",
            "午后被书页一页页拉长",
        ),
        StateBucket.AFTERNOON_BRIEF to listOf(
            "午后陪书页过了一会儿",
            "下午的某个片刻属于书",
            "午后被一小段故事填满",
        ),
        StateBucket.AFTERNOON_COLD to listOf(
            "你刚把午后的第一页翻开",
            "午后初醒，故事初开",
            "你的下午刚刚开始书写",
        ),
        StateBucket.DAWN to listOf(
            "凌晨的世界还在沉睡，唯有你与书清醒",
            "天未亮，故事先到",
            "凌晨陪你翻过寂静的几页",
        ),
        StateBucket.DUSK to listOf(
            "黄昏的光斜斜落在书页上",
            "暮色里你与故事相对",
            "黄昏的安静里，书页轻响",
        ),
        StateBucket.NOON to listOf(
            "午间的安静属于一本书",
            "正午的光把书页照得很亮",
            "中午陪你翻开了新的一页",
        ),
    )

    /** B 轴兜底 — 当桶定位失败（理论不会发生）使用。 */
    val STATE_VIBES_FALLBACK: List<String> = listOf(
        "你正在与一本书相伴",
        "此刻有书页相陪",
        "故事正在悄悄展开",
    )

    // ─────────────────── C 轴：书籍共鸣意象 ───────────────────

    /**
     * C 轴桶 Key — 题材枚举。在 [GreetingEngine] 内由 book.title/category/kind
     * 走关键词匹配映射到一个 Genre；命中失败时用 [Genre.UNKNOWN]。
     *
     * 桶选择故意保持粗粒度（7 个） — 题材越细越容易误判，影响"懂用户"的观感。
     */
    enum class Genre {
        SCI_FI, WUXIA, HISTORY, ROMANCE, MYSTERY, LITERATURE, FANTASY, UNKNOWN, NONE,
    }

    /**
     * 题材关键词字典 — title/category/kind 任一字符串 contains 关键词即命中。
     * 顺序在 [GreetingEngine] 的 [Genre.values] 中固定，命中越靠前越优先。
     */
    val GENRE_KEYWORDS: Map<Genre, Set<String>> = mapOf(
        Genre.SCI_FI to setOf("宇宙", "星", "银河", "科幻", "量子", "未来", "末日", "机甲", "赛博"),
        Genre.WUXIA to setOf("剑", "侠", "江湖", "武", "仙", "道", "门派", "录", "诀", "派"),
        Genre.HISTORY to setOf("史", "记", "传", "朝", "国", "战", "唐", "宋", "明", "清", "汉"),
        Genre.ROMANCE to setOf("恋", "爱", "婚", "心动", "暗恋", "情", "甜", "宠", "夫人", "总裁"),
        Genre.MYSTERY to setOf("案", "谜", "探", "杀", "凶", "悬", "推理", "罪", "侦"),
        Genre.LITERATURE to setOf("文学", "散文", "随笔", "日记", "诗", "传记", "纪实", "随想"),
        Genre.FANTASY to setOf("魔", "幻", "异界", "灵", "妖", "神", "界", "天", "诸"),
    )

    /**
     * C 轴：书籍共鸣意象。`{title}` 由引擎替换为书名（无书时用 [BOOK_RESONANCES_NONE]）。
     */
    val BOOK_RESONANCES: Map<Genre, List<String>> = mapOf(
        Genre.SCI_FI to listOf(
            "你和《{title}》一起去了很远的地方",
            "你跟着《{title}》看了一片星空",
            "你和《{title}》一起想了一些很远的事",
        ),
        Genre.WUXIA to listOf(
            "你跟着《{title}》走过了一段江湖",
            "你在《{title}》里看见了一柄孤剑",
            "你和《{title}》一起走过了一段江湖路",
        ),
        Genre.HISTORY to listOf(
            "你跟着《{title}》看过了一段旧时光",
            "你在《{title}》里听见了文明的低语",
            "你和《{title}》一起翻过了一些旧日子",
        ),
        Genre.ROMANCE to listOf(
            "你跟着《{title}》走过了一段心动的路",
            "你在《{title}》里收下了一颗轻轻跳动的心",
            "你和《{title}》一起被柔软的字句包围",
        ),
        Genre.MYSTERY to listOf(
            "你跟着《{title}》走入了一桩谜的回响",
            "你在《{title}》里破开了一层迷雾",
            "你和《{title}》一起在线索里游走",
        ),
        Genre.LITERATURE to listOf(
            "你和《{title}》一起看见了人世的微光",
            "你在《{title}》里听见了文字的呼吸",
            "你跟着《{title}》翻过一些安静的字",
        ),
        Genre.FANTASY to listOf(
            "你跟着《{title}》走入了未知的境地",
            "你在《{title}》里看见了不止一种世界",
            "你和《{title}》一起走出了寻常的边界",
        ),
        Genre.UNKNOWN to listOf(
            "你和《{title}》一起往字里慢慢走",
            "你和《{title}》并肩走过了今天",
            "你正翻阅着《{title}》",
        ),
    )

    /** C 轴：无书时的通用共鸣（不带书名）。 */
    val BOOK_RESONANCES_NONE: List<String> = listOf(
        "你正与书页同行",
        "故事在静静地等你",
        "今日的字句正等你翻开",
    )

    // ─────────────────── D 轴：情感闭环 ───────────────────

    /**
     * D 轴桶 Key — 用户粘性 / 状态。在 [GreetingEngine] 内由「最近活跃天数 + 今日时长」
     * 推导：连续 ≥3 天阅读 = STICKY；超过 7 天未读 = AWAKENING；其余 = PEACEFUL。
     */
    enum class Stickiness { STICKY, AWAKENING, PEACEFUL }

    /** D 轴：情感闭环。每桶 3-4 条，与节日骨架收尾搭配。 */
    val EMOTION_CLOSURES: Map<Stickiness, List<String>> = mapOf(
        Stickiness.STICKY to listOf(
            "这里的字句早已陪你许久",
            "字句之间留着你最熟悉的呼吸",
            "你和书页早已彼此熟悉",
            "这是你和故事的又一次默契相逢",
        ),
        Stickiness.AWAKENING to listOf(
            "翻开这一页，像回到了熟悉的地方",
            "久违的字句，正等着你回来",
            "好久不见，故事还在这里等你",
            "愿你重新爱上翻页的声音",
        ),
        Stickiness.PEACEFUL to listOf(
            "愿这一页能落进你心里",
            "愿这一刻被字句温柔包裹",
            "愿你今天合上书时带着好心情",
            "愿这本书陪你走过这一日",
        ),
    )
}
