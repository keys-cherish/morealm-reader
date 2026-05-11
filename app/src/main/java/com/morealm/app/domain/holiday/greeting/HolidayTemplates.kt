package com.morealm.app.domain.holiday.greeting

/**
 * DMRG 句子骨架 + 节日 A 轴开场池。
 *
 * 设计：
 *  - **零运行时开销**：所有数据是 `object` 内的不可变 `val`，编译进 dex，
 *    首次访问后常驻 PermGen，零 IO、零反序列化。
 *  - **骨架极简**：DMRG 算法只需要 2 个骨架就能覆盖全部表达，靠四轴
 *    维度（A 节日 / B 状态 / C 书籍共鸣 / D 情感闭环）的组合产生变体，
 *    模板组合爆炸交给数据自然解决。
 *  - **A 轴节日开场**：每节日 3-4 条开场意象，按 holidayId 索引。运行时
 *    会合并 [com.morealm.app.domain.holiday.Holiday.message] 作为最后兜底。
 *
 * 槽位含义：
 *  - `{A}` — 节日开场（来自 [HOLIDAY_INTROS]）
 *  - `{B}` — 阅读状态/时段意象（来自 [DimensionPools.STATE_VIBES]）
 *  - `{C}` — 书籍共鸣（来自 [DimensionPools.BOOK_RESONANCES]，含 `{title}` 子占位）
 *  - `{D}` — 情感闭环（来自 [DimensionPools.EMOTION_CLOSURES]）
 */
internal object HolidayTemplates {

    /**
     * 骨架模板。两条骨架在标点节奏上略有差异（前句重 / 后句重），
     * 由 DMRG 的 seed 决定走哪一条。骨架数刻意保持极小 — 真正的
     * 变化来自四轴池子的笛卡尔积。
     */
    val SKELETONS: List<String> = listOf(
        "{A}，{B}，{C}。{D}",
        "{A}。{B}，{C}，{D}",
    )

    /**
     * A 轴 — 每节日开场意象。键为 [com.morealm.app.domain.holiday.Holiday.id]。
     *
     * 每节日 3-4 条，节奏控制在 6-10 字，方便和骨架后续部分拼接成不撑爆的句子。
     * 句末不带标点 — 标点统一由骨架管。
     */
    val HOLIDAY_INTROS: Map<String, List<String>> = mapOf(
        "new_year" to listOf("辞旧迎新，万象更始", "新一年的第一页轻轻翻开", "钟声敲过零点", "新年好"),
        "valentine" to listOf("玫瑰的香气还未散去", "情人节的灯火很温柔", "爱意正悄悄落下", "今夜的浪漫被书页接住"),
        "women_day" to listOf("属于她的日子如约而至", "三月的风温柔似她", "今天，世界为她让路", "她正翻开属于自己的书"),
        "planting_day" to listOf("春日的土壤正在苏醒", "种下一棵树的好时节", "草木开始生长", "字也是慢慢生根的"),
        "april_fools" to listOf("玩笑落地，故事正经", "今天可以认真，也可以不", "假话与真话同样轻盈", "愚人节的微笑里藏着真心"),
        "labor_day" to listOf("劳动的果实在五月成熟", "假日的早晨自由舒展", "汗水之后是好故事", "翻页也是一种劳作"),
        "youth_day" to listOf("五月的青年正茁壮", "理想还在心里灼热", "年轻的眼睛望着远方", "少年气还未褪去"),
        "children_day" to listOf("童话的门重新打开", "六一的风带着糖果味", "童年还在书里等你", "大人也可以读童话"),
        "party_day" to listOf("百年长卷仍在书写", "红色的篇章悠然展开", "今日七月，岁月安稳", "信仰深处有故事"),
        "army_day" to listOf("八一的旗帜安静飘扬", "守护安静阅读的也是守护", "致敬所有的坚定", "营房之外有人翻书"),
        "teacher_day" to listOf("九月的感谢飘向师长", "每一本书都曾是老师", "粉笔灰落下，书页翻起", "致敬一切引路的字"),
        "national_day" to listOf("十月的国旗在风里飘扬", "祖国安好，山河无恙", "假期的午后最适合书", "盛世里翻一卷书"),
        "singles_day" to listOf("一个人的夜也可以热闹", "书架是最长情的朋友", "光棍节，书页相陪", "你不是一个人"),
        "christmas" to listOf("窗外的雪悄悄落下", "圣诞夜的灯光温柔", "今晚有人会想起你", "故事像礼物一样被打开"),
        "reading_day" to listOf("书香弥漫的日子", "今天属于一切书页", "翻开就是过节", "世界因读书更宽阔"),
        "spring_festival" to listOf("爆竹声中一岁除", "春节的灯笼高高挂起", "团圆与好故事一同到来", "新春纳福"),
        "lantern_festival" to listOf("月圆灯亮的夜晚", "元宵的甜与故事一样长", "灯影里翻一卷书", "圆灯之下宜读书"),
        "dragon_boat" to listOf("粽叶的清香飘进窗", "龙舟掠过岁月长河", "端午的水汽很温润", "五月初五，菖蒲微苦"),
        "qixi" to listOf("天上的故事写了千年", "七夕的星河格外清亮", "鹊桥下书页也轻轻翻", "今夜星光宜读书"),
        "mid_autumn" to listOf("月升中天，万籁俱寂", "今夜的月光跌入书页", "团圆与书都圆满", "桂香里翻一页书"),
        "chongyang" to listOf("登高望远的好日子", "重阳的菊花开得正好", "山高水长，书页同辽阔", "秋意正浓，宜读长篇"),
        "lunar_new_year_eve" to listOf("年夜的灯火逐一亮起", "辞旧迎新的最后一夜", "鞭炮与翻书声交织", "除夕安好"),
        "qing_ming" to listOf("清明的细雨刚刚落下", "故人故里都在风里", "今日宜静坐宜读书", "草色清新的早春"),
        "dong_zhi" to listOf("一年中夜最长的时刻", "冬至的暖意正升起", "饺子与书一起捧在手心", "夜深长，宜读长篇"),
        "li_chun" to listOf("春日的第一缕暖意", "立春了，万物都在抬头", "新岁的第一卷开始", "春意正悄悄上枝头"),
        "li_qiu" to listOf("立秋的凉风刚起", "梧桐叶开始发黄", "秋意宜读长篇", "天高云淡的好日子"),
        "mothers_day" to listOf("致那个最早的读者", "母亲的目光柔软如初", "今天的书页献给她", "母亲节安好"),
        "fathers_day" to listOf("父亲的沉默胜过千言", "他读你胜过读任何书", "今日的故事献给父亲", "父亲节安好"),
        "thanksgiving" to listOf("感恩的季节悄悄到来", "感谢每一本陪过你的书", "致每一份遇见", "感恩节安好"),
    )
}
