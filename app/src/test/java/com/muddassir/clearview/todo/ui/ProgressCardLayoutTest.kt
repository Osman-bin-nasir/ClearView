package com.muddassir.clearview.todo.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.TextStyle
import com.muddassir.clearview.todo.data.ProgressCardStats
import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA gate for the Story-format Progress Card: with a deterministic stand-in for
 * the platform TextMeasurer, every worst-case composition (long name, long
 * signature, first-week note, a year-long contribution grid) must fit inside
 * the 1080×1920 canvas with no overlaps. The check [verifyCardLayout] runs on
 * every real layout, so a regression here fails the build instead of shipping a
 * clipped or overlapping card.
 *
 * The grid builder and the tier palette are covered separately, so neither the
 * graph's shape nor the card's accent can silently rot.
 */
class ProgressCardLayoutTest {

    /** A Monday, so the grid's week alignment is easy to reason about. */
    private val today = LocalDate.of(2026, 8, 10)

    /** The six numbers the statistics table carries, in table order. */
    private val stats = listOf(
        CardStat("COMPLETED", "128"),
        CardStat("ACTIVE DAYS", "42"),
        CardStat("CURRENT STREAK", "12 days"),
        CardStat("BEST STREAK", "21"),
        CardStat("PERFECT DAYS", "9"),
        CardStat("INCOMPLETE", "6")
    )

    /**
     * [count] consecutive days ending today, each carrying [done] completions.
     * [heat] is only the day's verdict; the grid shades by [done].
     */
    private fun days(
        count: Int,
        done: Int = 0,
        heat: ProgressCardStats.Heat = ProgressCardStats.Heat.NONE
    ): List<ProgressCardStats.HeatDay> =
        (0 until count).map { i ->
            ProgressCardStats.HeatDay(today.minusDays((count - 1 - i).toLong()), heat, done)
        }

    /**
     * The window the card actually renders for a 7-day range: 78 Monday-aligned
     * days ending today (12 week columns, of which only the last 7 are scored).
     */
    private fun weekWindow(done: Int = 0) = days(78, done = done)

    /** A ~year-long range: 371 days, i.e. 53 whole week columns plus its lead-in. */
    private fun yearGrid(): CardGrid =
        buildCardGrid(days(371, done = 2), today.minusDays(370)) { d ->
            if (d.monthValue == 1) "Jan 26" else "Aug"
        }!!

    private fun texts(
        name: String = "Abdul Rahman",
        scoreLine: String? = "62 / 100",
        percentLine: String? = "66% completion",
        tier: ProgressCardStats.Tier? = ProgressCardStats.Tier.GOLD,
        firstWeek: Boolean = false,
        grid: CardGrid? = yearGrid(),
        signature: String = "ABDUL RAHMAN"
    ) = CardTexts(
        nameProgress = "$name's Progress",
        dateRange = "Aug 4 - Aug 10, 2026",
        scoreTitle = "WEEKLY SCORE",
        scoreLine = scoreLine,
        percentLine = percentLine,
        tierLabel = tier?.name,
        tier = tier,
        emptyRange = "No todos due in this period",
        stats = stats,
        summary = "Solid period \u2014 most of your planned work got done.",
        activityTitle = "ACTIVITY",
        grid = grid,
        legendLess = "LESS",
        legendMore = "MORE",
        note = if (firstWeek) "First week of tracking" else null,
        madeWith = "MADE WITH CLEARVIEW",
        signature = signature
    )

    /**
     * Deterministic stand-in for the real TextMeasurer: Latin glyphs measure
     * ~0.58em wide / 1.22em tall. Both factors deliberately overshoot Barlow
     * Condensed (which is narrower and ~1.2em tall), so the gate fails on the
     * safe side rather than waving through a card that clips on a real device.
     */
    private fun fakeMeasure(text: String, style: TextStyle, maxWidth: Float): Size {
        // TextStyle() defaults are TextUnit.Unspecified, whose .value is NaN —
        // treat unspecified as 0 so widths stay finite.
        val fs = style.fontSize.value.takeUnless { it.isNaN() || it.isInfinite() } ?: 0f
        val ls = style.letterSpacing.value.takeUnless { it.isNaN() || it.isInfinite() } ?: 0f
        val singleW = text.length * (fs * 0.58f + ls)
        val max = maxWidth.coerceAtLeast(1f)
        val lines = ceil(singleW / max).toInt().coerceAtLeast(1)
        return Size(min(singleW, max), lines * fs * 1.22f)
    }

    /** The most content-dense composition: long name, long signature, first-week note. */
    private fun worstCase(): List<CardBlock> = layoutProgressCard(
        texts(
            name = "Abdul RahmanAbdul Rahman".take(24),
            firstWeek = true,
            signature = "ABDUL RAHMAN ABDUL RAHMAN"
        ),
        appIconPresent = true,
        measure = ::fakeMeasure
    )

    @Test
    fun `worst case stays inside the story canvas and never overlaps`() {
        val issues = verifyCardLayout(worstCase(), 1080f, 1920f)
        assertTrue("story layout issues: $issues", issues.isEmpty())
    }

    @Test
    fun `every kind of range keeps its whole card inside the safe area`() {
        // A 7-day range is the chunkiest grid and a year the widest, so between
        // them they cover the extremes the layout has to survive.
        listOf(yearGrid(), buildCardGrid(weekWindow(), today.minusDays(6)) { "Aug" }!!)
            .forEach { grid ->
                val blocks = layoutProgressCard(
                    texts(firstWeek = true, grid = grid),
                    appIconPresent = true, measure = ::fakeMeasure
                )
                val issues = verifyCardLayout(blocks, 1080f, 1920f)
                assertTrue("grid ${grid.columns} cols: $issues", issues.isEmpty())
            }
    }

    @Test
    fun `score line never exceeds the padded content column`() {
        val blocks = layoutProgressCard(texts(), appIconPresent = true, measure = ::fakeMeasure)
        val score = blocks.filterIsInstance<CardBlock.Text>().first { it.text.startsWith("62 / 100") }
        val contentWidth = 1080f - 64f * 2f
        assertTrue(
            "score too wide: ${score.rect.width} > $contentWidth",
            score.rect.width <= contentWidth + 1f
        )
        assertTrue("score off-canvas", score.rect.isInside(1080f, 1920f))
    }

    @Test
    fun `empty range shows the empty message instead of a score`() {
        val blocks = layoutProgressCard(
            texts(scoreLine = null, percentLine = null, tier = null),
            appIconPresent = true, measure = ::fakeMeasure
        )
        val empty = blocks.filterIsInstance<CardBlock.Text>()
            .first { it.text == "No todos due in this period" }
        assertTrue(empty.rect.isInside(1080f, 1920f))
        // Nothing to grade → no badge, and the card stays on the neutral accent.
        assertTrue(blocks.none { it is CardBlock.Star })
    }

    // ── Statistics table ────────────────────────────────────────────

    @Test
    fun `statistics sit in one panel as a labelled 2 by 3 grid`() {
        val blocks = layoutProgressCard(texts(), appIconPresent = true, measure = ::fakeMeasure)
        val panel = blocks.filterIsInstance<CardBlock.Panel>().single()
        assertTrue("panel escapes the canvas", panel.rect.isInside(1080f, 1920f))
        assertEquals("panel should span the content column", 1080f - 144f, panel.rect.width, 1f)

        val textBlocks = blocks.filterIsInstance<CardBlock.Text>()
        stats.forEach { stat ->
            val value = textBlocks.first { it.text == stat.value }
            val label = textBlocks.first { it.text == stat.label }
            // Every cell lives inside the panel it belongs to.
            assertTrue("'${stat.value}' outside the panel", panel.rect.contains(value.rect))
            assertTrue("'${stat.label}' outside the panel", panel.rect.contains(label.rect))
            // Value on top, caption underneath — the table's reading order.
            assertTrue(
                "'${stat.label}' is not under its value",
                label.rect.top >= value.rect.bottom - 0.01f
            )
        }
    }

    @Test
    fun `the table's columns and row rules line up`() {
        val blocks = layoutProgressCard(texts(), appIconPresent = true, measure = ::fakeMeasure)
        val values = stats.map { s ->
            blocks.filterIsInstance<CardBlock.Text>().first { it.text == s.value }.rect
        }
        // Rows pair up left/right: 0+1, 2+3, 4+5 share a top and split the column.
        listOf(0, 2, 4).forEach { i ->
            assertEquals("row $i values are not aligned", values[i].top, values[i + 1].top, 0.01f)
            assertTrue("column 2 starts before column 1 ends", values[i + 1].left > values[i].left)
            assertTrue("row $i columns overlap", values[i].right <= values[i + 1].left)
        }
        // Rows stack top-down.
        assertTrue(values[0].bottom < values[2].top)
        assertTrue(values[2].bottom < values[4].top)

        val lines = blocks.filterIsInstance<CardBlock.Line>()
        // Hero divider + two row rules are horizontal; one column divider is vertical.
        assertEquals(4, lines.size)
        assertEquals(3, lines.count { it.rect.width > it.rect.height })
        assertEquals(1, lines.count { it.rect.height > it.rect.width })
    }

    @Test
    fun `an empty statistics list leaves the panel out entirely`() {
        val blocks = layoutProgressCard(
            CardTexts(
                nameProgress = "A's Progress",
                dateRange = "Aug 10, 2026",
                scoreTitle = "TODAY SCORE",
                scoreLine = null,
                percentLine = null,
                emptyRange = "No todos due in this period",
                stats = emptyList(),
                madeWith = "MADE WITH CLEARVIEW"
            ),
            appIconPresent = false, measure = ::fakeMeasure
        )
        assertTrue(blocks.none { it is CardBlock.Panel })
        // Only the hero divider remains: no table, so no rules either.
        assertEquals(1, blocks.filterIsInstance<CardBlock.Line>().size)
        val issues = verifyCardLayout(blocks, 1080f, 1920f)
        assertTrue("no-stats layout issues: $issues", issues.isEmpty())
    }

    // ── Tier badge and palette ──────────────────────────────────────

    @Test
    fun `the tier badge carries a star and the grade inside the canvas`() {
        val blocks = layoutProgressCard(texts(), appIconPresent = true, measure = ::fakeMeasure)
        val star = blocks.filterIsInstance<CardBlock.Star>().single()
        val badge = blocks.filterIsInstance<CardBlock.Text>().single { it.text == "GOLD" }
        assertTrue(star.rect.isInside(1080f, 1920f))
        assertTrue(badge.rect.isInside(1080f, 1920f))
        // The star sits just left of the grade it belongs to, and outside the
        // hero number above it.
        assertTrue(star.rect.right <= badge.rect.left)
        val score = blocks.filterIsInstance<CardBlock.Text>().first { it.text == "62 / 100" }
        assertTrue("badge overlaps the score", badge.rect.top >= score.rect.bottom)
    }

    @Test
    fun `the tier palette replaces the accent and ramps its own graph`() {
        assertTrue("a range with no data must stay neutral", paletteFor(null).accent == CardTeal)
        val gold = paletteFor(ProgressCardStats.Tier.GOLD)
        assertTrue("gold must not reuse the neutral accent", gold.accent != CardTeal)
        // The graph shades in the card's own accent, not a fixed teal.
        assertEquals(5, gold.heat.size)
        assertEquals(gold.accent, gold.heat.last())
        val diamond = paletteFor(ProgressCardStats.Tier.DIAMOND)
        assertEquals(heatRamp(diamond.accent), diamond.heat)
    }

    @Test
    fun `a higher tier glows harder than a lower one`() {
        val bronze = paletteFor(ProgressCardStats.Tier.BRONZE).glow.alpha
        val silver = paletteFor(ProgressCardStats.Tier.SILVER).glow.alpha
        val diamond = paletteFor(ProgressCardStats.Tier.DIAMOND).glow.alpha
        assertTrue("silver should out-glow bronze", silver > bronze)
        assertTrue("diamond should out-glow silver", diamond > silver)
    }

    // ── Contribution grid ───────────────────────────────────────────

    @Test
    fun `a year long grid fits the padded content column`() {
        val blocks = layoutProgressCard(texts(), appIconPresent = true, measure = ::fakeMeasure)
        val grid = blocks.filterIsInstance<CardBlock.Grid>().single()
        assertEquals(7, grid.rows)
        // 371 days, Monday-aligned: 53 whole week columns, plus one when the
        // window does not open on a Monday.
        assertTrue("unexpected column count ${grid.columns}", grid.columns in 53..54)
        assertTrue("grid escapes the canvas", grid.rect.isInside(1080f, 1920f))
        assertTrue("grid wider than the content column", grid.rect.width <= 1080f - 64f * 2f + 1f)
    }

    @Test
    fun `a seven day range still renders a twelve column week grid`() {
        // This is the shape the card used to get wrong: a single row of seven
        // big squares instead of a contribution graph.
        val grid = buildCardGrid(weekWindow(), today.minusDays(6)) { "Aug" }!!
        assertEquals(12, grid.columns)
        assertEquals(12 * GRID_ROWS, grid.levels.size)
        // Only the trailing week is scored; the other eleven columns are context.
        val scored = grid.levels.count { it != GRID_OUTSIDE_RANGE && it != GRID_NO_CELL }
        assertEquals(7, scored)
        assertEquals(GRID_OUTSIDE_RANGE, grid.levels[0])

        val blocks = layoutProgressCard(
            texts(grid = grid), appIconPresent = true, measure = ::fakeMeasure
        )
        val placed = blocks.filterIsInstance<CardBlock.Grid>().single()
        assertEquals(7, placed.rows)
        assertEquals(12, placed.columns)
        assertTrue("grid escapes the canvas", placed.rect.isInside(1080f, 1920f))
        // Twelve weeks is the chunkiest grid the card draws: big squares, not dots.
        assertTrue("cells too small: ${placed.cell}", placed.cell >= 40f)
    }

    @Test
    fun `days before the range are context, never shaded activity`() {
        val grid = buildCardGrid(weekWindow(done = 3), today.minusDays(6)) { "Aug" }!!
        // 78 days of window, 7 of them inside the range — and because today is
        // a Monday, the final column's other six rows are days that have not
        // happened yet, so they carry no cell at all.
        assertEquals(71, grid.levels.count { it == GRID_OUTSIDE_RANGE })
        assertEquals(7, grid.levels.count { it == intensityLevel(3) })
        assertEquals(6, grid.levels.count { it == GRID_NO_CELL })
        assertTrue(
            "an in-range day was drawn as context",
            grid.levels.drop(71).take(7).all { it == intensityLevel(3) }
        )
        assertTrue(
            "a context day was shaded as activity",
            grid.levels.take(71).none { it >= 0 }
        )
    }

    @Test
    fun `grid aligns the first day to its weekday and maps every intensity`() {
        // A window that does not open on a Monday leaves the empty cells above
        // its first day, exactly how a contribution graph reads.
        val start = today.minusDays(30)
        val heat = listOf(
            ProgressCardStats.Heat.NONE,
            ProgressCardStats.Heat.MISSED,
            ProgressCardStats.Heat.PARTIAL,
            ProgressCardStats.Heat.DONE
        )
        val source = (0 until 31).map { i ->
            ProgressCardStats.HeatDay(start.plusDays(i.toLong()), heat[i % heat.size], i % 4)
        }
        val grid = buildCardGrid(source, start) { "Aug" }!!
        val lead = start.dayOfWeek.value - 1
        assertEquals(GRID_NO_CELL, grid.levels[0])
        assertEquals(0, grid.levels[lead])
        assertEquals(1, grid.levels[lead + 1])
        assertEquals(2, grid.levels[lead + 2])
        assertEquals(3, grid.levels[lead + 3])
        assertTrue(grid.monthLabels.isNotEmpty())
    }

    @Test
    fun `empty day list yields no grid`() {
        assertEquals(null, buildCardGrid(emptyList(), today) { "Aug" })
    }

    @Test
    fun `legend swatches and labels all fit inside the canvas`() {
        val blocks = layoutProgressCard(texts(), appIconPresent = true, measure = ::fakeMeasure)
        val swatches = blocks.filterIsInstance<CardBlock.Swatch>()
        // One swatch per intensity level, left to right.
        assertEquals(HEAT_LEVELS, swatches.size)
        swatches.forEach { assertTrue(it.rect.isInside(1080f, 1920f)) }
        assertTrue("swatches out of order", swatches[0].rect.left < swatches[1].rect.left)
        listOf("LESS", "MORE").forEach { label ->
            val block = blocks.filterIsInstance<CardBlock.Text>().first { it.text == label }
            assertTrue("legend '$label' escapes the canvas", block.rect.isInside(1080f, 1920f))
        }
    }

    // ── Footer ──────────────────────────────────────────────────────

    @Test
    fun `footer splits the brand left and the signature right`() {
        val blocks = layoutProgressCard(texts(), appIconPresent = true, measure = ::fakeMeasure)
        val brand = blocks.filterIsInstance<CardBlock.Text>().first { it.text == "MADE WITH CLEARVIEW" }
        val signature = blocks.filterIsInstance<CardBlock.Text>().first { it.text == "ABDUL RAHMAN" }
        // Just inside the content margins, at the same baseline.
        assertEquals(72f, brand.rect.left, 0.5f)
        assertEquals(1080f - 72f, signature.rect.right, 0.5f)
        assertEquals(brand.rect.top, signature.rect.top, 0.01f)
        assertTrue("brand and signature collide", brand.rect.right < signature.rect.left)
        // Anchored near the bottom, never floating mid-card.
        assertTrue("footer floats at ${brand.rect.top}", brand.rect.top > 1920f * 0.85f)
    }

    @Test
    fun `an overlong signature is dropped rather than overprinting the brand`() {
        val blocks = layoutProgressCard(
            texts(signature = "ABDUL RAHMAN ABDUL RAHMAN ABDUL".repeat(3)),
            appIconPresent = true, measure = ::fakeMeasure
        )
        val brand = blocks.filterIsInstance<CardBlock.Text>().first { it.text == "MADE WITH CLEARVIEW" }
        assertTrue(brand.rect.isInside(1080f, 1920f))
        // No signature block was placed at all — it was dropped, not squeezed.
        val signatures = blocks.filterIsInstance<CardBlock.Text>().filter { it.rect.right > 1000f }
        assertTrue("an overlong signature still got drawn", signatures.isEmpty())
        val issues = verifyCardLayout(blocks, 1080f, 1920f)
        assertTrue("long-signature layout issues: $issues", issues.isEmpty())
    }

    @Test
    fun `an empty signature leaves the brand alone`() {
        val blocks = layoutProgressCard(
            texts(signature = ""), appIconPresent = true, measure = ::fakeMeasure
        )
        assertNotNull(
            blocks.filterIsInstance<CardBlock.Text>().firstOrNull { it.text == "MADE WITH CLEARVIEW" }
        )
    }

    // ── The gate itself ─────────────────────────────────────────────

    @Test
    fun `verify flags blocks that exceed the canvas`() {
        val blocks = listOf(
            CardBlock.Text(CardRect(100f, 100f, 200f, 140f), "ok", TextStyle()),
            CardBlock.Text(CardRect(1050f, 1900f, 1200f, 1950f), "overflow", TextStyle())
        )
        val issues = verifyCardLayout(blocks, 1080f, 1920f)
        assertTrue(
            "expected out-of-bounds flagged, got $issues",
            issues.any { it.startsWith("out-of-bounds") }
        )
    }

    @Test
    fun `verify flags overlapping blocks`() {
        val blocks = listOf(
            CardBlock.Text(CardRect(100f, 100f, 300f, 140f), "a", TextStyle()),
            CardBlock.Text(CardRect(250f, 100f, 400f, 140f), "b", TextStyle())
        )
        val issues = verifyCardLayout(blocks, 1080f, 1920f)
        assertTrue("expected overlap flagged, got $issues", issues.any { it.startsWith("overlap") })
    }

    @Test
    fun `verify flags a rule drawn across a cell`() {
        val blocks = listOf(
            CardBlock.Text(CardRect(100f, 100f, 300f, 140f), "a", TextStyle()),
            CardBlock.Line(CardRect(0f, 110f, 1080f, 112f))
        )
        val issues = verifyCardLayout(blocks, 1080f, 1920f)
        assertTrue("expected rule-over-text flagged, got $issues", issues.any { it.startsWith("overlap") })
    }

    @Test
    fun `a card without a grid still lays out cleanly`() {
        val blocks = layoutProgressCard(
            texts(grid = null), appIconPresent = false, measure = ::fakeMeasure
        )
        assertTrue(blocks.none { it is CardBlock.Grid })
        val issues = verifyCardLayout(blocks, 1080f, 1920f)
        assertTrue("no-grid layout issues: $issues", issues.isEmpty())
    }

    @Test
    fun `a card without a score keeps its layout clean`() {
        val blocks = layoutProgressCard(
            texts(scoreLine = null, percentLine = null, tier = null),
            appIconPresent = true, measure = ::fakeMeasure
        )
        assertNull(blocks.filterIsInstance<CardBlock.Text>().firstOrNull { it.text == "GOLD" })
        val issues = verifyCardLayout(blocks, 1080f, 1920f)
        assertTrue("no-score layout issues: $issues", issues.isEmpty())
    }
}

/** True when [inner] sits entirely inside [this] rect (with a sub-pixel slack). */
private fun CardRect.contains(inner: CardRect): Boolean =
    inner.left >= left - 0.01f && inner.top >= top - 0.01f &&
        inner.right <= right + 0.01f && inner.bottom <= bottom + 0.01f
