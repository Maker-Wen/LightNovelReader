@file:Suppress("AssignedValueIsNeverRead")

package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.scroll

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.github.michaelbull.result.get
import com.github.michaelbull.result.map
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import indi.dmzz_yyhyy.lightnovelreader.BuildConfig
import indi.dmzz_yyhyy.lightnovelreader.R
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.ReaderBenchmarkProbe
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.SettingState
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ChapterContentError
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ChapterContentUiState
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ReaderPaginationLayout
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet.LocalReaderTextLayout
import io.nightfish.lightnovelreader.api.ui.LocalTextLocaleList
import io.nightfish.lightnovelreader.api.ui.LocalComponentRender
import io.nightfish.lightnovelreader.api.ui.LocalReaderStyle
import io.nightfish.lightnovelreader.api.content.component.data.ParagraphComponentData
import io.nightfish.lightnovelreader.api.content.component.data.ImageComponentData
import indi.dmzz_yyhyy.lightnovelreader.ui.components.Loading
import indi.dmzz_yyhyy.lightnovelreader.ui.home.settings.data.MenuOptions
import indi.dmzz_yyhyy.lightnovelreader.utils.LocalSnackbarHost
import indi.dmzz_yyhyy.lightnovelreader.utils.readerTextColor
import indi.dmzz_yyhyy.lightnovelreader.utils.rememberReaderBackgroundPainter
import indi.dmzz_yyhyy.lightnovelreader.utils.rememberReaderFontFamily
import indi.dmzz_yyhyy.lightnovelreader.utils.showSnackbar
import kotlinx.coroutines.launch

@Composable
fun ScrollContentComponent(
    modifier: Modifier,
    uiState: ScrollContentUiState,
    settingState: SettingState,
    paddingValues: PaddingValues,
    changeIsImmersive: () -> Unit,
    onClickPrevChapter: () -> Unit,
    onClickNextChapter: () -> Unit
) {
    val snackbarHostState = LocalSnackbarHost.current
    val toggleMenu by rememberUpdatedState(changeIsImmersive)
    val density = LocalDensity.current
    val listState = uiState.lazyListState
    var lazyColumnSize by remember { mutableStateOf(IntSize.Zero) }
    val loopBackgroundEnabled = settingState.enableBackgroundImage &&
        settingState.backgroundImageDisplayMode == MenuOptions.ReaderBgImageDisplayModeOptions.Loop
    var backgroundViewportHeightPx by remember { mutableIntStateOf(0) }
    var backgroundPhasePx by remember { mutableFloatStateOf(0f) }
    val backgroundScrollConnection = remember(loopBackgroundEnabled) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                val height = backgroundViewportHeightPx
                if (loopBackgroundEnabled && height > 0 && consumed.y != 0f) {
                    backgroundPhasePx = positiveModulo(
                        backgroundPhasePx + consumed.y,
                        height.toFloat(),
                    )
                }
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(loopBackgroundEnabled) {
        backgroundPhasePx = 0f
    }

    val direction = LocalLayoutDirection.current
    val readerStyle = LocalReaderStyle.current
    val locales = LocalTextLocaleList.current
    val paginationLayout = ReaderPaginationLayout(
        width = lazyColumnSize.width,
        height = lazyColumnSize.height,
        fontSize = settingState.fontSize,
        fontLineHeight = settingState.lineHeight,
        fontWeight = settingState.fontWeigh,
        fontFamilyUri = settingState.fontUri.toString(),
        density = density.density,
        fontScale = density.fontScale,
        isRtl = direction == LayoutDirection.Rtl,
        mode = "scroll",
        styleSignature = "$readerStyle|${MaterialTheme.typography.bodyMedium}",
        localeTags = (0 until locales.size).joinToString(",") { locales[it].toLanguageTag() }
    )
    LaunchedEffect(paginationLayout) {
        uiState.setPaginationLayout(paginationLayout)
    }

    val reachedTopMsg = stringResource(R.string.reader_reached_top)
    val prevChapterLabel = stringResource(R.string.previous_chapter)
    val reachedBottomMsg = stringResource(R.string.reader_reached_bottom)
    val nextChapterLabel = stringResource(R.string.next_chapter)
    val confirmLabel = stringResource(R.string.confirm)
    val reachedStartMsg = stringResource(R.string.reader_reached_start)
    val reachedEndMsg = stringResource(R.string.reader_reached_end)

    LaunchedEffect(listState) {
        var atTop = false
        var atBottom = false

        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (!scrolling && !uiState.isPositioning) {
                    val layoutInfo = listState.layoutInfo
                    val totalCount = layoutInfo.totalItemsCount
                    val firstIndex = listState.firstVisibleItemIndex
                    val firstOffset = listState.firstVisibleItemScrollOffset
                    val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()

                    val isAtTop = firstIndex == 0 && firstOffset == 0
                    val isAtBottom = lastVisible != null &&
                            lastVisible.index == totalCount - 1 &&
                            (lastVisible.offset + lastVisible.size) <= layoutInfo.viewportEndOffset

                    when {
                        isAtTop -> {
                            if (atTop) {
                                if (uiState.readingChapterContent?.map { it.hasPrevChapter() }?.get() == true)
                                    launch {
                                        showSnackbar(
                                            coroutineScope = this,
                                            hostState = snackbarHostState,
                                            message = reachedTopMsg,
                                            actionLabel = prevChapterLabel
                                        ) { if (it == SnackbarResult.ActionPerformed) onClickPrevChapter() }
                                    }
                                else
                                    launch {
                                        showSnackbar(
                                            coroutineScope = this,
                                            hostState = snackbarHostState,
                                            message = reachedStartMsg,
                                            actionLabel = confirmLabel
                                        )
                                    }
                            }
                            atTop = true; atBottom = false
                        }

                        isAtBottom -> {
                            if (atBottom) {
                                if (uiState.readingChapterContent?.map { it.hasNextChapter() }?.get() == true)
                                    launch {
                                        showSnackbar(
                                            coroutineScope = this,
                                            hostState = snackbarHostState,
                                            message = reachedBottomMsg,
                                            actionLabel = nextChapterLabel
                                        ) { if (it == SnackbarResult.ActionPerformed) onClickNextChapter() }
                                    }
                                else
                                    launch {
                                        showSnackbar(
                                            coroutineScope = this,
                                            hostState = snackbarHostState,
                                            message = reachedEndMsg,
                                            actionLabel = confirmLabel
                                        )
                                    }
                            }
                            atBottom = true; atTop = false
                        }

                        else -> {
                            snackbarHostState.currentSnackbarData?.dismiss()
                            atTop = false; atBottom = false
                        }
                    }
                }
            }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        uiState.writeProgressRightNow()
    }
    val loopBackgroundPainter = if (loopBackgroundEnabled) {
        rememberReaderBackgroundPainter(settingState)
    } else null

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .onGloballyPositioned { backgroundViewportHeightPx = it.size.height }
    ) {
        if (loopBackgroundPainter != null && backgroundViewportHeightPx > 0) {
            val backgroundHeight = with(density) { backgroundViewportHeightPx.toDp() }
            Image(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(backgroundHeight)
                    .graphicsLayer {
                        translationY = backgroundPhasePx - backgroundViewportHeightPx
                    },
                painter = loopBackgroundPainter,
                contentDescription = null,
                contentScale = ContentScale.Crop,
            )
            Image(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(backgroundHeight)
                    .graphicsLayer { translationY = backgroundPhasePx },
                painter = loopBackgroundPainter,
                contentDescription = null,
                contentScale = ContentScale.Crop,
            )
        }

        Box(
            modifier.fillMaxSize().padding(paddingValues)
                .onGloballyPositioned { lazyColumnSize = it.size }
                // Keep the same gesture owner when loading and chapter content cross-fade.
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { toggleMenu() })
                }
        ) {
            AnimatedVisibility(
                uiState.contentList.getOrNull(1) == null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(Modifier.fillMaxSize()) { Loading() }
            }
            AnimatedVisibility(
                uiState.contentList.getOrNull(1) != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                SelectionContainer {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize()
                            .nestedScroll(backgroundScrollConnection),
                        state = listState,
                        userScrollEnabled = !uiState.isPositioning,
                    ) {
                        itemsIndexed(
                            items = uiState.contentList,
                            key = { index, pair -> pair?.first ?: "placeholder-$index" }
                        ) { index, pair ->
                            val result = pair?.second ?: return@itemsIndexed
                            uiState.contentList.getOrNull(index + 1)?.second?.get()?.let {
                                if (!it.hasPrevChapter()) return@itemsIndexed
                            }
                            uiState.contentList.getOrNull(index - 1)?.second?.get()?.let {
                                if (!it.hasNextChapter()) return@itemsIndexed
                            }
                            result.onOk {
                                TextContent(
                                    modifier = Modifier.fillMaxWidth(),
                                    settingState = settingState,
                                    content = it,
                                    uiState = uiState,
                                    layout = paginationLayout
                                )
                            }.onErr {
                                Box(Modifier.fillMaxWidth().defaultMinSize(
                                    minHeight = with(density) { lazyColumnSize.height.toDp() }
                                )) {
                                    ChapterContentError(it) {
                                        if (pair.first == uiState.readingChapterId) uiState.retry()
                                        else uiState.changeChapter(pair.first)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun positiveModulo(value: Float, modulus: Float): Float =
    if (modulus <= 0f) 0f else ((value % modulus) + modulus) % modulus

@Composable
private fun TextContent(
    modifier: Modifier,
    settingState: SettingState,
    content: ChapterContentUiState,
    uiState: ScrollContentUiState,
    layout: ReaderPaginationLayout
) {
    val density = LocalDensity.current
    val componentRender = LocalComponentRender.current
    // Match the integer padding used by ParagraphComponentRender's layout modifier.
    val paragraphTopPadding = with(density) {
        LocalReaderStyle.current.spacingBeforeParagraph.toDp().roundToPx().toFloat()
    }
    val geometryKey = remember(uiState.bookId, content.id, layout, content.contentKey, content.title) {
        ScrollGeometryKey(uiState.bookId, content.id, content.contentKey, content.title, layout.key)
    }
    val onHeaderSize = remember(uiState, geometryKey) {
        { size: IntSize -> uiState.setHeaderHeight(geometryKey, size.height) }
    }
    val textColor = readerTextColor(settingState)
    val fontFamily = rememberReaderFontFamily(settingState.fontUriUserData)
    Column(
        Modifier.defaultMinSize(
            minHeight = with(density) {
                layout.height.toDp()
            }
        )
    ) {
        val titleRegex = Regex("^(第[一二三四五六七八九十]+卷)\\s+(.*)")
        val matchResult = titleRegex.find(content.title)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged(onHeaderSize)
                .padding(vertical = 36.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (matchResult != null) {
                val (volumeTitle, chapterTitle) = matchResult.destructured
                Text(
                    text = volumeTitle,
                    textAlign = TextAlign.Center,
                    fontSize = (settingState.fontSize + 2).sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = fontFamily,
                    color = textColor,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    text = chapterTitle,
                    textAlign = TextAlign.Center,
                    fontSize = (settingState.fontSize + 6).sp,
                    lineHeight = (settingState.fontSize + settingState.lineHeight + 6).sp,
                    fontWeight = FontWeight((settingState.fontWeigh.toInt() + 100)),
                    fontFamily = fontFamily,
                    color = textColor
                )
            } else {
                Text(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    text = content.title,
                    textAlign = TextAlign.Center,
                    fontSize = (settingState.fontSize + 6).sp,
                    lineHeight = (settingState.fontSize + settingState.lineHeight + 6).sp,
                    fontWeight = FontWeight((settingState.fontWeigh.toInt() + 100)),
                    fontFamily = fontFamily,
                    color = textColor
                )
            }
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                HorizontalDivider(
                    modifier = Modifier.width(48.dp),
                    color = textColor
                )
            }
            Spacer(Modifier.height(16.dp))
        }
        content.content.forEachIndexed { index, component ->
            val topPadding = if (component is ParagraphComponentData) paragraphTopPadding else 0f
            val onComponentSize = remember(uiState, geometryKey, index, topPadding) {
                { size: IntSize ->
                    uiState.setComponentSize(geometryKey, index, size.height, topPadding)
                    if (BuildConfig.BENCHMARK && component is ImageComponentData) {
                        ReaderBenchmarkProbe.onImageSize?.invoke(uiState.bookId, content.id, index, size.height)
                    }
                }
            }
            val onTextLayout = remember(uiState, geometryKey, index) {
                { textLayout: TextLayoutResult -> uiState.setTextLayout(geometryKey, index, textLayout) }
            }
            Box(Modifier.fillMaxWidth().onSizeChanged(onComponentSize)) {
                CompositionLocalProvider(LocalReaderTextLayout provides onTextLayout) {
                    componentRender.Component(modifier, component)
                }
            }
        }
    }
}
