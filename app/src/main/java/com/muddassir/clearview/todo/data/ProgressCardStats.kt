package com.muddassir.clearview.todo.data

import com.muddassir.clearview.todo.model.TodoItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Pure, unit-testable computation behind the shareable Progress Card — the
 * compact analytics dashboard the card renders. No Android dependencies.
 *
 * A "range" is [from]..[to] inclusive, never extending beyond today. Every
 * occurrence (completed or missed) is counted per applicable day exactly like
 * the rest of the app ([TodoCodec.isActiveOn] / [TodoCodec.completedOn]), so
 * archived temporary todos keep contributing and future days never count as
 * progress or as missed.
 *
 * Ranges mirror the card's selector: TODAY, WEEK (trailing 7 days), MONTH
 * (trailing 30 days), DAY90 (trailing 90 days) and CUSTOM.
 *
 * The generalized [CardStats.score] (0..100, null when the range has no data)
 * mirrors the v2 Weekly Score exactly: Completion 55 (priority-weighted),
 * Consistency 20, Streak 15 (capped at 7), Timeliness 10 — with the same
 * exclusion rule (a component whose denominator is 0 is dropped and the
 * remaining weights rescale to 100, so nothing ever earns free points for
 * being empty). [CardStats.breakdown] carries the earned per-component points
 * for the card's SCORE BREAKDOWN section.
 *
 * "Unique" statistics count each todo TITLE once: [CardStats.uniqueCompleted]
 * is the number of distinct titles with ≥1 completion in the range,
 * [CardStats.completedTodos] the per-title lists with their completion ratios
 * ("7/7"), and [CardStats.incompleteTodos] the titles with zero completions,
 * most recently active first.
 */
object ProgressCardStats {

    /** The time ranges the card can summarize. */
    enum class RangeKind { TODAY, WEEK, MONTH, DAY90, CUSTOM }

    /**
     * The contribution grid always carries at least this many week columns, so
     * even a one-day range renders a real GitHub-style heatmap (twelve weeks of
     * surrounding context) instead of a single lonely square.
     */
    const val HEATMAP_MIN_WEEKS = 12

    /**
     * The most week columns the grid carries — 53, a full rolling year. Longer
     * ranges shrink the cells to fit rather than dropping days from the card.
     */
    const val HEATMAP_MAX_WEEKS = 53

    /**
     * The achievement grade a range earns. Exactly one per card, derived from
     * [CardStats.score]: the card renders its accent colour, its glow and its
     * badge from it, so a Diamond period and a Bronze one are visibly different
     * objects rather than the same poster with a different number on it.
     */
    enum class Tier { BRONZE, SILVER, GOLD, PLATINUM, DIAMOND }

    /**
     * The [Tier] for a 0..100 [score], or null when the range has no data at all
     * — there is nothing to grade there, so the card keeps its neutral accent
     * rather than handing out a Bronze for an empty week.
     *
     * The bands widen at the bottom on purpose. The score's four components
     * (priority-weighted completion, consistency, streak, timeliness) make 90+
     * genuinely rare, so the top two tiers have to be earned across a whole
     * period rather than won on one good afternoon.
     */
    fun tierFor(score: Int?): Tier? = when {
        score == null -> null
        score >= 90 -> Tier.DIAMOND
        score >= 75 -> Tier.PLATINUM
        score >= 55 -> Tier.GOLD
        score >= 35 -> Tier.SILVER
        else -> Tier.BRONZE
    }

    /** One day of the activity strip / heatmap grid. */
    enum class Heat { DONE, PARTIAL, MISSED, NONE }

    /**
     * One day of the strip/grid. [done] is how many occurrences were completed
     * that day — the raw number behind the GitHub-style intensity scale, so the
     * card can shade a day by how much was actually achieved rather than only
     * by its [Heat] verdict.
     */
    data class HeatDay(val date: LocalDate, val heat: Heat, val done: Int = 0)

    /** One distinct todo title with its occurrence counts inside the range. */
    data class UniqueTodo(
        val title: String,
        val done: Int,
        val due: Int,
        /** Latest day (epoch) the title was active or completed, for sorting. */
        val lastActiveEpochDay: Long
    ) {
        val ratio: Float get() = if (due > 0) done.toFloat() / due else 0f
    }

    data class BestDay(val date: LocalDate, val done: Int, val due: Int)

    /** The v2 score broken into its earned per-component points. */
    data class ScoreBreakdown(
        val completion: Int, val completionMax: Int,
        val consistency: Int, val consistencyMax: Int,
        val streak: Int, val streakMax: Int,
        /** Streak days used for scoring (capped at 7). */
        val streakDays: Int,
        val timeliness: Int, val timelinessMax: Int,
        /** 0 → Timeliness was excluded (nothing closed yet). */
        val closedItems: Int
    )

    /** Everything the card renders, computed for one range. */
    data class CardStats(
        val from: LocalDate,
        val to: LocalDate,
        val rangeKind: RangeKind,
        /** Todo INSTANCES first created inside the range. */
        val created: Int,
        /** Completed occurrences in the range. */
        val completed: Int,
        /**
         * Uncompleted occurrences in the range up to and including today —
         * the card's "Incomplete" figure (today's still-pending todos are
         * incomplete, not yet missed).
         */
        val missed: Int,
        /** Days in the range with at least one completion. */
        val activeDays: Int,
        /** Days where every due occurrence was completed. */
        val perfectDays: Int,
        /** Consecutive completed days ending at [to] (or the day before it). */
        val currentStreak: Int,
        /** Longest run of consecutive completed days within the range. */
        val bestStreak: Int,
        /** Generalized 0..100 score; null when the range has no occurrences. */
        val score: Int?,
        /** The score's per-component points; null when [score] is null. */
        val breakdown: ScoreBreakdown?,
        /** Distinct titles first created inside the range. */
        val uniqueCreated: Int,
        /** Distinct titles with ≥1 completion in the range. */
        val uniqueCompleted: Int,
        /** Distinct titles with ≥1 uncompleted occurrence in the range. */
        val uniqueIncomplete: Int,
        /** Titles with ≥1 completion, best completion ratio first. */
        val completedTodos: List<UniqueTodo>,
        /** Titles with zero completions, most recently active first. */
        val incompleteTodos: List<UniqueTodo>,
        /** The day with the highest completion ratio (ties: most done, latest). */
        val bestDay: BestDay?,
        /** The title with the best completion ratio (prefers ≥3 occurrences). */
        val mostConsistent: UniqueTodo?,
        /** The title with the most occurrences in the range. */
        val mostRepeated: UniqueTodo?,
        /** Completed occurrences per day of the range (1 decimal at display). */
        val avgPerDay: Float,
        /**
         * Daily activity for the contribution grid: a Monday-aligned window
         * ENDING AT [to], carrying the range's own week span but never fewer
         * than [HEATMAP_MIN_WEEKS] columns and never more than
         * [HEATMAP_MAX_WEEKS]. It can open before [from] — those leading days
         * are context for the graph, never part of the range's numbers — and
         * always ends at [to], so the days after it in the final week are
         * simply absent. Oldest first.
         */
        val heatmap: List<HeatDay>,
        /** True when the range has fewer than 7 days carrying any data. */
        val firstWeek: Boolean
    ) {
        val due: Int get() = completed + missed

        /** Completion rate as whole percent (0 when nothing was due). */
        val percent: Int get() = if (due > 0) (completed.toFloat() / due * 100).toInt() else 0

        /** Inclusive length of the range in days. */
        val rangeDays: Long get() = to.toEpochDay() - from.toEpochDay() + 1

        /** Unique completion rate: uniqueCompleted / uniqueCreated. */
        val uniquePercent: Int get() =
            if (uniqueCreated > 0) (uniqueCompleted.toFloat() / uniqueCreated * 100).toInt() else 0
    }

    /** Per-title accumulation while walking the range. */
    private data class TitleAcc(var done: Int = 0, var due: Int = 0, var lastActive: Long = Long.MIN_VALUE)

    /**
     * The inclusive [from]..[to] bounds of [kind], never extending beyond
     * [today]. CUSTOM ranges are clamped to today and to a sane order (an
     * inverted range collapses to its end day).
     */
    fun resolveRange(
        kind: RangeKind,
        items: List<TodoItem>,
        today: LocalDate,
        customFrom: LocalDate? = null,
        customTo: LocalDate? = null
    ): Pair<LocalDate, LocalDate> = when (kind) {
        RangeKind.TODAY -> today to today
        RangeKind.WEEK -> today.minusDays(6) to today
        RangeKind.MONTH -> today.minusDays(29) to today
        RangeKind.DAY90 -> today.minusDays(89) to today
        RangeKind.CUSTOM -> {
            val end = minOf(customTo ?: today, today)
            val from = customFrom ?: end
            if (from > end) end to end else from to end
        }
    }

    /** Full card statistics for [kind] over the resolved range. */
    fun compute(
        items: List<TodoItem>,
        kind: RangeKind,
        today: LocalDate,
        nowMillis: Long = System.currentTimeMillis(),
        customFrom: LocalDate? = null,
        customTo: LocalDate? = null
    ): CardStats {
        val (from, to) = resolveRange(kind, items, today, customFrom, customTo)
        val todayEpoch = today.toEpochDay()

        var completed = 0
        // The card's "Incomplete" figure: uncompleted occurrences up to and
        // including today (today's pending todos are incomplete).
        var missed = 0
        // Genuinely PASSED uncompleted days (yesterday and before, plus today
        // for strict-interval todos whose window closed) — the Timeliness
        // numerator and the red heatmap cells.
        var passedUncompleted = 0
        var activeDays = 0
        var perfectDays = 0
        var daysWithDue = 0
        var dataDays = 0
        // Priority-weighted completion (v2): High 3×, Medium 2×, Low 1×.
        var dueWeight = 0f
        var doneWeight = 0f
        // Completions on days that have already passed (closed items that did
        // NOT miss).
        var closedCompleted = 0
        val titles = HashMap<String, TitleAcc>()

        var day = from
        while (!day.isAfter(to)) {
            val epoch = day.toEpochDay()
            var dayCompleted = 0
            var dayDue = 0
            items.forEach { item ->
                if (TodoCodec.isActiveOn(item, day)) {
                    dayDue++
                    dueWeight += item.priority.scoreWeight
                    // Behaviour-aware credit (completed 1.0×, ATTEMPTED 0.5×,
                    // TIME minutes/target) so the card's score matches the
                    // centralized occurrenceScore used everywhere else.
                    doneWeight += item.priority.scoreWeight * TodoStats.creditFraction(item, day)
                    val completedNow = TodoCodec.completedOn(item, day)
                    if (completedNow) {
                        dayCompleted++
                        completed++
                        if (epoch < todayEpoch) closedCompleted++
                    } else if (epoch <= todayEpoch) {
                        missed++
                        if (epoch < todayEpoch || TodoCodec.intervalEnded(item, day, nowMillis)) {
                            passedUncompleted++
                        }
                    }
                    // Blank titles never join the unique-todo lists.
                    if (item.title.isNotBlank()) {
                        val acc = titles.getOrPut(item.title) { TitleAcc() }
                        acc.due++
                        if (completedNow) acc.done++
                        if (epoch > acc.lastActive) acc.lastActive = epoch
                    }
                }
            }
            if (dayDue > 0) daysWithDue++
            if (dayCompleted > 0) activeDays++
            if (dayDue > 0 && dayCompleted == dayDue) perfectDays++
            if (dayDue > 0 || dayCompleted > 0) dataDays++
            day = day.plusDays(1)
        }

        val created = items.count {
            val date = Instant.ofEpochMilli(it.createdAtEpochMillis)
                .atZone(ZoneId.systemDefault()).toLocalDate()
            !date.isBefore(from) && !date.isAfter(to)
        }
        val createdTitles = items.filter {
            val date = Instant.ofEpochMilli(it.createdAtEpochMillis)
                .atZone(ZoneId.systemDefault()).toLocalDate()
            it.title.isNotBlank() && !date.isBefore(from) && !date.isAfter(to)
        }.map { it.title }.toHashSet()

        val currentStreak = streakEndingAt(items, from, to)
        val bestStreak = bestStreakIn(items, from, to)

        val completedTodos = titles.entries
            .filter { it.value.done >= 1 }
            .sortedWith(
                compareByDescending<Map.Entry<String, TitleAcc>> { ratioOf(it.value) }
                    .thenByDescending { it.value.done }
                    .thenBy { it.key }
            )
            .map { UniqueTodo(it.key, it.value.done, it.value.due, it.value.lastActive) }
        val incompleteTodos = titles.entries
            .filter { it.value.done == 0 && it.value.due >= 1 }
            .sortedWith(
                compareByDescending<Map.Entry<String, TitleAcc>> { it.value.lastActive }
                    .thenBy { it.key }
            )
            .map { UniqueTodo(it.key, 0, it.value.due, it.value.lastActive) }

        val bestDay = bestDayIn(items, from, to)
        val mostRepeated = titles.entries
            .filter { it.value.due >= 1 }
            .maxWithOrNull(compareBy<Map.Entry<String, TitleAcc>> { it.value.due }.thenBy { it.value.done })
            ?.let { UniqueTodo(it.key, it.value.done, it.value.due, it.value.lastActive) }
        // Prefer a title with a meaningful sample (≥3 occurrences); fall back
        // to the best ratio available.
        val mostConsistent = completedTodos.firstOrNull { it.due >= 3 }
            ?: completedTodos.firstOrNull()

        // v2 score, identical semantics AND formula to the on-screen Weekly
        // Score: every component must have something to measure, excluded
        // components rescale the rest to 100, nothing earns free points for
        // being empty, and the total is round(scale × Σraw) — never a sum of
        // independently-rounded components (which could drift by ±1). A
        // still-pending today simply contributes 0 — never a bonus.
        val closed = closedCompleted + passedUncompleted
        val hasData = completed + missed > 0
        var breakdown: ScoreBreakdown? = null
        var score: Int? = null
        if (hasData) {
            val completionRaw = 55f * (doneWeight / dueWeight)
            val consistencyRaw = if (daysWithDue > 0) {
                20f * (activeDays.toFloat() / daysWithDue)
            } else 0f
            val streakRaw = 15f * (currentStreak.coerceAtMost(7).toFloat() / 7f)
            val timelinessRaw = if (closed > 0) {
                10f * (1f - passedUncompleted.toFloat() / closed)
            } else 0f
            val includedWeight = 55 + 20 + 15 + (if (closed > 0) 10 else 0)
            val scale = 100f / includedWeight
            breakdown = ScoreBreakdown(
                completion = (scale * completionRaw).roundToInt(),
                completionMax = (scale * 55).roundToInt(),
                consistency = (scale * consistencyRaw).roundToInt(),
                consistencyMax = (scale * 20).roundToInt(),
                streak = (scale * streakRaw).roundToInt(),
                streakMax = (scale * 15).roundToInt(),
                streakDays = currentStreak.coerceAtMost(7),
                timeliness = (scale * timelinessRaw).roundToInt(),
                timelinessMax = (scale * 10).roundToInt(),
                closedItems = closed
            )
            score = (scale * (completionRaw + consistencyRaw + streakRaw + timelinessRaw))
                .roundToInt().coerceIn(0, 100)
        }

        val heatmap = buildHeatmap(items, from, to, todayEpoch, nowMillis)

        return CardStats(
            from = from,
            to = to,
            rangeKind = kind,
            created = created,
            completed = completed,
            missed = missed,
            activeDays = activeDays,
            perfectDays = perfectDays,
            currentStreak = currentStreak,
            bestStreak = bestStreak,
            score = score,
            breakdown = breakdown,
            uniqueCreated = createdTitles.size,
            uniqueCompleted = completedTodos.size,
            uniqueIncomplete = incompleteTodos.size,
            completedTodos = completedTodos,
            incompleteTodos = incompleteTodos,
            bestDay = bestDay,
            mostConsistent = mostConsistent,
            mostRepeated = mostRepeated,
            avgPerDay = completed.toFloat() / (to.toEpochDay() - from.toEpochDay() + 1),
            heatmap = heatmap,
            firstWeek = dataDays < 7
        )
    }

    /**
     * Daily activity for the card's contribution grid — a Monday-aligned window
     * ENDING AT [to] that carries the range's own week span, floored at
     * [HEATMAP_MIN_WEEKS] columns and capped at [HEATMAP_MAX_WEEKS].
     *
     * The floor is what gives every range a GitHub-shaped graph: a 7-day range
     * still fills the same twelve columns as a 90-day one, with the days before
     * [from] left as context (the card draws them as empty, never as "missed").
     * The window is measured in whole weeks from the Monday of [from]'s week, so
     * the floor can only ever ADD context ahead of the range — never drop a
     * scored day. Only the [HEATMAP_MAX_WEEKS] cap can push the window past
     * [from], and those oldest days are then simply absent from the graph rather
     * than mislabelled as outside the range. All days are ≤ today, so there are
     * no "scheduled" cells — only done, partial, missed and no-activity.
     */
    private fun buildHeatmap(
        items: List<TodoItem>,
        from: LocalDate,
        to: LocalDate,
        todayEpoch: Long,
        nowMillis: Long
    ): List<HeatDay> {
        // Monday of the week that contains a date (dayOfWeek.value is 1 = Monday).
        fun weekStart(date: LocalDate): LocalDate =
            date.minusDays((date.dayOfWeek.value - 1).toLong())

        val lastMonday = weekStart(to)
        val spanWeeks = (lastMonday.toEpochDay() - weekStart(from).toEpochDay()) / 7 + 1
        val weeks = spanWeeks
            .coerceIn(HEATMAP_MIN_WEEKS.toLong(), HEATMAP_MAX_WEEKS.toLong())
            .toInt()
        val days = ArrayList<LocalDate>(weeks * 7)
        var day = lastMonday.minusWeeks((weeks - 1).toLong())
        while (!day.isAfter(to)) {
            days += day
            day = day.plusDays(1)
        }
        return days.map { date ->
            val epoch = date.toEpochDay()
            var done = 0
            var anyUncompletedDue = false
            var anyDue = false
            items.forEach { item ->
                if (TodoCodec.isActiveOn(item, date)) {
                    anyDue = true
                    if (TodoCodec.completedOn(item, date)) done++
                    else if (epoch <= todayEpoch) anyUncompletedDue = true
                }
            }
            val heat = when {
                done > 0 && anyUncompletedDue -> Heat.PARTIAL
                done > 0 -> Heat.DONE
                anyDue && (epoch < todayEpoch ||
                    items.any { TodoCodec.intervalEnded(it, date, nowMillis) }) -> Heat.MISSED
                else -> Heat.NONE
            }
            HeatDay(date, heat, done)
        }
    }

    /**
     * Consecutive days with at least one completion, ending at [to] — or the
     * day before it when [to] itself has none yet (an unfinished day must not
     * break the streak, mirroring [TodoStats.streak]).
     */
    private fun streakEndingAt(items: List<TodoItem>, from: LocalDate, to: LocalDate): Int {
        var day = to
        if (items.none { TodoCodec.completedOn(it, day) }) day = day.minusDays(1)
        var count = 0
        while (!day.isBefore(from) && items.any { TodoCodec.completedOn(it, day) }) {
            count++
            day = day.minusDays(1)
        }
        return count
    }

    /** Longest run of consecutive days with at least one completion. */
    private fun bestStreakIn(items: List<TodoItem>, from: LocalDate, to: LocalDate): Int {
        var best = 0
        var run = 0
        var day = from
        while (!day.isAfter(to)) {
            run = if (items.any { TodoCodec.completedOn(it, day) }) run + 1 else 0
            if (run > best) best = run
            day = day.plusDays(1)
        }
        return best
    }

    /** The day with the best completion ratio (ties: most done, then latest). */
    private fun bestDayIn(items: List<TodoItem>, from: LocalDate, to: LocalDate): BestDay? {
        var best: BestDay? = null
        var day = from
        while (!day.isAfter(to)) {
            var done = 0
            var due = 0
            items.forEach { item ->
                if (TodoCodec.isActiveOn(item, day)) {
                    due++
                    if (TodoCodec.completedOn(item, day)) done++
                }
            }
            if (due > 0) {
                val candidate = BestDay(day, done, due)
                val cur = best
                if (cur == null ||
                    candidate.done.toFloat() / candidate.due > cur.done.toFloat() / cur.due ||
                    (candidate.done.toFloat() / candidate.due == cur.done.toFloat() / cur.due &&
                        (candidate.done > cur.done || (candidate.done == cur.done && candidate.date.isAfter(cur.date))))
                ) {
                    best = candidate
                }
            }
            day = day.plusDays(1)
        }
        return best
    }

    private fun ratioOf(acc: TitleAcc): Float = if (acc.due > 0) acc.done.toFloat() / acc.due else 0f
}
