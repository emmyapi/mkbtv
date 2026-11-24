package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.domain.model.CardDepthSurface
import com.nuvio.tv.ui.components.LocalCardDepthStyle
import com.nuvio.tv.ui.components.nuvioCardDepth
import com.nuvio.tv.ui.util.contentTextDirection

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import com.nuvio.tv.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewResponder
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.MetaCastMember
import kotlinx.coroutines.launch

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun CastSection(
    cast: List<MetaCastMember>,
    listState: LazyListState,
    title: String,
    modifier: Modifier = Modifier,
    leadingCast: List<MetaCastMember> = emptyList(),
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    sectionFocusRequester: FocusRequester? = null,
    restorePersonKey: String? = null,
    restoreFocusToken: Int = 0,
    blockDefaultRestore: Boolean = false,
    lastFocusedPersonKey: String? = null,
    onLastFocusedPersonKeyChange: (String) -> Unit = {},
    onRestoreFocusHandled: () -> Unit = {},
    onCastMemberFocused: (MetaCastMember) -> Unit = {},
    onCastMemberClick: (MetaCastMember, String) -> Unit = { _, _ -> },
    windowResetKey: String? = null
) {
    if (cast.isEmpty() && leadingCast.isEmpty()) return

    val castIds = remember(leadingCast, cast) { castWindowIds(leadingCast, cast) }
    listState.keepDetailRowWindow(
        itemIds = castIds,
        lazyKeyAt = { index -> castLazyKeyAt(index, leadingCast, cast) },
        resetKey = windowResetKey
    )

    val scope = rememberCoroutineScope()
    val itemFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    fun requesterFor(key: String) = itemFocusRequesters.getOrPut(key) { FocusRequester() }
    LaunchedEffect(castIds) {
        itemFocusRequesters.keys.retainAll(castIds.toSet())
    }

    var holdRestoreScrollSuppress by remember { mutableStateOf(false) }
    var holdEnterRowScrollSuppress by remember { mutableStateOf(false) }
    var enterRowHoldToken by remember { mutableIntStateOf(0) }
    var rowHasFocus by remember { mutableStateOf(false) }
    var sectionVerticallyOnScreen by remember { mutableStateOf(false) }
    val view = LocalView.current
    val suppressRestoreScroll = holdRestoreScrollSuppress ||
        holdEnterRowScrollSuppress ||
        (restoreFocusToken > 0 && restorePersonKey != null)
    val restoreNoScrollResponder = remember {
        object : BringIntoViewResponder {
            override fun calculateRectForParent(localRect: Rect): Rect = Rect.Zero
            override suspend fun bringChildIntoView(localRect: () -> Rect?) {}
        }
    }
    val restoreItemModifier = if (suppressRestoreScroll) {
        Modifier.bringIntoViewResponder(restoreNoScrollResponder)
    } else {
        Modifier
    }

    // The return token is raised only after the person detail closes.
    LaunchedEffect(restoreFocusToken) {
        if (restoreFocusToken <= 0 || restorePersonKey == null) return@LaunchedEffect
        val index = castIds.indexOf(restorePersonKey)
        if (index >= 0) {
            holdRestoreScrollSuppress = true
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                listState.scrollToItem(index)
            }
            requesterFor(restorePersonKey).requestFocusAfterFrames()
            repeat(2) { withFrameNanos { } }
            holdRestoreScrollSuppress = false
        }
        onRestoreFocusHandled()
    }

    LaunchedEffect(enterRowHoldToken) {
        if (enterRowHoldToken == 0) return@LaunchedEffect
        holdEnterRowScrollSuppress = true
        repeat(2) { withFrameNanos { } }
        holdEnterRowScrollSuppress = false
    }

    val itemWidth = 150.dp
    val cardSize = 100.dp
    val currentUpFocusRequester by rememberUpdatedState(upFocusRequester)
    val currentDownFocusRequester by rememberUpdatedState(downFocusRequester)

    val itemFocusPropertiesModifier = if (currentUpFocusRequester != null || currentDownFocusRequester != null) {
        Modifier.focusProperties {
            if (currentUpFocusRequester != null) up = currentUpFocusRequester!!
            if (currentDownFocusRequester != null) down = currentDownFocusRequester!!
        }
    } else {
        Modifier
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = NuvioTheme.spacing.sm)
            .onGloballyPositioned { coords ->
                val bounds = coords.boundsInWindow()
                val next = bounds.top >= -1f &&
                    bounds.bottom <= view.height + 1f &&
                    bounds.height > 1f
                if (next != sectionVerticallyOnScreen) {
                    sectionVerticallyOnScreen = next
                }
            }
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = NuvioTheme.colors.TextPrimary,
            modifier = Modifier
                .padding(horizontal = NuvioTheme.spacing.xxxl)
                .semantics { heading() }
        )
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))

        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (sectionFocusRequester != null) Modifier.focusRequester(sectionFocusRequester) else Modifier)
                .onFocusChanged { state ->
                    val entered = state.hasFocus && !rowHasFocus
                    rowHasFocus = state.hasFocus
                    if (entered && restorePersonKey == null && sectionVerticallyOnScreen) {
                        holdEnterRowScrollSuppress = true
                        enterRowHoldToken += 1
                    }
                }
                .focusProperties {
                    onEnter = {
                        if (blockDefaultRestore) {
                            cancelFocusChange()
                        } else {
                            val direction = requestedFocusDirection
                            val key = restorePersonKey?.takeIf { it in castIds }
                                ?: lastFocusedPersonKey?.takeIf { direction != FocusDirection.Down && it in castIds }
                                ?: castIds.first()
                            val index = castIds.indexOf(key)
                            val requester = requesterFor(key)
                            if (listState.layoutInfo.visibleItemsInfo.any { it.index == index }) {
                                requester.requestFocus()
                            } else {
                                // A lazy row may still be scrolled to a person from the last visit.
                                cancelFocusChange()
                                scope.launch {
                                    listState.scrollToItem(index)
                                    requester.requestFocusAfterFrames(frames = 0, direction = direction)
                                }
                            }
                        }
                    }
                }
                .focusGroup(),
            state = listState,
            contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.xxxl, vertical = 6.dp),
            horizontalArrangement = Arrangement.Start
        ) {
            val standardGap = NuvioTheme.spacing.sm
            val deadSpace = itemWidth - cardSize

            if (leadingCast.isNotEmpty()) {
                itemsIndexed(
                    items = leadingCast,
                    key = { index, member -> leadingCastLazyKey(index, member) }
                ) { _, member ->
                    val isLastLeading = member == leadingCast.last()
                    val endPadding = if (isLastLeading && cast.isNotEmpty()) NuvioTheme.spacing.none else standardGap
                    val focusKey = castMemberWindowId(member, leading = true)

                    Box(modifier = restoreItemModifier.padding(end = endPadding)) {
                        CastMemberItem(
                            member = member,
                            modifier = Modifier
                                .focusRequester(requesterFor(focusKey))
                                .then(itemFocusPropertiesModifier),
                            itemWidth = itemWidth,
                            cardSize = cardSize,
                            onFocused = {
                                onLastFocusedPersonKeyChange(focusKey)
                                onCastMemberFocused(member)
                            },
                            onClick = { onCastMemberClick(member, focusKey) }
                        )
                    }
                }
            }

            if (leadingCast.isNotEmpty() && cast.isNotEmpty()) {
                item(key = CAST_ROLE_DIVIDER_ID) {
                    Box(
                        modifier = Modifier.height(cardSize),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(NuvioTheme.spacing.hairline)
                                .height(72.dp)
                                .offset(x = -deadSpace / 2)
                                .background(NuvioTheme.colors.SurfaceVariant.copy(alpha = 0.9f))
                        )
                    }
                }
            }

            itemsIndexed(
                items = cast,
                key = { index, member -> castMemberLazyKey(index, member) }
            ) { _, member ->
                val focusKey = castMemberWindowId(member, leading = false)

                Box(modifier = restoreItemModifier.padding(end = standardGap)) {
                    CastMemberItem(
                        member = member,
                        modifier = Modifier
                            .focusRequester(requesterFor(focusKey))
                            .then(itemFocusPropertiesModifier),
                        itemWidth = itemWidth,
                        cardSize = cardSize,
                        onFocused = {
                            onLastFocusedPersonKeyChange(focusKey)
                            onCastMemberFocused(member)
                        },
                        onClick = { onCastMemberClick(member, focusKey) }
                    )
                }
            }
        }
    }
}

private const val CAST_ROLE_DIVIDER_ID = "role_divider"

private fun castMemberWindowId(member: MetaCastMember, leading: Boolean): String {
    val prefix = if (leading) "leading" else "cast"
    return "$prefix:${member.tmdbId ?: member.name}:${member.character.orEmpty()}"
}

private fun castWindowIds(
    leadingCast: List<MetaCastMember>,
    cast: List<MetaCastMember>,
): List<String> {
    val ids = ArrayList<String>(leadingCast.size + cast.size + 1)
    leadingCast.forEach { member -> ids += castMemberWindowId(member, leading = true) }
    if (leadingCast.isNotEmpty() && cast.isNotEmpty()) ids += CAST_ROLE_DIVIDER_ID
    cast.forEach { member -> ids += castMemberWindowId(member, leading = false) }
    return ids
}

private fun leadingCastLazyKey(index: Int, member: MetaCastMember): String =
    "leading|" + index + "|" + (member.tmdbId?.toString() ?: member.name) + "|" +
        (member.character ?: "") + "|" + (member.photo ?: "")

private fun castMemberLazyKey(index: Int, member: MetaCastMember): String =
    index.toString() + "|" + (member.tmdbId?.toString() ?: member.name) + "|" +
        (member.character ?: "") + "|" + (member.photo ?: "")

private fun castLazyKeyAt(
    index: Int,
    leadingCast: List<MetaCastMember>,
    cast: List<MetaCastMember>,
): Any? {
    val hasDivider = leadingCast.isNotEmpty() && cast.isNotEmpty()
    return when {
        index < leadingCast.size -> leadingCast.getOrNull(index)?.let { leadingCastLazyKey(index, it) }
        hasDivider && index == leadingCast.size -> CAST_ROLE_DIVIDER_ID
        else -> {
            val castIndex = index - leadingCast.size - if (hasDivider) 1 else 0
            cast.getOrNull(castIndex)?.let { castMemberLazyKey(castIndex, it) }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CastMemberItem(
    member: MetaCastMember,
    modifier: Modifier = Modifier,
    itemWidth: Dp = 150.dp,
    cardSize: Dp = 100.dp,
    onFocused: () -> Unit = {},
    onClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val cardSizePx = remember(cardSize, density) {
        with(density) { cardSize.roundToPx() }
    }
    val typography = MaterialTheme.typography
    val nameStyle = remember(typography) { typography.labelMedium }
    val characterStyle = remember(typography) { typography.labelSmall }
    val initialsStyle = remember(typography) { typography.titleLarge }
    val photo = member.photo
    val photoModel = remember(context, photo, cardSizePx) {
        photo?.takeIf { it.isNotBlank() }?.let { url ->
            ImageRequest.Builder(context)
                .data(url)
                .crossfade(false)
                .size(width = cardSizePx, height = cardSizePx)
                .build()
        }
    }

    var isFocused by remember { mutableStateOf(false) }
    val cardDepthStyle = LocalCardDepthStyle.current
    val labelAreaHeight = remember(density, nameStyle, characterStyle) {
        with(density) {
            nameStyle.lineHeight.toDp() * 2 +
                NuvioTheme.spacing.xs +
                characterStyle.lineHeight.toDp()
        }
    }

    Column(
        modifier = Modifier.width(itemWidth),
        horizontalAlignment = Alignment.Start
    ) {
        // The visible name/character live in the sibling Column below and the portrait carries the
        // photo's description, so the focusable card replaces all of them with one stable name:
        // the person plus their character. With no photo the card would otherwise degrade to the
        // fallback initial ("N" or "?").
        val cardDescription = member.character
            ?.takeIf { it.isNotBlank() }
            ?.let { "${member.name}, $it" }
            ?: member.name
        Card(
            onClick = onClick,
            modifier = modifier
                .size(cardSize)
                .align(Alignment.Start)
                .onFocusChanged { state ->
                    isFocused = state.isFocused
                    if (state.isFocused) onFocused()
                }
                .clearAndSetSemantics {
                    contentDescription = cardDescription
                    role = Role.Button
                },
            shape = CardDefaults.shape(
                shape = CircleShape
            ),
            colors = CardDefaults.colors(
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
                focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent
            ),
            border = CardDefaults.border(
                focusedBorder = Border(
                    border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                    shape = CircleShape
                )
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .nuvioCardDepth(
                        shape = CircleShape,
                        surface = CardDepthSurface.CAST,
                        style = cardDepthStyle
                    ),
                contentAlignment = Alignment.Center
            ) {
                val currentBgColor = if (isFocused) NuvioTheme.colors.FocusBackground else NuvioTheme.colors.SurfaceVariant
                val bgPainter = remember(currentBgColor) { androidx.compose.ui.graphics.painter.ColorPainter(currentBgColor) }

                if (photoModel != null) {
                    AsyncImage(
                        model = photoModel,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        placeholder = bgPainter,
                        error = bgPainter,
                        fallback = bgPainter
                    )
                } else {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.fillMaxSize().background(currentBgColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = member.name.firstOrNull()?.uppercase() ?: "?",
                            style = initialsStyle,
                            color = NuvioTheme.colors.TextPrimary
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(labelAreaHeight)
        ) {
            Text(
                text = member.name,
                style = nameStyle,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            val character = member.character
            if (!character.isNullOrBlank()) {
                val displayCharacter = when {
                    character.equals("Creator", ignoreCase = true) -> stringResource(R.string.cast_role_creator)
                    character.equals("Director", ignoreCase = true) -> stringResource(R.string.cast_role_director)
                    character.equals("Writer", ignoreCase = true) -> stringResource(R.string.cast_role_writer)
                    else -> character
                }
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
                Text(
                    text = displayCharacter,
                    style = characterStyle.copy(
                        textDirection = displayCharacter.contentTextDirection()
                    ),
                    color = NuvioTheme.colors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
