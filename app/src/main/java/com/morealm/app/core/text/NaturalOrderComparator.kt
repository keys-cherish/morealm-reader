package com.morealm.app.core.text

import java.text.Collator
import java.util.Locale

/**
 * 自然排序工具集。
 *
 * 字典序排序会出现 "书1 < 书11 < 书2"（因为字符 '1' < '2'，比较停在第二位）。
 * 自然排序按数字含义比，结果是 "书1 < 书2 < 书11"。
 *
 * # 算法：分块对比法
 * 把字符串切成 [Token] 列表，每个 token 属于以下三类之一：
 *   - 阿拉伯数字段 `\d+` → [Token.Num]，预解析为 [Long]
 *   - 中文数字段（一/二/.../十/百/千/万/亿 + 大写壹..玖 + 繁体兩/萬/億）→ [Token.Num]
 *   - 其他文字段 → [Token.Text]
 *
 * 同位 token 按类型分派比较：
 *   - 两个 [Token.Num] → [Long.compareTo]
 *   - 数字 vs 文字 → 数字段排前（"123abc" < "abc123"）
 *   - 两个 [Token.Text] → [Collator] PRIMARY 强度（中文按拼音、大小写折叠）
 *
 * 共同前缀都相等时，更短的串排前面。
 *
 * # 性能：施瓦茨变换（Schwartzian transform）
 * 朴素 [Comparator<String>][NaturalOrderComparator] 每次 compare 都得对 a / b 两串
 * 重新跑一次 [tokenize]。N 个元素排序触发 O(N log N) 次切分；当 N 大或字符串长时，
 * 这个 overhead 显著（书架成千书名 + 含正则切分 + 中文数字解析）。
 *
 * 施瓦茨变换思路：先把每个元素映射成 `(原值, 预切分 token 列表)` 二元组（O(N) 次
 * tokenize），再按预算 key 排序（O(N log N) 次纯 [Long]/Collator 比较，无切分）。
 * 最后剥掉 token 列表回到原值。
 *
 * 入口：
 *   - [Iterable.sortedNatural]: 字符串列表自身做 key
 *   - [Iterable.sortedNaturalBy]: 自定义 selector 取字段（如 `it.title`）
 *   - [Iterable.sortedNaturalWith]: 复合排序，先按主 [Comparator] 再按自然顺序
 *
 * 兼容入口：
 *   - [NaturalOrderComparator] 仍是 [Comparator<String>][Comparator]，老调用方
 *     `sortedWith(compareBy(NaturalOrderComparator) { it.title })` 不必改；只是
 *     拿不到 Schwartzian 的常数级提速。
 *
 * 不考虑：负号、小数点、千分位 — 章节 / 书名场景不会遇到，避免过度设计。
 */

/**
 * 自然排序的最小切片。`sealed` 让 [compareTokens] 用类型分派而不是字符判定，
 * 行为更可证明、零字符串运行时开销。
 */
sealed class Token {
    /** 阿拉伯数字 + 中文数字统一存为 [Long] 数值；超 `Long.MAX_VALUE` 的钳到 max。 */
    data class Num(val value: Long) : Token()

    /** 其他文字段；保留原串用作 [Collator] 比较和共同前缀长度比较。 */
    data class Text(val value: String) : Token()
}

/** 中文数字字符集合 — 含个位、位级、大写、常见繁体与口语别字。 */
private const val CHINESE_DIGIT_CHARS =
    "零〇一二三四五六七八九十百千万亿两壹贰叁肆伍陆柒捌玖拾佰仟兩萬億"

/**
 * Token 切分正则。三类互斥，类型边界即切分边界。
 *  - `\d+` 阿拉伯数字串
 *  - `[CHINESE_DIGIT_CHARS]+` 连续中文数字
 *  - `[^\d + CHINESE_DIGIT_CHARS]+` 其余字符
 */
private val tokenRegex = Regex(
    """\d+|[$CHINESE_DIGIT_CHARS]+|[^\d$CHINESE_DIGIT_CHARS]+""",
)

/** 中文 / 拉丁混排时按本地化（拼音）排序，比纯字符比较更符合用户直觉。 */
private val collator: Collator = Collator.getInstance(Locale.CHINESE).apply {
    strength = Collator.PRIMARY
}

/** 单个中文数字字符 → 个位数值。位级字符（十/百/千/万/亿）单独处理，不在此表。 */
private val cnDigit = mapOf(
    '零' to 0L, '〇' to 0L,
    '一' to 1L, '壹' to 1L,
    '二' to 2L, '贰' to 2L, '两' to 2L, '兩' to 2L,
    '三' to 3L, '叁' to 3L,
    '四' to 4L, '肆' to 4L,
    '五' to 5L, '伍' to 5L,
    '六' to 6L, '陆' to 6L,
    '七' to 7L, '柒' to 7L,
    '八' to 8L, '捌' to 8L,
    '九' to 9L, '玖' to 9L,
)

/**
 * 把字符串切成 [Token] 序列。施瓦茨变换的 "decoration" 步骤 — 一个串只切一次，
 * 结果可缓存复用。
 *
 * 阿拉伯数字段：trim 前导零后 [String.toLongOrNull]；超 [Long] 范围（19+ 位章号
 * 不会出现）回退为 [Long.MAX_VALUE]，保证保序但全归一档。
 *
 * 中文数字段：走 [parseChineseNumber] 按 个 / 十 / 百 / 千 / 万 / 亿 累加。
 *
 * 其他段：包成 [Token.Text] 留给 [Collator]。
 */
fun tokenize(s: String): List<Token> = tokenRegex.findAll(s).map { match ->
    val raw = match.value
    when (val first = raw.first()) {
        in '0'..'9' -> Token.Num(
            raw.trimStart('0').ifEmpty { "0" }.toLongOrNull() ?: Long.MAX_VALUE,
        )
        in CHINESE_DIGIT_CHARS -> Token.Num(parseChineseNumber(raw))
        else -> {
            // 借用 first 让 IDE 提示 first 是 Char；变量本身用不到，避开未使用警告。
            @Suppress("UNUSED_VARIABLE") val _typeAnchor = first
            Token.Text(raw)
        }
    }
}.toList()

/**
 * 比较两个已 [tokenize] 的 token 序列。
 *
 * 共同前缀逐位类型分派：
 *   - 两 [Token.Num] → 数值比
 *   - 一数一文 → 数字在前
 *   - 两 [Token.Text] → [Collator]
 *
 * 都相等则更短的排前。
 */
fun compareTokens(a: List<Token>, b: List<Token>): Int {
    val n = minOf(a.size, b.size)
    for (i in 0 until n) {
        val cmp = compareSingleToken(a[i], b[i])
        if (cmp != 0) return cmp
    }
    return a.size - b.size
}

private fun compareSingleToken(a: Token, b: Token): Int = when {
    a is Token.Num && b is Token.Num -> a.value.compareTo(b.value)
    a is Token.Num -> -1                                    // 数字段排前
    b is Token.Num -> 1
    else -> collator.compare((a as Token.Text).value, (b as Token.Text).value)
}

/**
 * 按字符串自然顺序排序。施瓦茨变换：每元素只切分一次。
 *
 * 推荐用法：
 *   ```kotlin
 *   list.sortedNaturalBy { it.title }
 *   ```
 *
 * 等价于 `sortedWith(compareBy(NaturalOrderComparator) { it.title })` 但 N 元素
 * 切分次数从 O(N log N) 降到 O(N)。
 */
inline fun <T> Iterable<T>.sortedNaturalBy(crossinline selector: (T) -> String): List<T> =
    map { it to tokenize(selector(it)) }
        .sortedWith(NaturalKeyedComparator)
        .map { it.first }

/** 字符串列表自身就是 key 的便捷版。 */
fun Iterable<String>.sortedNatural(): List<String> = sortedNaturalBy { it }

/**
 * 复合排序：先按 [primary] 比较器，再按 [selector] 提取的字符串自然顺序。
 *
 * 用法（书架按格式分组、组内按书名自然序）：
 *   ```kotlin
 *   list.sortedNaturalWith(compareBy { it.format.name }) { it.title }
 *   ```
 *
 * 仍走施瓦茨变换 — 主 key 不需预算（廉价），副 key 走预切分。
 */
inline fun <T> Iterable<T>.sortedNaturalWith(
    primary: Comparator<T>,
    crossinline selector: (T) -> String,
): List<T> = map { it to tokenize(selector(it)) }
    .sortedWith(
        Comparator { x, y ->
            val p = primary.compare(x.first, y.first)
            if (p != 0) p else compareTokens(x.second, y.second)
        },
    )
    .map { it.first }

/**
 * Schwartzian 排序内部用的 Comparator：直接比 token 列表，不重切分。
 * 公开是为了在 [sortedNaturalBy] 的 inline 体内能引用（inline 可见性要求）。
 */
@PublishedApi
internal val NaturalKeyedComparator: Comparator<Pair<*, List<Token>>> =
    Comparator { x, y -> compareTokens(x.second, y.second) }

/**
 * 字符串自然排序 [Comparator]。兼容老调用方
 * `sortedWith(compareBy(NaturalOrderComparator) { it.title })`。
 *
 * 注意：每次 [compare] 重新切分 a / b 两串，N 元素排序 O(N log N) 次切分。
 * 大列表场景请改走 [sortedNaturalBy] 拿施瓦茨提速。
 */
object NaturalOrderComparator : Comparator<String> {
    override fun compare(a: String, b: String): Int =
        compareTokens(tokenize(a), tokenize(b))
}

/**
 * 中文数字串 → [Long]。
 *
 * 算法：从左到右扫描，按位级（十 / 百 / 千 / 万 / 亿）累加。
 *
 * 状态：
 *   - [current]：未乘位级的暂存数字（"二百" 中扫到 "二" 时 current=2）
 *   - [section]：当前万段内累加结果（千+百+十+个，<10000）
 *   - [result]：万 / 亿乘出的高位累加
 *
 * 规则：
 *   - 遇个位 → current = 数值
 *   - 遇位级（十 / 百 / 千 / 拾 / 佰 / 仟）：若 current=0 视为 1（"十二"=12 不是 02），
 *     section += current × scale，current 清零
 *   - 遇万 / 亿：result += (section + current) × 1e4 / 1e8，section + current 清零
 *
 * 边界：
 *   - 单字 "十" = 10，"百" = 100（current=0 → 1）
 *   - "壹" / "拾" / "佰" / "仟" 大写等价
 *   - "兩" / "萬" / "億" 繁体等价
 *   - 「两」识别为 2（口语：两百 = 200）
 */
private fun parseChineseNumber(s: String): Long {
    var result = 0L
    var section = 0L
    var current = 0L
    for (c in s) {
        when (c) {
            '十', '拾' -> {
                if (current == 0L) current = 1L
                section += current * 10
                current = 0L
            }
            '百', '佰' -> {
                if (current == 0L) current = 1L
                section += current * 100
                current = 0L
            }
            '千', '仟' -> {
                if (current == 0L) current = 1L
                section += current * 1000
                current = 0L
            }
            '万', '萬' -> {
                result += (section + current) * 10_000L
                section = 0L
                current = 0L
            }
            '亿', '億' -> {
                result += (section + current) * 100_000_000L
                section = 0L
                current = 0L
            }
            else -> cnDigit[c]?.let { current = it }
        }
    }
    return result + section + current
}
