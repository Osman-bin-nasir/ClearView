package com.muddassir.clearview.todo.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.muddassir.clearview.R
import com.muddassir.clearview.todo.data.ProgressCardStats
import java.time.Month
import com.muddassir.clearview.todo.data.TodoStore
import com.muddassir.clearview.todo.model.TodoItem
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// A rendered card: the composable preview surface + the raw bitmap for export.
// The palette, resolved strings ([CardTexts]) and the layout engine itself live
// in ProgressCardLayout.kt (pure, unit-tested).
private data class RenderedCard(val image: ImageBitmap, val bitmap: Bitmap)

/**
 * The card's display face: Barlow Condensed (SIL Open Font License — see
 * licenses/OFL-BarlowCondensed.txt), Black for the hero number and Bold for
 * every other piece of type. Shipping the file is what stops a 132px score from
 * rendering as generic system Bold — and the layout engine never resolves a
 * resource itself, so it stays pure JVM and unit-testable.
 */
private val CardDisplayFont = FontFamily(
    Font(R.font.barlow_condensed_black, FontWeight.Black),
    Font(R.font.barlow_condensed_bold, FontWeight.Bold)
)

/**
 * The shareable Progress Card generator — a full-screen dialog in two steps:
 *
 *  1. Name + time range → Generate Card.
 *  2. Live preview (1080×1920 Story) + Save to gallery, Share sheet and
 *     Regenerate.
 *
 * The card itself is drawn VECTOR-STRAIGHT INTO A BITMAP at full export
 * resolution ([CanvasDrawScope] over an off-screen [android.graphics.Bitmap]),
 * so the exported image is razor sharp at any size — no screenshot scaling.
 */
@Composable
fun ProgressCardDialog(
    items: List<TodoItem>,
    today: LocalDate,
    nowMillis: Long,
    store: TodoStore,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            ProgressCardFlow(items, today, nowMillis, store, onDismiss)
        }
    }
}

@Composable
private fun ProgressCardFlow(
    items: List<TodoItem>,
    today: LocalDate,
    nowMillis: Long,
    store: TodoStore,
    onDismiss: () -> Unit
) {
    var step by remember { mutableStateOf(0) } // 0 = input · 1 = card
    var name by remember { mutableStateOf(store.getProgressCardName()) }
    var range by remember { mutableStateOf(ProgressCardStats.RangeKind.WEEK) }
    var customFrom by remember { mutableStateOf<LocalDate?>(null) }
    var customTo by remember { mutableStateOf<LocalDate?>(null) }
    var card by remember { mutableStateOf<ProgressCardStats.CardStats?>(null) }
    var generating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                if (step == 1) {
                    step = 0
                    card = null
                } else {
                    onDismiss()
                }
            }) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.todo_back)
                )
            }
            Text(
                text = stringResource(R.string.progress_card_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        when (step) {
            0 -> NameInputStep(
                name = name,
                onNameChange = { name = it },
                range = range,
                onRangeChange = { range = it },
                customFrom = customFrom,
                customTo = customTo,
                onCustomFromChange = { customFrom = it },
                onCustomToChange = { customTo = it },
                generating = generating,
                onGenerate = {
                    // Range math (especially All Time, which walks every day
                    // since the earliest todo) must not run on the UI thread.
                    generating = true
                    scope.launch {
                        val computed = withContext(Dispatchers.Default) {
                            ProgressCardStats.compute(
                                items, range, today, nowMillis, customFrom, customTo
                            )
                        }
                        card = computed
                        if (name.isNotBlank()) store.setProgressCardName(name.trim())
                        generating = false
                        step = 1
                    }
                }
            )
            else -> card?.let { stats ->
                CardPreviewStep(
                    stats = stats,
                    name = name,
                    onRegenerate = {
                        step = 0
                        card = null
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NameInputStep(
    name: String,
    onNameChange: (String) -> Unit,
    range: ProgressCardStats.RangeKind,
    onRangeChange: (ProgressCardStats.RangeKind) -> Unit,
    customFrom: LocalDate?,
    customTo: LocalDate?,
    onCustomFromChange: (LocalDate?) -> Unit,
    onCustomToChange: (LocalDate?) -> Unit,
    generating: Boolean,
    onGenerate: () -> Unit
) {
    var showFromPicker by remember { mutableStateOf(false) }
    var showToPicker by remember { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    val dateFmt = DateTimeFormatter.ofPattern("MMM d, yyyy", locale)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp)
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.progress_card_name_label)) },
            placeholder = { Text(stringResource(R.string.progress_card_name_placeholder)) },
            singleLine = true
        )
        Spacer(Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.progress_card_range_label),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                ProgressCardStats.RangeKind.TODAY to R.string.progress_card_range_today,
                ProgressCardStats.RangeKind.WEEK to R.string.progress_card_range_week,
                ProgressCardStats.RangeKind.MONTH to R.string.progress_card_range_month,
                ProgressCardStats.RangeKind.DAY90 to R.string.progress_card_range_90,
                ProgressCardStats.RangeKind.CUSTOM to R.string.progress_card_range_custom
            ).forEach { (option, label) ->
                FilterChip(
                    selected = range == option,
                    onClick = { onRangeChange(option) },
                    label = { Text(stringResource(label)) }
                )
            }
        }

        if (range == ProgressCardStats.RangeKind.CUSTOM) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { showFromPicker = true }) {
                    Text(
                        text = stringResource(
                            R.string.progress_card_range_from,
                            customFrom?.format(dateFmt) ?: "—"
                        )
                    )
                }
                OutlinedButton(onClick = { showToPicker = true }) {
                    Text(
                        text = stringResource(
                            R.string.progress_card_range_to,
                            customTo?.format(dateFmt) ?: "—"
                        )
                    )
                }
            }
        }

        Spacer(Modifier.height(32.dp))
        val customValid = range != ProgressCardStats.RangeKind.CUSTOM ||
            (customFrom != null && customTo != null && !customTo!!.isBefore(customFrom))
        Button(
            onClick = onGenerate,
            enabled = name.isNotBlank() && customValid && !generating,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Text(
                if (generating) stringResource(R.string.progress_card_generating)
                else stringResource(R.string.progress_card_generate)
            )
        }
    }

    if (showFromPicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = customFrom?.atStartOfDay(ZoneId.systemDefault())
                ?.toInstant()?.toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showFromPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        onCustomFromChange(
                            Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
                        )
                    }
                    showFromPicker = false
                }) { Text(stringResource(R.string.todo_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showFromPicker = false }) {
                    Text(stringResource(R.string.todo_cancel))
                }
            }
        ) {
            DatePicker(state = state)
        }
    }
    if (showToPicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = customTo?.atStartOfDay(ZoneId.systemDefault())
                ?.toInstant()?.toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showToPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        onCustomToChange(
                            Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
                        )
                    }
                    showToPicker = false
                }) { Text(stringResource(R.string.todo_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showToPicker = false }) {
                    Text(stringResource(R.string.todo_cancel))
                }
            }
        ) {
            DatePicker(state = state)
        }
    }
}

@Composable
private fun CardPreviewStep(
    stats: ProgressCardStats.CardStats,
    name: String,
    onRegenerate: () -> Unit
) {
    val context = LocalContext.current
    // The score is always the range's own v2 score — the card is a
    // self-contained analytics dashboard for the selected period.
    val rendered = rememberProgressCardImage(stats, name, context)
    val cardSavedMsg = stringResource(R.string.progress_card_saved)
    val cardSaveFailedMsg = stringResource(R.string.progress_card_save_failed)

    // Pre-Android-10 gallery save goes through the system save dialog (SAF);
    // Android 10+ writes straight into MediaStore Pictures/ClearView.
    val createDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri ->
        if (uri != null) {
            val ok = writePngToUri(context, uri, rendered.bitmap)
            Toast.makeText(
                context,
                if (ok) cardSavedMsg else cardSaveFailedMsg,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        // One size only: the Story canvas is what the card is designed for, so
        // there is no size switch to get wrong.
        Text(
            text = stringResource(R.string.progress_card_story),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Image(
            bitmap = rendered.image,
            contentDescription = stringResource(R.string.progress_card_title),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(CARD_WIDTH / CARD_HEIGHT)
                .clip(RoundedCornerShape(24.dp))
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val ok = saveCardToMediaStore(context, rendered.bitmap)
                        Toast.makeText(
                            context,
                            if (ok) cardSavedMsg else cardSaveFailedMsg,
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        createDoc.launch("ProgressCard.png")
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.progress_card_save))
            }
            OutlinedButton(
                onClick = { shareCard(context, rendered.bitmap) },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.progress_card_share))
            }
        }
        Spacer(Modifier.height(4.dp))
        TextButton(
            onClick = onRegenerate,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text(stringResource(R.string.progress_card_regenerate))
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ── Off-screen rendering ────────────────────────────────────────────────

/**
 * Renders [stats] into an off-screen [Bitmap] at full export resolution
 * (1080×1920) via [CanvasDrawScope], returning both the composable preview
 * surface and the raw bitmap for save/share. Re-renders whenever the stats or
 * the name change.
 */
@Composable
private fun rememberProgressCardImage(
    stats: ProgressCardStats.CardStats,
    name: String,
    context: Context
): RenderedCard {
    val textMeasurer = rememberTextMeasurer(cacheSize = 64)
    val density = Density(1f) // draw in raw pixels: 1 sp/dp == 1 px
    val appIcon = remember { loadAppIcon(context) }
    val grain = remember { createGrainBitmap() }
    val width = CARD_WIDTH.toInt()
    val height = CARD_HEIGHT.toInt()
    // Draw SYNCHRONOUSLY while creating the bitmap so the preview never shows
    // a blank frame: CanvasDrawScope.draw is synchronous, and this only re-runs
    // when the stats/name actually change (a button tap), not on every
    // recomposition.
    return remember(stats, name) {
        val texts = buildTexts(stats, name, context)
        // Every chromatic value on the card follows from the range's tier.
        val palette = paletteFor(texts.tier)
        // 1. Lay out every element as a measured block (flex-column flow).
        val blocks = layoutProgressCard(
            texts, appIcon != null, CardDisplayFont
        ) { text, style, maxWidth ->
            // ceil: never constrain below the layout width, or a borderline
            // single-line text would re-wrap during the draw pass.
            val result = textMeasurer.measure(
                AnnotatedString(text), style,
                constraints = Constraints(maxWidth = ceil(maxWidth).toInt().coerceAtLeast(1))
            )
            Size(result.size.width.toFloat(), result.size.height.toFloat())
        }
        // 2. QA gate: nothing may sit outside the canvas or overlap. The unit
        //    tests assert this is empty for worst-case inputs; this log catches
        //    any regression the tests didn't predict.
        verifyCardLayout(blocks, width.toFloat(), height.toFloat())
            .takeIf { it.isNotEmpty() }
            ?.let { Log.w("ProgressCard", "Layout QA failed: ${it.joinToString(" | ")}") }
        // 3. Draw the blocks onto the export bitmap.
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val image = bitmap.asImageBitmap()
        CanvasDrawScope().draw(
            density,
            LayoutDirection.Ltr,
            androidx.compose.ui.graphics.Canvas(image),
            Size(width.toFloat(), height.toFloat())
        ) {
            drawProgressCardBackground(grain, palette)
            drawCardBlocks(blocks, appIcon, textMeasurer, palette)
        }
        RenderedCard(image, bitmap)
    }
}

private fun buildTexts(
    stats: ProgressCardStats.CardStats,
    name: String,
    context: Context
): CardTexts {
    val appName = context.getString(R.string.app_name)
    val days = " " + context.getString(R.string.progress_card_days)
    val scoreTitle = context.getString(
        when (stats.rangeKind) {
            ProgressCardStats.RangeKind.TODAY -> R.string.progress_card_score_today
            ProgressCardStats.RangeKind.WEEK -> R.string.progress_card_score_week
            ProgressCardStats.RangeKind.MONTH -> R.string.progress_card_score_month
            ProgressCardStats.RangeKind.DAY90 -> R.string.progress_card_score_90
            ProgressCardStats.RangeKind.CUSTOM -> R.string.progress_card_score_period
        }
    )
    // The contribution grid always spans at least twelve Monday-aligned weeks
    // ending at the range's last day, so every range reads as a real GitHub
    // graph. Days before the range starts are drawn hollow (context, not data),
    // and the year is spelled out on every January so a year-long grid can never
    // show two ambiguous "Aug" labels.
    val shortMonth = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)
    val monthYear = DateTimeFormatter.ofPattern("MMM yy", Locale.ENGLISH)
    val grid = buildCardGrid(stats.heatmap, stats.from) { date ->
        if (date.month == Month.JANUARY) monthYear.format(date) else shortMonth.format(date)
    }
    // The six numbers the table carries. Every slot has to earn its place, so
    // they are deliberately DISJOINT: each says something the other five do not,
    // and none of them restates the hero score above.
    val tableStats = listOf(
        CardStat(
            label(context, R.string.progress_card_completed),
            stats.completed.toString()
        ),
        CardStat(
            label(context, R.string.progress_card_active),
            stats.activeDays.toString()
        ),
        CardStat(
            label(context, R.string.progress_card_current_streak),
            // No comparison history yet → an honest dash, never a fake "0 days".
            if (stats.firstWeek) "\u2014" else stats.currentStreak.toString() + days
        ),
        CardStat(
            label(context, R.string.progress_card_best_streak),
            stats.bestStreak.toString()
        ),
        CardStat(
            label(context, R.string.progress_card_perfect_days),
            stats.perfectDays.toString()
        ),
        CardStat(
            label(context, R.string.progress_card_incomplete),
            stats.missed.toString()
        )
    )
    // The range's grade — the one line that turns the number above it into a
    // verdict. Null (no data) leaves the card on its neutral teal accent.
    val tier = ProgressCardStats.tierFor(stats.score)

    return CardTexts(
        nameProgress = context.getString(R.string.progress_card_your_progress, name.trim().take(24)),
        dateRange = formatDateRange(stats.from, stats.to),
        scoreTitle = scoreTitle,
        scoreLine = stats.score?.let {
            context.getString(R.string.progress_card_score_value, it)
        },
        percentLine = stats.score?.let {
            context.getString(R.string.progress_card_completion, stats.percent)
        },
        tierLabel = tier?.let { context.getString(tierLabelRes(it)).uppercase() },
        tier = tier,
        emptyRange = context.getString(R.string.progress_card_empty_range),
        stats = tableStats,
        summary = summarize(stats, context),
        activityTitle = context.getString(R.string.progress_card_activity),
        grid = grid,
        legendLess = context.getString(R.string.progress_card_legend_less),
        legendMore = context.getString(R.string.progress_card_legend_more),
        // The best day is real, specific evidence of progress; on a first week
        // there is no comparison history yet, so the honest edge-state line wins.
        note = when {
            stats.firstWeek -> context.getString(R.string.progress_card_first_week)
            stats.bestDay?.done?.let { it > 0 } == true -> context.getString(
                R.string.progress_card_best_day,
                DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH).format(stats.bestDay.date),
                stats.bestDay.done
            )
            else -> null
        },
        madeWith = context.getString(R.string.progress_card_made_with, appName).uppercase(),
        // The card is signed by the person who earned it, the way a shared
        // poster is credited — not by the brand alone.
        signature = name.trim().uppercase()
    )
}

/**
 * A table label, uppercased for the card's small-caps style. Upper-cased with
 * [Locale.ROOT] rather than the device locale so the card's type never shifts
 * shape with the reader's language.
 */
private fun label(context: Context, resId: Int): String =
    context.getString(resId).uppercase(Locale.ROOT)

/** The card-facing name of an achievement tier. */
private fun tierLabelRes(tier: ProgressCardStats.Tier): Int = when (tier) {
    ProgressCardStats.Tier.BRONZE -> R.string.progress_card_tier_bronze
    ProgressCardStats.Tier.SILVER -> R.string.progress_card_tier_silver
    ProgressCardStats.Tier.GOLD -> R.string.progress_card_tier_gold
    ProgressCardStats.Tier.PLATINUM -> R.string.progress_card_tier_platinum
    ProgressCardStats.Tier.DIAMOND -> R.string.progress_card_tier_diamond
}

/**
 * One short, honest verdict for the range, chosen from the real completion rate
 * (never invented, and null while the range has no data at all). The thresholds
 * describe the range the user actually selected, so the sentence can never
 * contradict the number above it.
 */
private fun summarize(stats: ProgressCardStats.CardStats, context: Context): String? {
    if (stats.score == null) return null
    return context.getString(
        when {
            stats.percent >= 80 -> R.string.progress_card_summary_high
            stats.percent >= 60 -> R.string.progress_card_summary_good
            stats.percent >= 40 -> R.string.progress_card_summary_mixed
            else -> R.string.progress_card_summary_low
        }
    )
}

/** "Aug 3 – Aug 10, 2026" (year repeated only when the range crosses years). */
private fun formatDateRange(from: LocalDate, to: LocalDate): String {
    val short = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)
    val full = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)
    return when {
        from == to -> full.format(from)
        from.year == to.year -> "${short.format(from)} – ${full.format(to)}"
        else -> "${full.format(from)} – ${full.format(to)}"
    }
}

/** The launcher icon rasterized for the card header (null → teal ✓ mark). */
private fun loadAppIcon(context: Context): ImageBitmap? = runCatching {
    val drawable = context.getDrawable(R.mipmap.ic_launcher) ?: return null
    val size = 192
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    drawable.setBounds(0, 0, size, size)
    drawable.draw(canvas)
    bmp.asImageBitmap()
}.getOrNull()

/** A small tiled noise bitmap giving the flat black a subtle grain texture. */
private fun createGrainBitmap(size: Int = 128): ImageBitmap {
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val rand = Random(0xC0FFEE)
    for (x in 0 until size) {
        for (y in 0 until size) {
            val v = (rand.nextInt(64) - 32 + 128).coerceIn(0, 255)
            bmp.setPixel(x, y, android.graphics.Color.argb(255, v, v, v))
        }
    }
    return bmp.asImageBitmap()
}

// ── The poster drawing (DrawScope over the off-screen bitmap) ──────────

private fun DrawScope.drawProgressCardBackground(grain: ImageBitmap?, palette: CardPalette) {
    val w = size.width
    val h = size.height

    // Flat black + a tier-tinted vertical gradient and a radial glow behind
    // the hero — the "designed, not a screenshot" backdrop. The glow carries
    // the tier's colour (and gets brighter with it), so the card's whole
    // ambience shifts as the user climbs.
    drawRect(
        brush = Brush.verticalGradient(listOf(CardBgTop, CardBgBottom))
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(palette.glow, Color.Transparent),
            center = Offset(w / 2f, h * 0.30f),
            radius = w * 0.60f
        ),
        radius = w * 0.60f,
        center = Offset(w / 2f, h * 0.30f)
    )
    // Subtle grain over the whole card.
    grain?.let { g ->
        val gs = g.width
        var gx = 0
        while (gx < w) {
            var gy = 0
            while (gy < h) {
                drawImage(
                    image = g,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(gs, gs),
                    dstOffset = IntOffset(gx, gy),
                    dstSize = IntSize(gs, gs),
                    alpha = 0.05f
                )
                gy += gs
            }
            gx += gs
        }
    }
}

/**
 * Draws the laid-out [blocks]. Placement comes entirely from the rects
 * produced by [layoutProgressCard] — this pass never re-derives positions, so
 * the QA-verified layout is exactly what gets exported.
 */
private fun DrawScope.drawCardBlocks(
    blocks: List<CardBlock>,
    appIcon: ImageBitmap?,
    tm: TextMeasurer,
    palette: CardPalette
) {
    blocks.forEach { block ->
        when (block) {
            is CardBlock.Text ->
                drawTextBlock(block.text, block.style, block.rect, tm)

            is CardBlock.Line ->
                drawRect(
                    color = CardDivider,
                    topLeft = Offset(block.rect.left, block.rect.top),
                    size = Size(block.rect.width, block.rect.height)
                )

            is CardBlock.Icon -> drawIconBlock(block, appIcon, tm, palette)

            is CardBlock.Panel -> drawRoundRect(
                color = Color.White.copy(alpha = 0.05f),
                topLeft = Offset(block.rect.left, block.rect.top),
                size = Size(block.rect.width, block.rect.height),
                cornerRadius = CornerRadius(28f)
            )

            is CardBlock.Swatch -> drawRoundRect(
                color = palette.heat[block.level.coerceIn(0, palette.heat.lastIndex)],
                topLeft = Offset(block.rect.left, block.rect.top),
                size = Size(block.rect.width, block.rect.height),
                cornerRadius = CornerRadius(block.rect.width * 0.24f)
            )

            is CardBlock.Star -> drawStarBlock(block, palette)

            is CardBlock.Grid -> drawGridBlock(block, tm, palette)
        }
    }
}

/**
 * The tier badge's mark: a five-point star in the tier's accent, drawn as a
 * vector. Vector shapes stay crisp at export resolution and keep the card free
 * of emoji, whose size and colour belong to whichever font the platform picks.
 */
private fun DrawScope.drawStarBlock(block: CardBlock.Star, palette: CardPalette) {
    val r = block.rect
    val cx = (r.left + r.right) / 2f
    val cy = (r.top + r.bottom) / 2f
    val outer = minOf(r.width, r.height) / 2f
    val inner = outer * 0.46f
    val path = Path()
    repeat(10) { i ->
        val radius = if (i % 2 == 0) outer else inner
        // -90° first, so the star stands on a point instead of a flat edge.
        val angle = Math.toRadians(-90.0 + i * 36.0)
        val x = cx + (radius * cos(angle)).toFloat()
        val y = cy + (radius * sin(angle)).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, palette.accent)
}

/**
 * The contribution grid: one rounded square per day, column per week (Monday at
 * the top), shaded by the day's completion intensity — or drawn hollow when the
 * day falls outside the scored range. Month labels are pinned above their own
 * column inside the same block, so nothing can drift from its squares.
 */
private fun DrawScope.drawGridBlock(
    block: CardBlock.Grid,
    tm: TextMeasurer,
    palette: CardPalette
) {
    val radius = CornerRadius(block.cell * 0.24f)
    block.monthLabels.forEach { (column, label) ->
        val style = TextStyle(
            fontSize = block.labelFont.sp,
            fontWeight = FontWeight.SemiBold,
            color = CardTextDim
        )
        val layout = tm.measure(AnnotatedString(label), style)
        drawText(
            layout,
            topLeft = Offset(
                block.rect.left + column * (block.cell + block.gap),
                block.rect.top + (block.labelRow - layout.size.height) / 2f
            )
        )
    }
    val gridTop = block.rect.top + block.labelRow
    block.levels.forEachIndexed { index, level ->
        if (level == GRID_NO_CELL) return@forEachIndexed
        val column = index / block.rows
        val row = index % block.rows
        val left = block.rect.left + column * (block.cell + block.gap)
        val top = gridTop + row * (block.cell + block.gap)
        if (level == GRID_OUTSIDE_RANGE) {
            // A day the graph shows but the range never scored: a hollow
            // square, so the leading context weeks read as "not measured"
            // rather than as days the user did nothing.
            val inset = block.cell * 0.13f
            drawRoundRect(
                color = CardGridGhost,
                topLeft = Offset(left + inset, top + inset),
                size = Size(block.cell - inset * 2f, block.cell - inset * 2f),
                cornerRadius = radius,
                style = Stroke(width = (block.cell * 0.06f).coerceAtLeast(1.5f))
            )
        } else {
            drawRoundRect(
                color = palette.heat[level.coerceIn(0, palette.heat.lastIndex)],
                topLeft = Offset(left, top),
                size = Size(block.cell, block.cell),
                cornerRadius = radius
            )
        }
    }
}

/** Draws a text block at exactly the laid-out rect (wrapped to its width). */
private fun DrawScope.drawTextBlock(text: String, style: TextStyle, rect: CardRect, tm: TextMeasurer) {
    // ceil: keep the draw constraint ≥ the layout width so the text can never
    // re-wrap differently than the QA-verified layout.
    val layout = tm.measure(
        AnnotatedString(text), style,
        constraints = Constraints(maxWidth = ceil(rect.width).toInt().coerceAtLeast(1))
    )
    drawText(layout, topLeft = Offset(rect.left, rect.top))
}

/** The app logo raster (or a teal ✓ tile as fallback) inside its block. */
private fun DrawScope.drawIconBlock(
    block: CardBlock.Icon,
    appIcon: ImageBitmap?,
    tm: TextMeasurer,
    palette: CardPalette
) {
    val corner = CornerRadius(block.rect.width * 0.24f)
    if (block.useAppIcon && appIcon != null) {
        drawRoundRect(
            color = Color.White.copy(alpha = 0.10f),
            topLeft = Offset(block.rect.left, block.rect.top),
            size = Size(block.rect.width, block.rect.height),
            cornerRadius = corner
        )
        drawImage(
            image = appIcon,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(appIcon.width, appIcon.height),
            dstOffset = IntOffset(block.rect.left.toInt(), block.rect.top.toInt()),
            dstSize = IntSize(block.rect.width.toInt(), block.rect.height.toInt())
        )
    } else {
        drawRoundRect(
            color = palette.accent,
            topLeft = Offset(block.rect.left, block.rect.top),
            size = Size(block.rect.width, block.rect.height),
            cornerRadius = corner
        )
        val mark = TextStyle(
            fontSize = (block.rect.width * 0.55f).sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF06241F)
        )
        val layout = tm.measure(AnnotatedString("✓"), mark)
        drawText(
            layout,
            topLeft = Offset(
                block.rect.left + (block.rect.width - layout.size.width) / 2f,
                block.rect.top + (block.rect.height - layout.size.height) / 2f
            )
        )
    }
}

// ── Save / share ────────────────────────────────────────────────────────

/** Android 10+: writes the PNG straight into Pictures/ClearView (no permission needed). */
private fun saveCardToMediaStore(context: Context, bitmap: Bitmap): Boolean {
    val bytes = ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
    }
    return try {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "progress_card.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/ClearView"
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
        } catch (e: Exception) {
            // Don't leave an IS_PENDING=1 ghost row behind on failure.
            runCatching { resolver.delete(uri, null, null) }
            return false
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        true
    } catch (e: Exception) {
        false
    }
}

/** Pre-Android-10: writes the PNG to a SAF-picked location. */
private fun writePngToUri(context: Context, uri: Uri, bitmap: Bitmap): Boolean = try {
    context.contentResolver.openOutputStream(uri)?.use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    } != null
} catch (e: Exception) {
    false
}

/** Native share sheet (Instagram / WhatsApp / …) via a cache file + FileProvider. */
private fun shareCard(context: Context, bitmap: Bitmap) {
    try {
        val file = File(context.cacheDir, "progress_card.png")
        FileOutputStream(file).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val uri = FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", file
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, context.getString(R.string.progress_card_share_via))
        )
    } catch (e: Exception) {
        Toast.makeText(
            context,
            context.getString(R.string.progress_card_share_failed),
            Toast.LENGTH_SHORT
        ).show()
    }
}
