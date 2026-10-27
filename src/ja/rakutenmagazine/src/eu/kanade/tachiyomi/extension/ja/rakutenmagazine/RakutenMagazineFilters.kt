package eu.kanade.tachiyomi.extension.ja.rakutenmagazine

import eu.kanade.tachiyomi.source.model.Filter

class CategoryFilter : Filter.Select<String>(
    "カテゴリー (Category)",
    CATEGORIES.map { it.first }.toTypedArray(),
) {
    val selectedCategoryId: String
        get() = CATEGORIES[state].second

    val selectedCategoryName: String
        get() = CATEGORIES[state].first
}

class SortFilter : Filter.Select<String>(
    "並び順 (Sort)",
    arrayOf("人気順 (Ranking)", "新着順 (Publication Date)"),
) {
    val isByDate: Boolean
        get() = state == 1
}

class FeaturedOnlyFilter : Filter.CheckBox("注目雑誌のみ (Featured / Notice Titles Only)", false)

internal val CATEGORIES = listOf(
    "すべて (All)" to "",
    "女性ファッション" to "312",
    "女性ライフスタイル" to "313",
    "グルメ・トラベル" to "314",
    "趣味" to "315",
    "生活・暮らし" to "316",
    "ニュース・週刊誌" to "317",
    "ビジネス・経済・国際" to "318",
    "IT・ガジェット" to "319",
    "スポーツ・アウトドア" to "320",
    "男性ファッション" to "321",
    "男性ライフスタイル" to "322",
    "国内旅行ガイド(ムック)" to "324",
    "海外旅行ガイド(ムック)" to "327",
    "ヘア・ビューティー" to "328",
    "車・バイク" to "329",
    "芸能・エンタメ" to "330",
    "Foreign Magazines" to "331",
    "健康" to "333",
    "料理" to "334",
    "キッズ・ファミリー" to "335",
)
