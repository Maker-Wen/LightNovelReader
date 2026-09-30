package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import indi.dmzz_yyhyy.lightnovelreader.R
import indi.dmzz_yyhyy.lightnovelreader.BuildConfig
import kotlin.math.roundToInt

/** Progress values are whole-book fractions in 0..1; the caller supplies navigation-bar insets. */
@Composable
fun ReaderBottomBar(
    progress: Float,
    originProgress: Float?,
    previewTitle: String,
    isPreviewing: Boolean,
    enabled: Boolean,
    menuInteractive: Boolean,
    onBegin: () -> ReaderSeekStart,
    onPreview: (Float) -> Unit,
    onCommit: () -> Unit,
    onCancel: () -> Unit,
    onReturn: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onDirectory: () -> Unit,
    onSettings: () -> Unit,
    canPrevious: Boolean,
    canNext: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < .5f
    val thumbColor = if (dark) lerp(colors.inverseSurface, colors.surface, .1f) else colors.surfaceBright
    val originColor = (if (dark) lerp(colors.secondaryContainer, colors.secondary, .6f) else colors.secondary)
        .copy(alpha = .45f)
    var gestureOriginProgress by remember { mutableStateOf<Float?>(null) }
    val value = progress.fraction()
    val origin = originProgress?.takeIf { it.isFinite() }?.fraction()
    val displayedOrigin = origin ?: gestureOriginProgress
    val thumbScale = remember { Animatable(1f) }
    var snapFeedback by remember { mutableIntStateOf(0) }
    val haptic = LocalHapticFeedback.current
    fun performOriginHaptic() {
        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }
    LaunchedEffect(snapFeedback) {
        if (snapFeedback > 0) {
            thumbScale.animateTo(.92f, tween(70))
            thumbScale.animateTo(1f, spring(dampingRatio = .65f, stiffness = 700f))
        }
    }
    val latestValue by rememberUpdatedState(value)
    val begin by rememberUpdatedState(onBegin)
    val preview by rememberUpdatedState(onPreview)
    val commit by rememberUpdatedState(onCommit)
    val cancel by rememberUpdatedState(onCancel)
    val returnToOrigin by rememberUpdatedState(onReturn)
    val progressLabel = stringResource(R.string.reader_book_progress)
    val returnLabel = stringResource(R.string.reader_return_position)
    val percentage = String.format(LocalConfiguration.current.locales[0], "%.1f%%", value * 100)
    val density = LocalDensity.current
    val popupPosition = remember(density) {
        val margin = with(density) { 14.dp.roundToPx() }
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize
            ) = IntOffset(
                (anchorBounds.center.x - popupContentSize.width / 2)
                    .coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)),
                (anchorBounds.top - popupContentSize.height - margin).coerceAtLeast(margin)
            )
        }
    }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val narrow = maxWidth < 350.dp
        val outerPadding = if (narrow) 8.dp else 14.dp
        // Keep the popup bounds stable while dragging across titles of different lengths.
        val popupWidth = (maxWidth - outerPadding * 2 - 12.dp).coerceIn(0.dp, 256.dp)
        Box(Modifier.fillMaxWidth().padding(horizontal = outerPadding).padding(bottom = 7.dp)
            .then(if (BuildConfig.BENCHMARK) Modifier
                .semantics { testTagsAsResourceId = true }
                .testTag("reader-progress-popup-anchor") else Modifier)) {
            Surface(
                shape = RoundedCornerShape(27.dp),
                color = colors.surfaceContainer,
                shadowElevation = 1.dp
            ) {
                Column(
                    Modifier.padding(
                        start = if (narrow) 8.dp else 14.dp,
                        end = if (narrow) 8.dp else 14.dp,
                        top = 8.dp,
                        bottom = 12.dp
                    )
                ) {
                    BoxWithConstraints(
                        Modifier.fillMaxWidth().height(52.dp)
                            .alpha(if (enabled) 1f else .38f)
                            .then(if (BuildConfig.BENCHMARK) Modifier.drawWithContent {
                                drawContent()
                                ReaderBenchmarkProbe.onMarkerFrame?.let { observer ->
                                    val center = displayedOrigin?.let {
                                        val markerTravel = (size.width.toDp() - 48.dp).coerceAtLeast(0.dp)
                                        val markerX = 24.dp + markerTravel * it
                                        (markerX - 24.dp).roundToPx() + 24.dp.toPx()
                                    }
                                    observer(ReaderMarkerFrame(value, displayedOrigin, origin, gestureOriginProgress,
                                        isPreviewing, enabled, size.width, center))
                                }
                            } else Modifier)
                            .pointerInput(enabled) {
                                if (!enabled) return@pointerInput
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    down.consume()
                                    // Keep the complete 48 dp return target inside the progress row at both ends.
                                    val inset = 24.dp.toPx()
                                    val travel = (size.width - 2 * inset).coerceAtLeast(1f)
                                    val start = begin()
                                    val gesture = ReaderSeekGesture(
                                        startProgress = latestValue,
                                        originProgress = start.formalOriginProgress,
                                        temporaryOriginProgress = start.temporaryOriginProgress,
                                        downX = down.position.x.toDp().value,
                                        downY = (down.position.y - size.height / 2f).toDp().value,
                                        trackStart = inset.toDp().value,
                                        trackLength = travel.toDp().value,
                                        touchSlop = viewConfiguration.touchSlop.toDp().value
                                    )
                                    gestureOriginProgress = gesture.origin
                                    var finished = false
                                    fun update(position: Offset) {
                                        if (gesture.update(position.x.toDp().value,
                                                (position.y - size.height / 2f).toDp().value)) {
                                            snapFeedback++
                                            performOriginHaptic()
                                        }
                                        gesture.preview?.let { target ->
                                            gestureOriginProgress = gesture.origin
                                            preview(target)
                                        }
                                    }
                                    try {
                                        update(down.position)
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull { it.id == down.id }
                                                ?: break
                                            if (change.isConsumed || event.changes.any { it.id != down.id && it.pressed }) break
                                            // Include the release coordinate before deciding whether to return.
                                            update(change.position)
                                            change.consume()
                                            if (!change.pressed) {
                                                finished = true
                                                when (gesture.action) {
                                                    ReaderSeekAction.Commit -> commit()
                                                    ReaderSeekAction.Cancel -> cancel()
                                                    ReaderSeekAction.Return -> {
                                                        // Snapping already gave feedback, including a snap on UP.
                                                        if (!gesture.snapped) performOriginHaptic()
                                                        returnToOrigin()
                                                    }
                                                }
                                                break
                                            }
                                        }
                                    } finally {
                                        gestureOriginProgress = null
                                        if (!finished) cancel()
                                    }
                                }
                            }
                    ) {
                        val travel = (maxWidth - 48.dp).coerceAtLeast(0.dp)
                        val thumbX = 24.dp + travel * value
                        Canvas(
                            Modifier.fillMaxSize()
                                .semantics {
                                    contentDescription = progressLabel
                                    stateDescription = "$previewTitle, $percentage"
                                    progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f)
                                    if (!enabled) disabled()
                                    setProgress { target ->
                                        if (!enabled || !target.isFinite()) false
                                        else { begin(); preview(target.fraction()); commit(); true }
                                    }
                                }
                                .onKeyEvent { event ->
                                    if (!enabled || event.type != KeyEventType.KeyDown) false
                                    else {
                                        val target = when (event.key) {
                                            Key.DirectionLeft, Key.DirectionDown -> value - .01f
                                            Key.DirectionRight, Key.DirectionUp -> value + .01f
                                            Key.MoveHome -> 0f
                                            Key.MoveEnd -> 1f
                                            else -> return@onKeyEvent false
                                        }
                                        begin(); preview(target.fraction()); commit(); true
                                    }
                                }
                                .focusable(enabled)
                        ) {
                            val inset = 24.dp.toPx()
                            val width = (size.width - 2 * inset).coerceAtLeast(0f)
                            val trackHeight = 16.dp.toPx()
                            // Align the rounded cap centers with the slider's endpoint centers.
                            val top = Offset(inset - trackHeight / 2, (size.height - trackHeight) / 2)
                            drawRoundRect(colors.surfaceContainerHighest, top,
                                Size(width + trackHeight, trackHeight), CornerRadius(trackHeight / 2))
                            if (value > 0f) drawRoundRect(colors.primaryContainer, top,
                                Size(width * value + trackHeight, trackHeight), CornerRadius(trackHeight / 2))
                        }
                        if (displayedOrigin != null) {
                            val originX = 24.dp + travel * displayedOrigin
                            Box(
                                Modifier.absoluteOffset { IntOffset((originX - 24.dp).roundToPx(), 2.dp.roundToPx()) }
                                    .size(48.dp)
                                    .semantics {
                                        if (origin != null) {
                                            role = Role.Button
                                            contentDescription = returnLabel
                                            if (!enabled) disabled()
                                            onClick {
                                                if (!enabled) false else {
                                                    if (begin().formalOriginProgress != null) {
                                                        performOriginHaptic()
                                                        returnToOrigin()
                                                        true
                                                    }
                                                    else { cancel(); false }
                                                }
                                            }
                                        }
                                    }
                                    .onKeyEvent { event ->
                                        if (origin != null && enabled && event.type == KeyEventType.KeyUp &&
                                            event.key in listOf(Key.Enter, Key.NumPadEnter, Key.Spacebar)) {
                                            if (begin().formalOriginProgress != null) {
                                                performOriginHaptic()
                                                returnToOrigin()
                                                true
                                            }
                                            else { cancel(); false }
                                        } else false
                                    }
                                    .focusable(enabled && origin != null),
                                contentAlignment = Alignment.Center
                            ) {
                                Canvas(Modifier.size(16.dp)) {
                                    drawCircle(originColor)
                                }
                            }
                        }
                        Box(
                            Modifier.absoluteOffset { IntOffset((thumbX - 12.dp).roundToPx(), 14.dp.roundToPx()) }
                                .size(24.dp).scale(thumbScale.value)
                                .shadow(2.dp, CircleShape).background(thumbColor, CircleShape)
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(if (narrow) 2.dp else 4.dp)
                    ) {
                        ReaderTool(R.drawable.arrow_back_24px, stringResource(R.string.previous_chapter),
                            menuInteractive && canPrevious, onPrevious, Modifier.weight(1f).fillMaxHeight())
                        ReaderTool(R.drawable.outline_bookmark_24px, stringResource(R.string.dialog_snap_bookmarks),
                            false, {}, Modifier.weight(1f).fillMaxHeight())
                        ReaderTool(R.drawable.menu_24px, stringResource(R.string.reader_directory),
                            menuInteractive, onDirectory, Modifier.weight(1f).fillMaxHeight())
                        ReaderTool(R.drawable.outline_settings_24px, stringResource(R.string.settings),
                            menuInteractive, onSettings, Modifier.weight(1f).fillMaxHeight())
                        ReaderTool(R.drawable.arrow_forward_24px, stringResource(R.string.next_chapter),
                            menuInteractive && canNext, onNext, Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
            if (enabled && isPreviewing) {
                Popup(popupPositionProvider = popupPosition,
                    properties = PopupProperties(focusable = false, dismissOnBackPress = false,
                        dismissOnClickOutside = false)) {
                    val titleStyle = LocalTextStyle.current.copy(
                        fontSize = 13.sp, lineHeight = 20.sp, textAlign = TextAlign.Center
                    )
                    val titleMeasurer = rememberTextMeasurer()
                    // A constant two-line sample also respects Android's nonlinear font scaling.
                    val titleHeight = with(density) {
                        titleMeasurer.measure("国\n国", style = titleStyle, maxLines = 2).size.height.toDp()
                    }
                    Surface(modifier = if (BuildConfig.BENCHMARK) Modifier
                        .semantics { testTagsAsResourceId = true }
                        .testTag("reader-progress-popup") else Modifier,
                        shape = RoundedCornerShape(24.dp), color = colors.surfaceContainerHigh,
                        contentColor = colors.onSurface, shadowElevation = 1.dp) {
                        Column(
                            Modifier.width(popupWidth)
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Box(
                                Modifier.fillMaxWidth().height(titleHeight),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(previewTitle, modifier = Modifier.fillMaxWidth(), style = titleStyle,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Text(percentage, modifier = Modifier.fillMaxWidth(), fontSize = 11.sp, lineHeight = 15.sp,
                                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                                textAlign = TextAlign.Center,
                                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                                color = colors.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderTool(
    @DrawableRes icon: Int,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier
) {
    val foreground = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier.widthIn(min = 48.dp).heightIn(min = 60.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp).alpha(if (enabled) 1f else .33f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically)
    ) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp), tint = foreground)
        Text(label, color = foreground, fontSize = 11.sp, lineHeight = 14.sp, textAlign = TextAlign.Center)
    }
}

private fun Float.fraction(): Float = if (isFinite()) coerceIn(0f, 1f) else 0f
