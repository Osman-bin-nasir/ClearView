package com.muddassir.clearview.quran.model

/**
 * A single Quran verse from the Sahih International English translation.
 *
 * @property surahNumber  1-based surah number (1..114)
 * @property ayahNumber   Verse number within its surah (1-based)
 * @property surahName    English transliterated surah name, e.g. "At-Talaaq"
 * @property surahTranslation English meaning of the surah name, e.g. "Divorce"
 * @property text         English verse text (Sahih International)
 * @property arabicText   Arabic verse text (IndoPak script, quran-indopak).
 *                        Empty string when the Arabic edition is not cached
 *                        yet (the widget and detail screen still work — they
 *                        just show English).
 * @property totalAyahs   Number of ayahs in this verse's surah (e.g. 7 for
 *                        Al-Faatiha). Derived from the downloaded edition —
 *                        never hardcoded — so the reader can show
 *                        "ayah / total" progress. 0 when the count isn't known
 *                        yet (verse persisted by an older build before its
 *                        surah counts had been derived); the repository
 *                        backfills it as soon as the cache is parsed.
 */
data class QuranVerse(
    val surahNumber: Int,
    val ayahNumber: Int,
    val surahName: String,
    val surahTranslation: String,
    val text: String,
    val arabicText: String = "",
    val totalAyahs: Int = 0
)
