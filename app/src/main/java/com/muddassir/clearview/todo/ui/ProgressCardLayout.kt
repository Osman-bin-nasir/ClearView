package com.muddassir.clearview.todo.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.muddassir.clearview.todo.data.ProgressCardStats
import java.time.LocalDate

// ── Export geometry: ONE size, the Story format ────────────────────────

/** Card width in export pixels. */
internal const val CARD_WIDTH = 1080f

/** Card height in export pixels (9:16 Story). */
internal const val CARD_HEIGHT = 1920f

/**
 * Content margin. Everything on the card lives inside
 * ([CARD_MARGIN], [CARD_MARGIN])..([CARD_WIDTH] - [CARD_MARGIN], 1920 - it),
 * which is the safe area Instagram and WhatsApp do not crop.
 */
private const val CARD_MARGIN = 72f

// ── Card palette ───────────────────────────────────────────────────────
//
// The card's accent is NOT fixed. It comes from the range's achievement tier
// ([paletteFor]), so a Diamond card and a Bronze card are two different
// objects rather than the same poster with a different number in it. Teal —
// the app's own accent — is the neutral, used when a range has nothing to
// grade at all.

internal val CardTeal = Color(0xFF2DD4BF)
internal val CardTextDim = Color(0xFF9AA3AF)
internal val CardBgTop = Color(0xFF10161B)
internal val CardBgBottom = Color(0xFF06090D)
internal val CardDivider = Color.White.copy(alpha = 0.08f)

/**
 * The outline of a graph cell whose day sits outside the selected range — the
 * leading context weeks a short range shows without scoring them. A hollow
 * square reads as "not measured here"; a filled grey one would read as "nothing
 * done that day", which would be a lie about a day the range never covered.
 */
internal val CardGridGhost = Color.White.copy(alpha = 0.10f)

/**
 * Everything chromatic on the card, chosen once per card: the accent, the tint
 * of the glow behind the hero, and the contribution graph's intensity ramp.
 */
internal data class CardPalette(
    val accent: Color,
    val glow: Color,
    /** Index 0..4, matching [intensityLevel]. */
    val heat: List<Color>
)

/**
 * Grid shading at [accent] strength: a barely-there square for a day with
 * nothing done (so empty days read as "nothing happened", not as a missed
 * day), then four growing steps — GitHub's five-step scale, in the card's own
 * accent.
 */
internal fun heatRamp(accent: Color): List<Color> = listOf(
    Color(0xFF1A2128),
    accent.copy(alpha = 0.26f),
    accent.copy(alpha = 0.48f),
    accent.copy(alpha = 0.72f),
    accent
)

/**
 * How many intensity levels a day can be shaded at ([intensityLevel]): nothing,
 * then four steps up. The legend draws one swatch per level.
 */
internal const val HEAT_LEVELS = 5

/** The neutral (teal) ramp. */
internal val CardHeatColors = heatRamp(CardTeal)

/**
 * The palette for an achievement tier. A higher tier glows harder as well as
 * changing hue, so the card literally brightens as the user climbs — and a
 * range with no data at all ([tier] null) keeps the app's neutral teal.
 */
internal fun paletteFor(tier: ProgressCardStats.Tier?): CardPalette = when (tier) {
    null -> palette(CardTeal, 0.10f)
    ProgressCardStats.Tier.BRONZE -> palette(Color(0xFFC87F4A), 0.07f)
    ProgressCardStats.Tier.SILVER -> palette(Color(0xFFB8C4D0), 0.09f)
    ProgressCardStats.Tier.GOLD -> palette(Color(0xFFE8C168), 0.11f)
    ProgressCardStats.Tier.PLATINUM -> palette(Color(0xFF7FE3D6), 0.13f)
    ProgressCardStats.Tier.DIAMOND -> palette(Color(0xFF6FD8F5), 0.16f)
}

private fun palette(accent: Color, glowAlpha: Float) = CardPalette(
    accent = accent,
    glow = accent.copy(alpha = glowAlpha),
    heat = heatRamp(accent)
)

/** One label/value pair in the statistics table. */
internal data class CardStat(
    val label: String,
    val value: String
)

/**
 * The contribution grid, pre-computed by the caller: column-major levels, the
 * column count, and the month label for each column that opens a new month. The
 * layout only sizes and positions it.
 */
internal data class CardGrid(
    /**
     * index = column * [GRID_ROWS] + row. A level is [GRID_NO_CELL],
     * [GRID_OUTSIDE_RANGE], or an intensity 0..4.
     */
    val levels: List<Int>,
    val columns: Int,
    /** (column index, label) pairs — "Aug", "Jan 26". */
    val monthLabels: List<Pair<Int, String>> = emptyList()
)

/**
 * Resolved, localizable strings the card renders (built by the UI layer —
 * the layout engine stays purely visual).
 */
internal data class CardTexts(
    val nameProgress: String,
    val dateRange: String,
    /** e.g. "WEEKLY SCORE" / "MONTHLY SCORE". */
    val scoreTitle: String,
    /** e.g. "62 / 100"; null → [emptyRange] is shown instead of the score. */
    val scoreLine: String?,
    /** e.g. "66% completion"; shown under the score when the range has data. */
    val percentLine: String?,
    /** The range's achievement grade ("DIAMOND"); null → no badge, neutral accent. */
    val tierLabel: String? = null,
    /** The tier the card's accent is drawn from; null → the neutral teal palette. */
    val tier: ProgressCardStats.Tier? = null,
    val emptyRange: String,
    /** The statistics table: label/value pairs, laid out 2 columns × 3 rows. */
    val stats: List<CardStat> = emptyList(),
    /** One-line verdict derived from the range's real numbers; null = no range data. */
    val summary: String? = null,
    /** Caption above the contribution grid ("ACTIVITY"); "" = no grid. */
    val activityTitle: String = "",
    /** The day grid for the range; null → the section is skipped entirely. */
    val grid: CardGrid? = null,
    /** Legend ends: "Less" and "More" around the intensity ramp. */
    val legendLess: String = "Less",
    val legendMore: String = "More",
    /** One short line for an edge state ("First week of tracking"); null = none. */
    val note: String? = null,
    /** Brand line for the bottom-left of the signature footer. */
    val madeWith: String,
    /** The user's name for the bottom-right of the footer — the card's signature. */
    val signature: String = ""
)

/** An absolutely-positioned region of the card, produced by [layoutProgressCard]. */
internal data class CardRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun isInside(canvasW: Float, canvasH: Float): Boolean =
        left >= 0f && top >= 0f && right <= canvasW && bottom <= canvasH
}

/**
 * One laid-out visual element. Every element carries its final [rect], so the
 * drawing pass is a pure map over blocks and the QA gate can check bounds and
 * overlap without rendering a single pixel.
 */
internal sealed class CardBlock {
    abstract val rect: CardRect

    data class Text(
        override val rect: CardRect,
        val text: String,
        val style: TextStyle
    ) : CardBlock()

    /** A hairline: the hero divider, or one of the statistics table's rules. */
    data class Line(override val rect: CardRect) : CardBlock()

    data class Icon(override val rect: CardRect, val useAppIcon: Boolean) : CardBlock()

    /**
     * The statistics table's rounded container. Its text and hairlines are
     * separate blocks, so the QA gate checks the content against the panel it
     * actually sits in rather than trusting the drawing pass.
     */
    data class Panel(override val rect: CardRect) : CardBlock()

    /**
     * The tier badge's mark, drawn as a five-point star in the tier accent.
     * A vector shape keeps the card free of emoji, whose size and colour belong
     * to whatever font the platform happens to pick.
     */
    data class Star(override val rect: CardRect) : CardBlock()

    /**
     * The contribution grid. Its [rect] INCLUDES the month-label row on top, so
     * the QA gate covers the whole region.
     */
    data class Grid(
        override val rect: CardRect,
        /** index = column * [rows] + row; see [CardGrid.levels]. */
        val levels: List<Int>,
        val columns: Int,
        val rows: Int,
        val cell: Float,
        val gap: Float,
        /** Height of the month-label strip inside the top of [rect]. */
        val labelRow: Float,
        /** (column index, label) for the columns that open a month. */
        val monthLabels: List<Pair<Int, String>>,
        /** Font size of the month labels. */
        val labelFont: Float
    ) : CardBlock()

    /** One legend swatch (a small filled square). */
    data class Swatch(override val rect: CardRect, val level: Int) : CardBlock()
}

/** Days per contribution-graph column: Monday..Sunday, always. */
internal const val GRID_ROWS = 7

/** A cell with no day behind it (the tail of the final, partial week). */
internal const val GRID_NO_CELL = -1

/**
 * A day the graph shows but the selected range never scored — the leading
 * context weeks of a short range. Drawn as an empty cell, never as "missed".
 */
internal const val GRID_OUTSIDE_RANGE = -2

/**
 * Builds the week-aligned contribution grid for [days] (oldest first, one entry
 * per calendar day of the graph's window). Column 0 is the Monday of the week
 * containing the first day, so a window that does not open on a Monday leaves
 * empty cells at the top — exactly how a contribution graph reads.
 *
 * [rangeStart] is the first day the range actually scored. Every day before it
 * is context the graph shows but never measured, so it is marked
 * [GRID_OUTSIDE_RANGE] rather than shaded. [monthLabel] formats a column's month
 * (the caller decides locale and whether to include the year), and is only
 * called for the columns that open a month.
 */
internal fun buildCardGrid(
    days: List<ProgressCardStats.HeatDay>,
    rangeStart: LocalDate,
    monthLabel: (LocalDate) -> String
): CardGrid? {
    if (days.isEmpty()) return null
    val first = days.first().date
    val start = first.minusDays((first.dayOfWeek.value - 1).toLong())
    val columns = ((days.last().date.toEpochDay() - start.toEpochDay()) / 7).toInt() + 1
    val cells = MutableList(columns * GRID_ROWS) { GRID_NO_CELL }
    days.forEach { day ->
        val offset = (day.date.toEpochDay() - start.toEpochDay()).toInt()
        if (offset in cells.indices) {
            cells[offset] = if (day.date.isBefore(rangeStart)) GRID_OUTSIDE_RANGE
            else intensityLevel(day.done)
        }
    }
    val labels = (0 until columns).mapNotNull { column ->
        val date = start.plusDays(column * 7L)
        val prev = if (column == 0) null else start.plusDays((column - 1) * 7L)
        if (prev == null || date.monthValue != prev.monthValue) column to monthLabel(date) else null
    }
    return CardGrid(cells, columns, monthLabels = labels)
}

/**
 * The GitHub-style intensity of one day, from how many occurrences were
 * completed on it: 0 nothing, 1 one, 2 a couple, 3–4 a good day, 5+ a great
 * one. Absolute thresholds keep the scale stable across ranges — a day does not
 * change colour because the period it sits in got busier.
 */
internal fun intensityLevel(done: Int): Int = when {
    done <= 0 -> 0
    done == 1 -> 1
    done == 2 -> 2
    done <= 4 -> 3
    else -> 4
}

/**
 * Text measurement, injected so the layout engine stays pure JVM and unit
 * testable. [maxWidth] constrains wrapping — the returned Size must be the
 * size the text actually occupies when wrapped to maxWidth.
 */
internal fun interface CardMeasurer {
    fun measure(text: String, style: TextStyle, maxWidth: Float): Size
}

// ── Geometry & config ──────────────────────────────────────────────────

/**
 * The card's full layout spec. Every value is a raw pixel on the export
 * canvas (drawn at Density(1f) so 1sp == 1px). Horizontal padding comes from
 * [CARD_MARGIN]: nothing is ever allowed to exceed [left]..[right].
 */
private data class CardConfig(
    val left: Float,
    val right: Float,
    val top: Float,
    val gap: Float,
    val iconSize: Float,
    val nameFont: Float,
    val nameXGap: Float,
    val dateFont: Float,
    val gapAfterDate: Float,
    val scoreTitleFont: Float,
    val scoreFont: Float,
    val scoreMin: Float,
    val scoreGap: Float,
    val scorePercentFont: Float,
    val scorePercentGap: Float,
    // ── Tier badge (the range's achievement grade) ──
    val tierGap: Float,
    val tierFont: Float,
    val tierStar: Float,
    val tierInnerGap: Float,
    val gapAfterScore: Float,
    val dividerGap: Float,
    // ── Statistics table (one panel · 2 columns × 3 rows) ──
    val tablePad: Float,
    val tableColGap: Float,
    val tableRowGap: Float,
    val tableRule: Float,
    val statValueFont: Float,
    val statLabelFont: Float,
    val gapAfterTable: Float,
    // ── Contribution grid ──
    val gridTitleFont: Float,
    val gapAfterGridTitle: Float,
    val gridCellMax: Float,
    val gridLabelRow: Float,
    val gridLabelFont: Float,
    val gapAfterGrid: Float,
    val legendFont: Float,
    val legendSwatch: Float,
    val gapAfterLegend: Float,
    // ── Summary, edge note, footer ──
    val summaryFont: Float,
    val gapAfterSummary: Float,
    val noteFont: Float,
    val smallGap: Float,
    val footerFont: Float,
    /** Upper bound for how much slack one flexible gap may absorb. */
    val flexCap: Float
) {
    val width: Float get() = right - left
    val center: Float get() = (left + right) / 2f
}

/**
 * The Story-sized card's entire vertical budget. Four sections have to share
 * 1920px — header, hero score + tier, the statistics table, and the activity
 * graph — so the type scale is tuned for how the exported image reads on a
 * phone, NOT proportionally to the canvas, and the gaps are deliberately tight
 * enough that the widest grid (53 columns) still fits the safe area.
 */
private val CARD = CardConfig(
    left = CARD_MARGIN, right = CARD_WIDTH - CARD_MARGIN, top = 96f, gap = 26f,
    iconSize = 76f, nameFont = 42f, nameXGap = 24f,
    dateFont = 30f, gapAfterDate = 40f,
    scoreTitleFont = 22f, scoreFont = 132f, scoreMin = 72f,
    scoreGap = 16f, scorePercentFont = 32f, scorePercentGap = 12f,
    tierGap = 18f, tierFont = 30f, tierStar = 28f, tierInnerGap = 12f,
    gapAfterScore = 38f,
    dividerGap = 30f,
    tablePad = 32f, tableColGap = 36f, tableRowGap = 16f, tableRule = 2f,
    statValueFont = 52f, statLabelFont = 20f,
    gapAfterTable = 40f,
    gridTitleFont = 22f, gapAfterGridTitle = 16f,
    gridCellMax = 54f, gridLabelRow = 26f, gridLabelFont = 17f,
    gapAfterGrid = 14f,
    legendFont = 19f, legendSwatch = 18f, gapAfterLegend = 30f,
    summaryFont = 30f, gapAfterSummary = 24f, noteFont = 24f,
    smallGap = 14f,
    footerFont = 24f,
    flexCap = 72f
)

/**
 * Cell gap as a fraction of the cell itself — roughly GitHub's ~0.27 spacing.
 * The cells are solved from this ratio so the graph always spans the full
 * content column: a twelve-week grid becomes chunky squares, a year-long one
 * shrinks to fine dots, and neither ever strands a narrow band mid-card.
 */
private const val GRID_GAP_RATIO = 0.26f

/** Floor for a cell, so the widest grid (53 columns) stays visible. */
private const val MIN_GRID_CELL = 8f

/** Space always kept free above the footer, on top of the footer's own height. */
private const val FOOTER_RESERVE = 40f

/** How many gaps share the leftover vertical space (see [layoutProgressCard]). */
private const val FLEX_GAPS = 6f

/** A simple vertical flex column: y advances by measured content + gaps. */
private class Flow(
    private val left: Float,
    private val right: Float,
    private val gapSize: Float,
    private val blocks: MutableList<CardBlock>,
    private val measure: CardMeasurer
) {
    var y: Float = 0f
    val width: Float get() = right - left

    fun gap(g: Float = gapSize) {
        y += g
    }

    /**
     * Measures and places a [text] block, then advances the flow cursor below
     * it (so the next section always starts under this one's real bottom).
     */
    fun text(
        text: String,
        style: TextStyle,
        maxWidth: Float = width,
        x: Float = left,
        y: Float = this.y,
        centeredX: Float? = null
    ): CardRect {
        val size = measure.measure(text, style, maxWidth)
        val tx = centeredX?.let { it - size.width / 2f } ?: x
        val rect = CardRect(tx, y, tx + size.width, y + size.height)
        blocks += CardBlock.Text(rect, text, style)
        this.y = maxOf(this.y, y + size.height)
        return rect
    }
}

/**
 * Lays the card out in two passes over a single vertical flow (flex-column
 * semantics): every section's position follows from the section above it, all
 * text is measured and width-constrained to the padded column, and nothing is
 * placed with a hardcoded pixel offset.
 *
 * Pass 1 measures the card at its base vertical rhythm; pass 2 hands the
 * leftover height back to [FLEX_GAPS] flexible gaps (each capped at
 * [CardConfig.flexCap]) so a sparse range fills the canvas instead of leaving a
 * hole under the content, while a dense range keeps the base rhythm untouched.
 * Returns the complete block list — the drawing pass and the QA bounds/overlap
 * check both consume exactly this.
 */
internal fun layoutProgressCard(
    texts: CardTexts,
    appIconPresent: Boolean,
    /**
     * Display face for the card's numerals and headings. Nothing in this file
     * resolves an Android font resource — the caller owns that — so the layout
     * stays pure JVM and unit-testable. Null → the platform default.
     */
    displayFont: FontFamily? = null,
    measure: CardMeasurer
): List<CardBlock> {
    val probe = layoutCardOnce(texts, appIconPresent, measure, displayFont, extraGap = 0f)
    // The footer (brand + signature) is appended last, so everything before it is
    // content: its bottom decides how much slack the flexible gaps may absorb.
    val slack = (CARD_HEIGHT - CARD_MARGIN - FOOTER_RESERVE) - probe.contentBottom
    val extra = (slack / FLEX_GAPS).coerceIn(0f, CARD.flexCap)
    return if (extra <= 0f) probe.blocks
    else layoutCardOnce(texts, appIconPresent, measure, displayFont, extra).blocks
}

/** One pass's output: the blocks, plus the content bottom (footer excluded). */
private class CardLayout(val blocks: List<CardBlock>, val contentBottom: Float)

private fun layoutCardOnce(
    texts: CardTexts,
    appIconPresent: Boolean,
    measure: CardMeasurer,
    displayFont: FontFamily?,
    extraGap: Float
): CardLayout {
    val cfg = CARD
    // Every chromatic value on the card follows from the range's tier.
    val palette = paletteFor(texts.tier)
    val blocks = ArrayList<CardBlock>()
    val flow = Flow(cfg.left, cfg.right, cfg.gap, blocks, measure)
    flow.y = cfg.top

    // ── 1. Header: app icon + "[Name]'s Progress" ──
    val iconY = flow.y
    blocks += CardBlock.Icon(
        CardRect(cfg.left, iconY, cfg.left + cfg.iconSize, iconY + cfg.iconSize),
        appIconPresent
    )
    val nameStyle = TextStyle(
        fontSize = cfg.nameFont.sp, fontWeight = FontWeight.Bold,
        color = Color.White, fontFamily = displayFont
    )
    val nameMax = cfg.width - cfg.iconSize - cfg.nameXGap
    val nameSize = measure.measure(texts.nameProgress, nameStyle, nameMax)
    flow.text(
        texts.nameProgress, nameStyle, nameMax,
        x = cfg.left + cfg.iconSize + cfg.nameXGap,
        y = iconY + (cfg.iconSize - nameSize.height) / 2f
    )
    flow.y = iconY + cfg.iconSize
    flow.gap(cfg.gap + extraGap)

    // ── 2. Date range (in the tier accent) ──
    flow.text(
        texts.dateRange,
        TextStyle(
            fontSize = cfg.dateFont.sp, fontWeight = FontWeight.SemiBold,
            color = palette.accent, fontFamily = displayFont
        )
    )
    flow.gap(cfg.gapAfterDate + extraGap)

    // ── 3. Hero: caption, score, completion, tier badge ──
    flow.text(
        texts.scoreTitle,
        TextStyle(
            fontSize = cfg.scoreTitleFont.sp,
            fontWeight = FontWeight.SemiBold,
            color = CardTextDim,
            letterSpacing = (cfg.scoreTitleFont * 0.28f).sp,
            fontFamily = displayFont
        ),
        centeredX = cfg.center
    )
    flow.gap(cfg.scoreGap)
    if (texts.scoreLine != null) {
        // Auto-shrink so the score never exceeds the padded column.
        val maxScoreW = cfg.width * 0.96f
        var font = cfg.scoreFont
        var style = scoreStyle(font, displayFont)
        while (measure.measure(texts.scoreLine, style, maxScoreW).width > maxScoreW && font > cfg.scoreMin) {
            font -= 2f
            style = scoreStyle(font, displayFont)
        }
        flow.text(texts.scoreLine, style, maxWidth = maxScoreW, centeredX = cfg.center)
        texts.percentLine?.let { percent ->
            flow.gap(cfg.scorePercentGap)
            flow.text(
                percent,
                TextStyle(
                    fontSize = cfg.scorePercentFont.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.accent,
                    fontFamily = displayFont
                ),
                centeredX = cfg.center
            )
        }
    } else {
        flow.text(
            texts.emptyRange,
            TextStyle(
                fontSize = cfg.scoreTitleFont.sp,
                color = CardTextDim,
                fontFamily = displayFont
            ),
            centeredX = cfg.center
        )
    }
    // The grade the range earned, right under the number it was earned with.
    texts.tierLabel?.let { label ->
        tierBadge(flow, blocks, cfg, palette, label, displayFont, measure)
    }
    flow.gap(cfg.gapAfterScore)

    // ── 4. Hairline divider ──
    val lineY = flow.y
    blocks += CardBlock.Line(CardRect(cfg.left, lineY, cfg.right, lineY + 2f))
    flow.y = lineY + 2f
    flow.gap(cfg.dividerGap + extraGap)

    // ── 5. Statistics table ──
    if (texts.stats.isNotEmpty()) {
        statsTable(flow, blocks, cfg, texts.stats, displayFont, measure)
        flow.gap(cfg.gapAfterTable + extraGap)
    }

    // ── 6. Activity: contributions grid (GitHub-style) ──
    texts.grid?.let { grid ->
        if (texts.activityTitle.isNotEmpty()) {
            flow.text(
                texts.activityTitle,
                TextStyle(
                    fontSize = cfg.gridTitleFont.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = CardTextDim,
                    letterSpacing = (cfg.gridTitleFont * 0.28f).sp
                )
            )
            flow.gap(cfg.gapAfterGridTitle)
        }
        val top = flow.y
        // Fill the content column at GitHub's cell-to-gap ratio: twelve weeks
        // become chunky squares, a year shrinks to fine dots — either way the
        // graph spans the card instead of stranding a narrow band mid-column.
        val cell = minOf(
            cfg.gridCellMax,
            (cfg.width / (grid.columns * (1f + GRID_GAP_RATIO) - GRID_GAP_RATIO))
                .coerceAtLeast(MIN_GRID_CELL)
        )
        val cellGap = cell * GRID_GAP_RATIO
        val gridW = grid.columns * cell + (grid.columns - 1) * cellGap
        val gridH = GRID_ROWS * cell + (GRID_ROWS - 1) * cellGap
        val left = cfg.center - gridW / 2f
        val totalH = cfg.gridLabelRow + gridH
        blocks += CardBlock.Grid(
            CardRect(left, top, left + gridW, top + totalH),
            grid.levels, grid.columns, GRID_ROWS, cell, cellGap,
            cfg.gridLabelRow, grid.monthLabels, cfg.gridLabelFont
        )
        flow.y = top + totalH
        flow.gap(cfg.gapAfterGrid)
        legendRow(flow, blocks, cfg, texts.legendLess, texts.legendMore, measure)
        flow.gap(cfg.gapAfterLegend + extraGap)
    }

    // ── 7. One-line verdict built from the range's real numbers ──
    texts.summary?.let { summary ->
        flow.text(
            summary,
            TextStyle(
                fontSize = cfg.summaryFont.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.86f),
                fontFamily = displayFont
            ),
            maxWidth = cfg.width * 0.92f,
            centeredX = cfg.center
        )
        flow.gap(cfg.gapAfterSummary + extraGap)
    }

    // ── 8. An edge-state line ("First week of tracking") or the best day —
    // never a fake number ──
    texts.note?.let { note ->
        flow.text(
            note,
            TextStyle(fontSize = cfg.noteFont.sp, color = CardTextDim, fontFamily = displayFont),
            maxWidth = cfg.width,
            centeredX = cfg.center
        )
    }

    // ── 9. Signature footer: brand left, the user's name right ──
    val contentBottom = probeBottom(blocks)
    val footerStyle = TextStyle(
        fontSize = cfg.footerFont.sp,
        color = CardTextDim,
        letterSpacing = (cfg.footerFont * 0.18f).sp,
        fontFamily = displayFont
    )
    // Both halves are measured against the FULL canvas width, so the credit line
    // is always exactly one line tall. That keeps the footer's height bounded —
    // a wrapped signature could otherwise grow past the bottom of the card.
    val brand = measure.measure(texts.madeWith, footerStyle, CARD_WIDTH)
    // Pinned near the canvas bottom so the credit never floats mid-card — but
    // never above where the content already ends (an overflowing layout stays
    // flagged by the QA gate instead of being silently clipped).
    val footerY = maxOf(
        contentBottom + FOOTER_RESERVE,
        CARD_HEIGHT - CARD_MARGIN - brand.height
    )
    blocks += CardBlock.Text(
        CardRect(cfg.left, footerY, cfg.left + brand.width, footerY + brand.height),
        texts.madeWith, footerStyle
    )
    // The signature only appears when it cannot collide with the brand: a long
    // name loses the right-hand slot rather than overprinting the credit.
    if (texts.signature.isNotEmpty()) {
        val sig = measure.measure(texts.signature, footerStyle, CARD_WIDTH)
        if (cfg.left + brand.width + cfg.smallGap * 2f + sig.width <= cfg.right) {
            blocks += CardBlock.Text(
                CardRect(
                    cfg.right - sig.width, footerY,
                    cfg.right, footerY + sig.height
                ),
                texts.signature, footerStyle
            )
        }
    }
    return CardLayout(blocks, contentBottom)
}

/** The hero number: Black weight, the display face, nothing else. */
private fun scoreStyle(font: Float, displayFont: FontFamily?) = TextStyle(
    fontSize = font.sp,
    fontWeight = FontWeight.Black,
    color = Color.White,
    fontFamily = displayFont
)

/**
 * The tier badge: a vector star plus the range's grade, centered under the
 * score in the tier's own accent — the line that says how the period actually
 * went, rather than leaving the reader to interpret a bare number.
 */
private fun tierBadge(
    flow: Flow,
    blocks: MutableList<CardBlock>,
    cfg: CardConfig,
    palette: CardPalette,
    label: String,
    displayFont: FontFamily?,
    measure: CardMeasurer
) {
    flow.gap(cfg.tierGap)
    val style = TextStyle(
        fontSize = cfg.tierFont.sp,
        fontWeight = FontWeight.Bold,
        color = palette.accent,
        letterSpacing = (cfg.tierFont * 0.24f).sp,
        fontFamily = displayFont
    )
    val text = measure.measure(label, style, cfg.width)
    val unitW = cfg.tierStar + cfg.tierInnerGap + text.width
    val x = cfg.center - unitW / 2f
    val y = flow.y
    blocks += CardBlock.Star(
        CardRect(
            x, y + (text.height - cfg.tierStar) / 2f,
            x + cfg.tierStar, y + (text.height + cfg.tierStar) / 2f
        )
    )
    blocks += CardBlock.Text(
        CardRect(x + cfg.tierStar + cfg.tierInnerGap, y, x + unitW, y + text.height),
        label, style
    )
    flow.y = y + text.height
}

/**
 * The statistics table: one rounded panel carrying [stats] as a 2-column grid
 * split by hairlines, the way a player card carries its attributes. Every cell
 * is measured before anything is placed, so all rows share a single height and
 * the panel reads as a grid however long a label runs — and the QA gate can
 * check each cell against the panel box it actually sits in.
 */
private fun statsTable(
    flow: Flow,
    blocks: MutableList<CardBlock>,
    cfg: CardConfig,
    stats: List<CardStat>,
    displayFont: FontFamily?,
    measure: CardMeasurer
) {
    val cols = 2
    val rows = (stats.size + cols - 1) / cols
    val valueStyle = TextStyle(
        fontSize = cfg.statValueFont.sp, fontWeight = FontWeight.Bold,
        color = Color.White, fontFamily = displayFont
    )
    val labelStyle = TextStyle(
        fontSize = cfg.statLabelFont.sp, color = CardTextDim,
        letterSpacing = (cfg.statLabelFont * 0.2f).sp, fontFamily = displayFont
    )
    val colW = (cfg.width - cfg.tablePad * 2f) / cols
    val cellW = colW - cfg.tableColGap
    val valueH = stats.maxOf { measure.measure(it.value, valueStyle, cellW).height }
    val labelH = stats.maxOf { measure.measure(it.label, labelStyle, cellW).height }
    val rowH = valueH + cfg.tableRowGap + labelH
    val innerH = rows * rowH + (rows - 1) * cfg.tableRule
    val panelH = cfg.tablePad * 2f + innerH
    val top = flow.y
    blocks += CardBlock.Panel(CardRect(cfg.left, top, cfg.right, top + panelH))

    // A rule between each pair of rows, and one down the middle between the
    // columns — the two devices that make a list of numbers read as a table.
    for (row in 1 until rows) {
        val y = top + cfg.tablePad + row * rowH + (row - 1) * cfg.tableRule
        blocks += CardBlock.Line(
            CardRect(cfg.left + cfg.tablePad, y, cfg.right - cfg.tablePad, y + cfg.tableRule)
        )
    }
    val dividerTop = top + cfg.tablePad
    blocks += CardBlock.Line(
        CardRect(
            cfg.center - cfg.tableRule / 2f, dividerTop,
            cfg.center + cfg.tableRule / 2f, dividerTop + innerH
        )
    )

    stats.forEachIndexed { index, stat ->
        val x = cfg.left + cfg.tablePad + (index % cols) * colW
        val y = top + cfg.tablePad + (index / cols) * (rowH + cfg.tableRule)
        blocks += CardBlock.Text(CardRect(x, y, x + cellW, y + valueH), stat.value, valueStyle)
        blocks += CardBlock.Text(
            CardRect(x, y + valueH + cfg.tableRowGap, x + cellW, y + rowH),
            stat.label, labelStyle
        )
    }
    flow.y = top + panelH
}

/** Bottom of everything laid out so far (used to place and reserve the footer). */
private fun probeBottom(blocks: List<CardBlock>): Float =
    blocks.maxOfOrNull { it.rect.bottom } ?: CARD_MARGIN

/**
 * The grid legend, GitHub-style: "Less ▫▪▪▪▪ More" — one swatch per intensity
 * level, centered as a single row under the grid. A scale reads faster than
 * four labelled swatches and matches how the graph itself is read.
 */
private fun legendRow(
    flow: Flow,
    blocks: MutableList<CardBlock>,
    cfg: CardConfig,
    lessLabel: String,
    moreLabel: String,
    measure: CardMeasurer
) {
    if (lessLabel.isEmpty() && moreLabel.isEmpty()) return
    val style = TextStyle(fontSize = cfg.legendFont.sp, color = CardTextDim)
    val textGap = cfg.legendSwatch * 0.6f
    val swatchGap = cfg.legendSwatch * 0.34f
    val less = measure.measure(lessLabel, style, cfg.width)
    val more = measure.measure(moreLabel, style, cfg.width)
    val swatchesW = HEAT_LEVELS * cfg.legendSwatch + (HEAT_LEVELS - 1) * swatchGap
    val totalW = less.width + textGap + swatchesW + textGap + more.width
    val rowH = maxOf(cfg.legendSwatch, less.height, more.height)
    val y = flow.y
    var x = cfg.center - totalW / 2f
    blocks += CardBlock.Text(
        CardRect(x, y + (rowH - less.height) / 2f, x + less.width, y + (rowH + less.height) / 2f),
        lessLabel, style
    )
    x += less.width + textGap
    repeat(HEAT_LEVELS) { level ->
        blocks += CardBlock.Swatch(
            CardRect(x, y + (rowH - cfg.legendSwatch) / 2f, x + cfg.legendSwatch, y + (rowH + cfg.legendSwatch) / 2f),
            level
        )
        x += cfg.legendSwatch + swatchGap
    }
    x += textGap - swatchGap
    blocks += CardBlock.Text(
        CardRect(x, y + (rowH - more.height) / 2f, x + more.width, y + (rowH + more.height) / 2f),
        moreLabel, style
    )
    flow.y = y + rowH
}

/**
 * QA gate: returns a description for every block that sits outside the canvas
 * or overlaps another block. The unit tests assert this is empty for the
 * worst-case layouts, and the UI logs any hits at render time — so an
 * off-by-pixel regression is caught by the build instead of shipping.
 */
internal fun verifyCardLayout(blocks: List<CardBlock>, canvasW: Float, canvasH: Float): List<String> {
    val issues = ArrayList<String>()
    blocks.forEach { b ->
        if (!b.rect.isInside(canvasW, canvasH)) {
            issues += "out-of-bounds ${b.javaClass.simpleName} ${b.rect}"
        }
    }
    val eps = 1f
    for (i in blocks.indices) {
        for (j in i + 1 until blocks.size) {
            // A panel is a BACKGROUND: its own value/label cells and its rules
            // sit inside it by design, so container ↔ content is not an overlap
            // bug. Both are still bounds-checked above, and two texts from
            // different cells must never overlap — that is still checked here.
            if (blocks[i] is CardBlock.Panel || blocks[j] is CardBlock.Panel) continue
            // Table rules cross each other by design (the column divider runs
            // through both row rules), but a rule crossing a TEXT is a bug and
            // stays checked below.
            if (blocks[i] is CardBlock.Line && blocks[j] is CardBlock.Line) continue
            val a = blocks[i].rect
            val b = blocks[j].rect
            if (a.left < b.right - eps && b.left < a.right - eps &&
                a.top < b.bottom - eps && b.top < a.bottom - eps
            ) {
                issues += "overlap ${blocks[i].javaClass.simpleName} $a × ${blocks[j].javaClass.simpleName} $b"
            }
        }
    }
    return issues
}
