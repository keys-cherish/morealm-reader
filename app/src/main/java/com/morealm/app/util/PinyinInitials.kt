package com.morealm.app.util

import java.text.Collator
import java.util.Locale

/**
 * 汉字/混排字符串的拼音首字母提取。
 *
 * 实现基于 GB 区位锚点 + [Collator] 比较：常用汉字在中文 Collator 排序下落在
 * 「啊..芭」「芭..擦」等锚点区间内，据此映射到 A-Z。生僻字（GBK 扩展区外）可能
 * 落不进任何区间，归入 '#'。这层不追求 100% 覆盖——图书馆分组/检索场景下
 * 少数生僻字进 '#' 分组是可接受的降级；未来接 FTS 拼音索引后可整体替换。
 */
object PinyinInitials {

    private val collator: Collator = Collator.getInstance(Locale.CHINA)

    /** 各字母区间的起始锚点字，与 [LETTERS] 一一对应（无 I/U/V 声母）。 */
    private const val ANCHORS = "啊芭擦搭蛾发噶哈击喀垃妈拿哦啪期然撒塌挖昔压匝"
    private val LETTERS = "ABCDEFGHJKLMNOPQRSTWXYZ".toCharArray()

    /** 所有可能的分组字母，按展示顺序（字母 + '#'）。 */
    val GROUPS: List<Char> = LETTERS.toList() + '#'

    /** 单字符 → 首字母（'A'..'Z' 或 '#'）。 */
    fun firstLetter(c: Char): Char {
        when {
            c in 'A'..'Z' -> return c
            c in 'a'..'z' -> return c.uppercaseChar()
            c.code < 0x80 -> return '#'
        }
        val s = c.toString()
        // 在锚点表里找最后一个 <= c 的锚点；c 比「啊」还靠前（符号区）则保持 '#'
        var result = '#'
        for (i in ANCHORS.indices) {
            if (collator.compare(ANCHORS[i].toString(), s) <= 0) result = LETTERS[i] else break
        }
        return result
    }

    /** 字符串首字符的分组字母（空串归 '#'）。 */
    fun groupOf(text: String): Char {
        val t = text.trim()
        if (t.isEmpty()) return '#'
        return firstLetter(t[0])
    }

    /** 全部字符的首字母串（用于「hzxy → 红砖学园」式检索）。非字母字符跳过。 */
    fun initials(text: String): String = buildString {
        for (c in text) {
            val l = firstLetter(c)
            if (l != '#') append(l)
        }
    }
}
