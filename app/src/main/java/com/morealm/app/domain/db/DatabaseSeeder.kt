package com.morealm.app.domain.db

import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.entity.TagDefinition
import com.morealm.app.domain.entity.TagType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seeds the [tag_definitions] table on first launch (or after a wipe).
 *
 * **Idempotent**: each seed tag has a stable `id` (built-in:<name>) so re-runs
 * are no-ops thanks to `INSERT OR IGNORE`. We *don't* overwrite existing rows —
 * users who edit a built-in tag's keywords keep their changes across upgrades.
 *
 * Why seed at all rather than ship them as static data?
 *   - Users want to add their own keywords to "玄幻" / rename "言情" → "总裁文" / etc.
 *   - Once they edit, the row becomes "owned" by them; we just preserve it.
 */
@Singleton
class DatabaseSeeder @Inject constructor(
    private val tagDao: TagDefinitionDao,
) {

    /** Run once at app start. Cheap when seeded — single SELECT to count. */
    suspend fun seedIfNeeded() {
        val existing = tagDao.getTagsByTypeSync(TagType.GENRE)
        if (existing.size >= GENRE_SEED.size) {
            return // Already seeded (or extended by user) — nothing to do.
        }
        val now = System.currentTimeMillis()
        val tags = GENRE_SEED.mapIndexed { idx, seed ->
            TagDefinition(
                id = "builtin:${seed.name}",
                name = seed.name,
                type = TagType.GENRE,
                keywords = seed.keywords,
                color = seed.color,
                icon = seed.icon,
                sortOrder = idx,
                builtin = true,
                createdAt = now,
            )
        }
        tagDao.insertAll(tags)
        AppLog.info("Seeder", "Inserted ${tags.size} built-in genre tags")
    }

    private data class Seed(
        val name: String,
        val icon: String,
        val color: String,
        val keywords: String,
    )

    companion object {
        /**
         * Built-in 中文 web-novel genre vocabulary.
         *
         * Order matters: more specific tags first (修真 wins over 玄幻 when a
         * book is unambiguously 修真). Colors are loosely "warm = action-y,
         * cool = romance / sci-fi / mystery", picked to look pleasant on both
         * light and dark themes.
         */
        private val GENRE_SEED: List<Seed> = listOf(
            Seed("修真", "⛩", "#7E57C2", "修真,仙侠,修仙,渡劫,金丹,元婴,飞升,问鼎,真人,化神"),
            Seed("玄幻", "🐉", "#FF7043", "玄幻,魔法,异界,斗气,斗破,斗罗,魔兽,战神,大陆"),
            Seed("武侠", "⚔️", "#8D6E63", "武侠,江湖,剑客,金庸,古龙,梁羽生,内功,招式"),
            Seed("都市", "🌆", "#42A5F5", "都市,都市异能,都市修真,都市重生,豪门,娱乐圈"),
            Seed("历史", "🏯", "#A1887F", "历史,穿越历史,三国,明朝,宋朝,汉朝,唐朝,春秋,战国,秦"),
            Seed("军事", "🎖", "#558B2F", "军事,抗战,兵王,特种,战争,军旅,二战"),
            Seed("科幻", "🚀", "#26A69A", "科幻,末世,星际,机甲,未来,异能,超能,克隆,基因"),
            Seed("网游", "🎮", "#5C6BC0", "网游,游戏世界,电竞,竞技,虚拟现实,主播"),
            Seed("悬疑", "🕵", "#37474F", "悬疑,推理,侦探,刑侦,密室,凶案"),
            Seed("灵异", "👻", "#6D4C41", "灵异,诡异,鬼故事,盗墓,鬼吹,茅山"),
            Seed("恐怖", "💀", "#212121", "恐怖,惊悚,血腥,猎奇"),
            Seed("言情", "💕", "#EC407A", "言情,总裁,豪门,女频,宠妻,婚恋,霸总,虐恋"),
            Seed("同人", "✨", "#AB47BC", "同人,二创,衍生"),
            Seed("二次元", "🎌", "#26C6DA", "二次元,动漫,轻小说,日漫"),
            Seed("短篇", "📃", "#9E9E9E", "短篇,故事集,合集"),
        )
    }
}
