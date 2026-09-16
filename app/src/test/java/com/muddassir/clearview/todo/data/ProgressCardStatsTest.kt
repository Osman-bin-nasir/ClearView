package com.muddassir.clearview.todo.data

import com.muddassir.clearview.todo.model.TodoItem
import com.muddassir.clearview.todo.model.TodoPriority
import com.muddassir.clearview.todo.model.TodoType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressCardStatsTest {

    // 2026-08-12 is a Wednesday. WEEK = trailing 7 days (2026-08-06 .. 08-12);
    // MONTH = trailing 30; DAY90 = trailing 90. All ranges end at today.
    private val today: LocalDate = LocalDate.of(2026, 8, 12)
    private val monday: LocalDate = LocalDate.of(2026, 8, 10)

    private fun millis() = 1L

    private fun item(
        id: String,
        title: String = "Todo",
        start: LocalDate = monday,
        end: LocalDate? = null,
        permanent: Boolean = true,
        completions: Map<LocalDate, Long> = emptyMap(),
        priority: TodoPriority = TodoPriority.NORMAL,
        createdAt: LocalDate = start
    ): TodoItem = TodoItem(
        id = id,
        title = title,
        type = if (permanent) TodoType.PERMANENT else TodoType.TEMPORARY,
        startDateEpochDay = start.toEpochDay(),
        endDateEpochDay = end?.toEpochDay(),
        scheduledDays = null,
        completions = completions.entries.associate { it.key.toEpochDay() to it.value },
        priority = priority,
        createdAtEpochMillis = createdAt.atStartOfDay(ZoneId.systemDefault())
            .toInstant().toEpochMilli()
    )

    // ── Ranges ──────────────────────────────────────────────────────

    @Test
    fun `resolveRange today is a single day`() {
        val (from, to) = ProgressCardStats.resolveRange(
            ProgressCardStats.RangeKind.TODAY, emptyList(), today
        )
        assertEquals(today, from)
        assertEquals(today, to)
    }

    @Test
    fun `resolveRange week is the trailing seven days`() {
        val (from, to) = ProgressCardStats.resolveRange(
            ProgressCardStats.RangeKind.WEEK, emptyList(), today
        )
        assertEquals(today.minusDays(6), from)
        assertEquals(today, to)
    }

    @Test
    fun `resolveRange month is the trailing thirty days`() {
        val (from, to) = ProgressCardStats.resolveRange(
            ProgressCardStats.RangeKind.MONTH, emptyList(), today
        )
        assertEquals(today.minusDays(29), from)
        assertEquals(today, to)
    }

    @Test
    fun `resolveRange day90 is the trailing ninety days`() {
        val (from, to) = ProgressCardStats.resolveRange(
            ProgressCardStats.RangeKind.DAY90, emptyList(), today
        )
        assertEquals(today.minusDays(89), from)
        assertEquals(today, to)
    }

    @Test
    fun `resolveRange custom clamps to today and collapses inverted ranges`() {
        val future = ProgressCardStats.resolveRange(
            ProgressCardStats.RangeKind.CUSTOM, emptyList(), today,
            customFrom = LocalDate.of(2026, 7, 1), customTo = LocalDate.of(2026, 12, 31)
        )
        assertEquals(LocalDate.of(2026, 7, 1), future.first)
        assertEquals(today, future.second)

        val inverted = ProgressCardStats.resolveRange(
            ProgressCardStats.RangeKind.CUSTOM, emptyList(), today,
            customFrom = LocalDate.of(2026, 8, 11), customTo = LocalDate.of(2026, 8, 1)
        )
        assertEquals(LocalDate.of(2026, 8, 1), inverted.first)
        assertEquals(LocalDate.of(2026, 8, 1), inverted.second)
    }

    // ── Counts ──────────────────────────────────────────────────────

    @Test
    fun `week counts completed and missed occurrences`() {
        val items = listOf(
            item("q", "Read Qur'an", completions = mapOf(
                monday to millis(),
                monday.plusDays(1) to millis()
            ))
        )
        val stats = ProgressCardStats.compute(
            items, ProgressCardStats.RangeKind.WEEK, today
        )
        // Active only Mon..Wed: Mon+Tue completed, Wed (today) pending.
        assertEquals(2, stats.completed)
        assertEquals(1, stats.missed)
        assertEquals(3, stats.due)
        assertEquals(66, stats.percent)
        assertEquals(2, stats.activeDays)
        assertEquals(2, stats.perfectDays)
        assertEquals(7, stats.rangeDays)
    }

    @Test
    fun `created counts todo instances created inside the range only`() {
        val items = listOf(
            item("a", createdAt = monday),             // in range
            item("b", createdAt = today),              // in range
            item("c", createdAt = monday.minusDays(10)) // before the range → out
        )
        val stats = ProgressCardStats.compute(
            items, ProgressCardStats.RangeKind.WEEK, today
        )
        assertEquals(2, stats.created)
    }

    // ── Unique todos ────────────────────────────────────────────────

    @Test
    fun `unique counts are distinct titles in each category`() {
        val items = listOf(
            // Two instances of the same title → one unique "created".
            item("q1", title = "Read Qur'an", createdAt = monday),
            item("q2", title = "Read Qur'an", createdAt = monday.plusDays(1)),
            item("f", title = "Fajr Salah", createdAt = today,
                completions = mapOf(today to millis())),
            // Created before the range, completed in it.
            item("s", title = "Study", createdAt = monday.minusDays(10),
                completions = mapOf(monday to millis())),
            // Created in the range but never due/completed in it.
            item("g", title = "Gym", start = today.plusDays(2), createdAt = today)
        )
        val stats = ProgressCardStats.compute(
            items, ProgressCardStats.RangeKind.WEEK, today
        )
        // Distinct titles created in range: Read Qur'an, Fajr Salah, Gym.
        assertEquals(3, stats.uniqueCreated)
        // Distinct titles completed in range: Fajr Salah, Study.
        assertEquals(2, stats.uniqueCompleted)
        // Distinct titles with ZERO completions but ≥1 occurrence: Read Qur'an
        // only (Study was completed at least once, so it goes under Completed).
        assertEquals(1, stats.uniqueIncomplete)
    }

    @Test
    fun `completed todos are titles with completions sorted by ratio`() {
        val items = listOf(
            item("a", title = "Always", completions = mapOf(
                monday to millis(), monday.plusDays(1) to millis(), today to millis()
            )), // 3/3
            item("b", title = "Patchy", completions = mapOf(monday to millis())), // 1/3
            item("c", title = "Untouched", completions = emptyMap()) // 0/3 → incomplete
        )
        val stats = ProgressCardStats.compute(
            items, ProgressCardStats.RangeKind.WEEK, today
        )
        assertEquals(listOf("Always", "Patchy"), stats.completedTodos.map { it.title })
        assertEquals("3/3", ratioOf(stats.completedTodos.first()))
        // A partially-done todo still appears in Completed with its true ratio.
        assertEquals("1/3", ratioOf(stats.completedTodos.last()))
    }

    @Test
    fun `incomplete todos are zero-completion titles sorted by last active`() {
        val items = listOf(
            item("old", title = "Old", start = monday),                 // last active Mon
            item("new", title = "New", start = today)                   // last active today
        )
        val stats = ProgressCardStats.compute(
            items, ProgressCardStats.RangeKind.WEEK, today
        )
        assertEquals(listOf("New", "Old"), stats.incompleteTodos.map { it.title })
        assertTrue(stats.incompleteTodos.all { it.done == 0 })
    }

    @Test
    fun `best day picks the highest completion ratio`() {
        // A three-day custom range. Both todos due every day; only day 1 has a
        // completion (1 of 2 done) — the best (and only positive) day.
        val from = today.minusDays(2)
        val items = listOf(
            item("a", start = from, completions = mapOf(from to millis())),
            item("b", start = from)
        )
        val stats = ProgressCardStats.compute(
            items, ProgressCardStats.RangeKind.CUSTOM, today,
            customFrom = from, customTo = today
        )
        val best = stats.bestDay
        assertEquals(from, best?.date)
        assertEquals(1, best?.done)
        assertEquals(2, best?.due)
    }

    @Test
    fun `most consistent prefers a meaningful sample and most repeated is by occurrences`() {
        val items = listOf(
            item("x", title = "One Hit", completions = mapOf(monday to millis())),   // 1/1
            item("y", title = "Steady", completions = mapOf(
                monday to millis(), monday.plusDays(1) to millis()
            )),                                                                    // 2/3
            item("z", title = "Heavy", start = monday, completions = emptyMap())     // 0/3
        )
        val stats = ProgressCardStats.compute(
            items, ProgressCardStats.RangeKind.WEEK, today
        )
        // "Steady" (2/3 over a real sample) beats "One Hit" (1/1).
        assertEquals("Steady", stats.mostConsistent?.title)
        // "Heavy" and "Steady" both have 3 occurrences; tie-break favours the
        // one with more completions.
        assertEquals("Steady", stats.mostRepeated?.title)
    }

    @Test
    fun `most consistent falls back when no title has three occurrences`() {
        val stats = ProgressCardStats.compute(
            listOf(item("x", title = "Only", completions = mapOf(monday to millis()))),
            ProgressCardStats.RangeKind.WEEK, today
        )
        assertEquals("Only", stats.mostConsistent?.title)
    }

    @Test
    fun `avg per day divides by the range length`() {
        val stats = ProgressCardStats.compute(
            listOf(item("q", completions = mapOf(monday to millis(), monday.plusDays(1) to millis()))),
            ProgressCardStats.RangeKind.WEEK, today
        )
        assertEquals(2f / 7f, stats.avgPerDay, 0.001f)
    }

    // ── Streaks ─────────────────────────────────────────────────────

    @Test
    fun `current streak ends at to and skips an unfinished final day`() {
        val completions = mapOf(
            monday to millis(),
            monday.plusDays(1) to millis()
        )
        val stats = ProgressCardStats.compute(
            listOf(item("q", completions = completions)),
            ProgressCardStats.RangeKind.WEEK, today
        )
        // Today (Wed) has no completion → the streak ends at Tue.
        assertEquals(2, stats.currentStreak)
        assertEquals(2, stats.bestStreak)
    }

    @Test
    fun `current streak survives a completed today`() {
        val completions = mapOf(
            monday to millis(),
            monday.plusDays(1) to millis(),
            today to millis()
        )
        val stats = ProgressCardStats.compute(
            listOf(item("q", completions = completions)),
            ProgressCardStats.RangeKind.WEEK, today
        )
        assertEquals(3, stats.currentStreak)
    }

    @Test
    fun `best streak finds the longest run while current streak ends at today`() {
        val from = LocalDate.of(2026, 8, 1)
        val completions = mapOf(
            from to millis(),
            from.plusDays(1) to millis(),
            from.plusDays(2) to millis(),
            from.plusDays(9) to millis(),  // Aug 10
            from.plusDays(10) to millis()  // Aug 11
        )
        val stats = ProgressCardStats.compute(
            listOf(item("q", completions = completions)),
            ProgressCardStats.RangeKind.CUSTOM, today,
            customFrom = from, customTo = today
        )
        assertEquals(3, stats.bestStreak)
        assertEquals(2, stats.currentStreak)
    }

    // ── Heatmap ─────────────────────────────────────────────────────

    /** The heat verdict for one day of the grid window (by date, not index). */
    private fun ProgressCardStats.CardStats.heatOn(date: LocalDate): ProgressCardStats.Heat =
        heatmap.first { it.date == date }.heat

    /** The grid window for a range, optionally custom. */
    private fun window(
        kind: ProgressCardStats.RangeKind,
        from: LocalDate? = null
    ): List<ProgressCardStats.HeatDay> = ProgressCardStats.compute(
        emptyList(), kind, today,
        customFrom = from, customTo = if (from != null) today else null
    ).heatmap

    @Test
    fun `heatmap window is Monday-aligned and ends on the range's last day`() {
        val stats = ProgressCardStats.compute(
            listOf(item("q")), ProgressCardStats.RangeKind.WEEK, today
        )
        assertEquals(DayOfWeek.MONDAY, stats.heatmap.first().date.dayOfWeek)
        assertEquals(today, stats.heatmap.last().date)
        // 12 week columns, so a 7-day range still draws a real contribution
        // graph instead of a single row of seven boxes.
        assertEquals(ProgressCardStats.HEATMAP_MIN_WEEKS * 7 - 4, stats.heatmap.size)
    }

    @Test
    fun `heatmap week reflects done missed and still-pending days`() {
        val items = listOf(
            item("q", completions = mapOf(monday to millis(), monday.plusDays(1) to millis()))
        )
        val stats = ProgressCardStats.compute(
            items, ProgressCardStats.RangeKind.WEEK, today
        )
        // Before the todo starts (Thu..Sun) there is no activity.
        assertEquals(ProgressCardStats.Heat.NONE, stats.heatOn(today.minusDays(6)))
        // Mon and Tue were completed, today is pending → NONE (not missed yet).
        assertEquals(ProgressCardStats.Heat.DONE, stats.heatOn(monday))
        assertEquals(ProgressCardStats.Heat.DONE, stats.heatOn(monday.plusDays(1)))
        assertEquals(ProgressCardStats.Heat.NONE, stats.heatOn(today))
    }

    @Test
    fun `heatmap marks missed days missed and today pending`() {
        // Permanent daily todo with NO completions: Mon+Tue were missed,
        // today is still actionable.
        val stats = ProgressCardStats.compute(
            listOf(item("q", completions = emptyMap())),
            ProgressCardStats.RangeKind.WEEK, today
        )
        assertEquals(ProgressCardStats.Heat.MISSED, stats.heatOn(monday))
        assertEquals(ProgressCardStats.Heat.MISSED, stats.heatOn(monday.plusDays(1)))
        assertEquals(ProgressCardStats.Heat.NONE, stats.heatOn(today))
    }

    @Test
    fun `heatmap marks partially completed days partial`() {
        val items = listOf(
            item("a", completions = mapOf(monday to millis())),
            item("b", completions = emptyMap())
        )
        val stats = ProgressCardStats.compute(
            items, ProgressCardStats.RangeKind.WEEK, today
        )
        assertEquals(ProgressCardStats.Heat.PARTIAL, stats.heatOn(monday))
    }

    @Test
    fun `every range covers at least twelve week columns and a year at most`() {
        // Short ranges are floored to the same twelve columns, so the card
        // always gets a graph rather than a stub.
        assertEquals(ProgressCardStats.HEATMAP_MIN_WEEKS * 7 - 4, window(ProgressCardStats.RangeKind.WEEK).size)
        assertEquals(ProgressCardStats.HEATMAP_MIN_WEEKS * 7 - 4, window(ProgressCardStats.RangeKind.MONTH).size)

        // A 90-day range needs 14 columns, so it keeps its own span.
        val day90 = window(ProgressCardStats.RangeKind.DAY90)
        assertTrue(
            "90 days lost its own span: ${day90.size}",
            day90.size > ProgressCardStats.HEATMAP_MIN_WEEKS * 7
        )
        assertEquals(DayOfWeek.MONDAY, day90.first().date.dayOfWeek)
        // The window opens on the Monday of the range's own first week, so no
        // scored day can fall off the front of the graph.
        assertTrue(day90.first().date.isBefore(today.minusDays(89)))
        assertTrue(day90.first().date.isAfter(today.minusDays(96)))
    }

    @Test
    fun `custom heatmap keeps its span up to a full rolling year`() {
        val from = today.minusDays(200)
        val stats = ProgressCardStats.compute(
            emptyList(), ProgressCardStats.RangeKind.CUSTOM, today,
            customFrom = from, customTo = today
        )
        // 200 days fit inside the one-year cap, whole weeks back from the Monday
        // of the range's own first week — so the window can never open after
        // the range starts and drop a scored day.
        assertTrue(stats.heatmap.first().date.isBefore(from))
        assertTrue(stats.heatmap.first().date.isAfter(from.minusDays(7)))
        assertEquals(today, stats.heatmap.last().date)

        // Anything longer is clamped to 53 week columns.
        val long = ProgressCardStats.compute(
            emptyList(), ProgressCardStats.RangeKind.CUSTOM, today,
            customFrom = today.minusDays(500), customTo = today
        )
        assertEquals(ProgressCardStats.HEATMAP_MAX_WEEKS * 7 - 4, long.heatmap.size)
        assertEquals(today, long.heatmap.last().date)
    }

    @Test
    fun `today heatmap still covers the context window`() {
        val stats = ProgressCardStats.compute(
            listOf(item("q")), ProgressCardStats.RangeKind.TODAY, today
        )
        assertEquals(ProgressCardStats.HEATMAP_MIN_WEEKS * 7 - 4, stats.heatmap.size)
        assertEquals(today, stats.heatmap.last().date)
        assertEquals(ProgressCardStats.Heat.NONE, stats.heatOn(today))
    }

    // ── Tier ────────────────────────────────────────────────────────

    @Test
    fun `tier grades a score and refuses to grade an empty range`() {
        // Nothing to grade → no tier at all (the card keeps its neutral accent).
        assertNull(ProgressCardStats.tierFor(null))

        assertEquals(ProgressCardStats.Tier.BRONZE, ProgressCardStats.tierFor(0))
        assertEquals(ProgressCardStats.Tier.BRONZE, ProgressCardStats.tierFor(34))
        assertEquals(ProgressCardStats.Tier.SILVER, ProgressCardStats.tierFor(35))
        assertEquals(ProgressCardStats.Tier.SILVER, ProgressCardStats.tierFor(54))
        assertEquals(ProgressCardStats.Tier.GOLD, ProgressCardStats.tierFor(55))
        assertEquals(ProgressCardStats.Tier.GOLD, ProgressCardStats.tierFor(74))
        assertEquals(ProgressCardStats.Tier.PLATINUM, ProgressCardStats.tierFor(75))
        assertEquals(ProgressCardStats.Tier.PLATINUM, ProgressCardStats.tierFor(89))
        assertEquals(ProgressCardStats.Tier.DIAMOND, ProgressCardStats.tierFor(90))
        assertEquals(ProgressCardStats.Tier.DIAMOND, ProgressCardStats.tierFor(100))
    }

    @Test
    fun `a range is graded only when it has something to grade`() {
        // Nothing due → no score, and therefore no tier: a card can never be
        // handed a Bronze for an empty week.
        val empty = ProgressCardStats.compute(
            emptyList(), ProgressCardStats.RangeKind.WEEK, today
        )
        assertNull(empty.score)
        assertNull(ProgressCardStats.tierFor(empty.score))

        // As soon as the range carries data it grades — the card never renders a
        // score without a tier badge beside it.
        val scored = ProgressCardStats.compute(
            listOf(item("q", completions = mapOf(monday to millis()))),
            ProgressCardStats.RangeKind.WEEK, today
        )
        assertNotNull(scored.score)
        assertNotNull("a scored range must be graded", ProgressCardStats.tierFor(scored.score))
    }

    // ── First week & score ──────────────────────────────────────────

    @Test
    fun `firstWeek is true with fewer than seven data days`() {
        val stats = ProgressCardStats.compute(
            listOf(item("q", completions = mapOf(monday to millis()))),
            ProgressCardStats.RangeKind.WEEK, today
        )
        assertTrue(stats.firstWeek)
    }

    @Test
    fun `score is null without any occurrences and full when everything done`() {
        val none = ProgressCardStats.compute(emptyList(), ProgressCardStats.RangeKind.WEEK, today)
        assertNull(none.score)
        assertNull(none.breakdown)

        // A 12-day custom range with EVERY day completed gives a streak of 7
        // (the cap) → Completion 55 + Consistency 20 + Streak 15 + Timeliness
        // 10 (11 closed items, none missed) = 100.
        val from = LocalDate.of(2026, 8, 1)
        val completions = (0L..11L).associate { from.plusDays(it) to millis() }
        val stats = ProgressCardStats.compute(
            listOf(item("q", completions = completions)),
            ProgressCardStats.RangeKind.CUSTOM, today,
            customFrom = from, customTo = today
        )
        assertEquals(100, stats.score)
        assertEquals(100, stats.breakdown!!.completion + stats.breakdown.consistency +
            stats.breakdown.streak + stats.breakdown.timeliness)
    }

    @Test
    fun `zero completions with due todos scores zero not a free 30`() {
        // The v2 core rule: a component only scores when there is something to
        // measure. 1 due Medium todo, not yet overdue, 0 completed → every
        // included component is 0. The OLD algorithm gifted 30 for doing
        // nothing (5 for having no high-priority todos + 25 for nothing being
        // overdue) — that inflated score was the reported bug.
        val stats = ProgressCardStats.compute(
            listOf(item("q", "Read Qur'an")),
            ProgressCardStats.RangeKind.WEEK, today
        )
        assertEquals(0, stats.score)
    }

    @Test
    fun `completing a high priority todo earns more than a low one`() {
        // Two daily todos (one Low, one High), only Monday completed. Priority
        // is a multiplier INSIDE the single weighted Completion bucket, so the
        // same amount of work earns more when the completed todo was High.
        fun weekWith(priority: TodoPriority, completions: Map<LocalDate, Long>) =
            ProgressCardStats.compute(
                listOf(
                    item("lo", priority = TodoPriority.LOW),
                    item("hi", priority = TodoPriority.HIGH, completions = completions)
                ),
                ProgressCardStats.RangeKind.WEEK, today
            )
        val highDone = weekWith(TodoPriority.HIGH, mapOf(monday to millis()))
        val highScore = highDone.score!!
        val lowDone = ProgressCardStats.compute(
            listOf(
                item("lo", priority = TodoPriority.LOW, completions = mapOf(monday to millis())),
                item("hi", priority = TodoPriority.HIGH)
            ),
            ProgressCardStats.RangeKind.WEEK, today
        ).score!!
        assertTrue(
            "high-done ($highScore) should outscore low-done ($lowDone)",
            highScore > lowDone
        )
    }

    @Test
    fun `score drops when occurrences were missed`() {
        // 1 done Monday; Tuesday passed uncompleted (a genuine miss); today is
        // still pending (costs nothing — the miss is reflected inside
        // Completion and Timeliness).
        val completions = mapOf(monday to millis())
        val stats = ProgressCardStats.compute(
            listOf(item("q", completions = completions)),
            ProgressCardStats.RangeKind.WEEK, today
        )
        assertEquals(2, stats.missed)
        val score = stats.score!!
        assertTrue("score should be well below 100 (was $score)", score < 100)
        assertTrue(score > 0)
    }

    @Test
    fun `timeliness is excluded from the breakdown while nothing closed`() {
        // A todo due only today (still pending, window open): no closed items,
        // so Timeliness is excluded and the breakdown has 0 closedItems.
        val stats = ProgressCardStats.compute(
            listOf(item("q", start = today)),
            ProgressCardStats.RangeKind.TODAY, today
        )
        assertEquals(0, stats.score)
        assertEquals(0, stats.breakdown!!.closedItems)
    }

    private fun ratioOf(todo: ProgressCardStats.UniqueTodo): String =
        "${todo.done}/${todo.due}"
}
