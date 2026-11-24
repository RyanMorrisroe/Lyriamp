package com.ryanmorrisroe.lyriamp.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.animation.core.spring
import androidx.compose.animation.animateContentSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibraryFilter
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibraryItemType
import com.ryanmorrisroe.lyriamp.data.persistence.HomeDensity
import com.ryanmorrisroe.lyriamp.data.persistence.HomePin
import com.ryanmorrisroe.lyriamp.data.persistence.HomeSection
import com.ryanmorrisroe.lyriamp.data.persistence.HomeSectionType
import com.ryanmorrisroe.lyriamp.data.persistence.HomeSpecialAction
import com.ryanmorrisroe.lyriamp.feature.library.HomeViewModel
import com.ryanmorrisroe.lyriamp.ui.glyph.BackArrowIcon
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeCustomizationScreen(
    viewModel: HomeViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pickerVisible by remember { mutableStateOf(false) }
    var draggedPinIndex by remember { mutableStateOf(-1) }
    var pinDragOffsetX by remember { mutableStateOf(0f) }
    var pinDropTargetIndex by remember { mutableStateOf(-1) }
    var draggedSectionIndex by remember { mutableStateOf(-1) }
    var sectionDragOffsetY by remember { mutableStateOf(0f) }
    var sectionDropTargetIndex by remember { mutableStateOf(-1) }
    var sectionDragPointerY by remember { mutableStateOf(0f) }
    var sectionDragStartLocalY by remember { mutableStateOf(0f) }
    var sectionSourceBounds by remember { mutableStateOf<Rect?>(null) }
    var sectionContainerBounds by remember { mutableStateOf<Rect?>(null) }
    var sectionRowBounds by remember { mutableStateOf<Map<Int, Rect>>(emptyMap()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Customize Home") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = "Back" }
                    ) {
                        BackArrowIcon(
                            MaterialTheme.colorScheme.onSurface,
                            Modifier.size(24.dp)
                        )
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::resetPreferences) {
                        Text("Reset")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .onGloballyPositioned { sectionContainerBounds = it.boundsInRoot() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
            Text(
                "Pinned items",
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 6.dp),
                style = MaterialTheme.typography.titleMedium
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${state.preferences.pins.size} of 8 pins",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(
                    onClick = { pickerVisible = true },
                    enabled = state.preferences.pins.size < 8
                ) {
                    Text("+ Add")
                }
            }
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)
            ) {
                items(
                    items = state.preferences.pins,
                    key = { pin ->
                        if (pin.kind.name == "LIBRARY_ITEM") {
                            "${pin.itemType}:${pin.itemId}"
                        } else {
                            "special:${pin.specialAction}"
                        }
                    }
                ) { pin ->
                    val pinIndex = state.preferences.pins.indexOf(pin)
                    Column(
                        modifier = Modifier
                            .animateItem(placementSpec = spring())
                            .padding(vertical = 4.dp)
                            .graphicsLayer {
                                translationX = if (draggedPinIndex == pinIndex) pinDragOffsetX else 0f
                                alpha = if (draggedPinIndex == pinIndex) 0.92f else 1f
                            }
                            .zIndex(if (draggedPinIndex == pinIndex) 1f else 0f)
                            .background(
                                color = if (pinDropTargetIndex == pinIndex &&
                                    draggedPinIndex != pinIndex
                                ) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    androidx.compose.ui.graphics.Color.Transparent
                                },
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                            )
                            .border(
                                width = if (pinDropTargetIndex == pinIndex &&
                                    draggedPinIndex != pinIndex
                                ) 1.dp else 0.dp,
                                color = if (pinDropTargetIndex == pinIndex &&
                                    draggedPinIndex != pinIndex
                                ) MaterialTheme.colorScheme.primary else
                                    androidx.compose.ui.graphics.Color.Transparent,
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(modifier = Modifier.size(width = 112.dp, height = 72.dp)) {
                            androidx.compose.material3.Surface(
                                modifier = Modifier.fillMaxSize(),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainer
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        pin.title,
                                        modifier = Modifier
                                            .padding(horizontal = 8.dp)
                                            .padding(bottom = 12.dp),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                            TextButton(
                                onClick = { viewModel.removePin(pin) },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(32.dp)
                                    .padding(0.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text(
                                    "×",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                            val density = LocalDensity.current
                            var dragDistance by remember(pinIndex) { mutableStateOf(0f) }
                            Text(
                                "⠿",
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 4.dp)
                                    .pointerInput(pinIndex) {
                                        detectDragGestures(
                                            onDragStart = {
                                                draggedPinIndex = pinIndex
                                                pinDropTargetIndex = pinIndex
                                                pinDragOffsetX = 0f
                                                dragDistance = 0f
                                            },
                                            onDragEnd = {
                                                if (draggedPinIndex >= 0 &&
                                                    pinDropTargetIndex >= 0 &&
                                                    draggedPinIndex != pinDropTargetIndex
                                                ) {
                                                    viewModel.movePin(
                                                        draggedPinIndex,
                                                        pinDropTargetIndex
                                                    )
                                                }
                                                draggedPinIndex = -1
                                                pinDropTargetIndex = -1
                                                pinDragOffsetX = 0f
                                                dragDistance = 0f
                                            },
                                            onDragCancel = {
                                                draggedPinIndex = -1
                                                pinDropTargetIndex = -1
                                                pinDragOffsetX = 0f
                                                dragDistance = 0f
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                dragDistance += dragAmount.x
                                                pinDragOffsetX += dragAmount.x
                                                val itemStep = with(density) { 120.dp.toPx() }
                                                val offsetTarget =
                                                    (dragDistance / itemStep).roundToInt()
                                                pinDropTargetIndex =
                                                    (pinIndex + offsetTarget).coerceIn(
                                                        0,
                                                        state.preferences.pins.lastIndex
                                                    )
                                            }
                                        )
                                    },
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Text(
                "Home sections",
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 6.dp),
                style = MaterialTheme.typography.titleMedium
            )
            state.preferences.sections.forEachIndexed { index, section ->
                val title = sectionTitle(section.type)
                ListItem(
                    modifier = Modifier
                        .animateContentSize(animationSpec = spring())
                        .onGloballyPositioned { coordinates ->
                            sectionRowBounds =
                                sectionRowBounds + (index to coordinates.boundsInRoot())
                        }
                        .background(
                            color = if (draggedSectionIndex == index) {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            } else if (sectionDropTargetIndex == index) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                androidx.compose.ui.graphics.Color.Transparent
                            }
                        )
                        .border(
                            width = if (sectionDropTargetIndex == index) 2.dp else 0.dp,
                            color = if (sectionDropTargetIndex == index) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                androidx.compose.ui.graphics.Color.Transparent
                            },
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                        ),
                    headlineContent = { Text(title) },
                    supportingContent = {
                        Text("${section.limit} items")
                    },
                    leadingContent = {
                        val density = LocalDensity.current
                        var dragDistance by remember(index) { mutableStateOf(0f) }
                        Text(
                            "⠿",
                            modifier = Modifier
                                .size(32.dp)
                                .padding(top = 6.dp)
                                .pointerInput(index) {
                                    detectDragGestures(
                                        onDragStart = { startOffset ->
                                            draggedSectionIndex = index
                                            sectionDropTargetIndex = index
                                            sectionDragOffsetY = 0f
                                            dragDistance = 0f
                                            sectionSourceBounds = sectionRowBounds[index]
                                            sectionDragStartLocalY = startOffset.y
                                            sectionDragPointerY =
                                                sectionSourceBounds?.top?.plus(startOffset.y)
                                                    ?: 0f
                                        },
                                        onDragEnd = {
                                            if (draggedSectionIndex >= 0 &&
                                                sectionDropTargetIndex >= 0 &&
                                                draggedSectionIndex != sectionDropTargetIndex
                                            ) {
                                                viewModel.moveSection(
                                                    draggedSectionIndex,
                                                    sectionDropTargetIndex
                                                )
                                            }
                                            draggedSectionIndex = -1
                                            sectionDropTargetIndex = -1
                                            sectionDragOffsetY = 0f
                                            dragDistance = 0f
                                            sectionDragPointerY = 0f
                                            sectionDragStartLocalY = 0f
                                            sectionSourceBounds = null
                                        },
                                        onDragCancel = {
                                            draggedSectionIndex = -1
                                            sectionDropTargetIndex = -1
                                            sectionDragOffsetY = 0f
                                            dragDistance = 0f
                                            sectionDragPointerY = 0f
                                            sectionDragStartLocalY = 0f
                                            sectionSourceBounds = null
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            dragDistance += dragAmount.y
                                            sectionDragOffsetY += dragAmount.y
                                            val source = sectionSourceBounds
                                            sectionDragPointerY = if (source != null) {
                                                source.top +
                                                    sectionDragStartLocalY +
                                                    sectionDragOffsetY
                                            } else {
                                                sectionDragPointerY + dragAmount.y
                                            }
                                            val target = sectionRowBounds.entries
                                                .minByOrNull { (_, bounds) ->
                                                    val midpoint = (bounds.top + bounds.bottom) / 2f
                                                    kotlin.math.abs(sectionDragPointerY - midpoint)
                                                }
                                                ?.key
                                            if (target != null) {
                                                sectionDropTargetIndex = target
                                            }
                                        }
                                    )
                                },
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = section.enabled,
                            onCheckedChange = { enabled ->
                                viewModel.setSection(section.copy(enabled = enabled))
                            }
                        )
                    }
                )
            }

            Text(
                "Layout",
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 6.dp),
                style = MaterialTheme.typography.titleMedium
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HomeDensity.entries.forEach { density ->
                    FilterChip(
                        selected = state.preferences.density == density,
                        onClick = { viewModel.setDensity(density) },
                        label = { Text(density.name.lowercase().replaceFirstChar(Char::uppercase)) }
                    )
                }
            }

            state.actionMessage?.let { message ->
                Text(
                    message,
                    modifier = Modifier.padding(16.dp),
                    color = if (message.startsWith("Could not")) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            }
            }

            val draggedSection = draggedSectionIndex
                .takeIf { it >= 0 }
                ?.let(state.preferences.sections::getOrNull)
            val previewPosition = sectionDragPointerY
            val previewContainer = sectionContainerBounds
            if (draggedSection != null && previewContainer != null) {
                val previewY = previewPosition
                    .minus(previewContainer.top)
                    .minus(with(LocalDensity.current) { 28.dp.toPx() })
                    .coerceAtLeast(0f)
                Surface(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                x = 0,
                                y = previewY.roundToInt()
                            )
                        }
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .zIndex(20f)
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.primary,
                            androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                        ),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shadowElevation = 12.dp
                ) {
                    ListItem(
                        headlineContent = { Text(sectionTitle(draggedSection.type)) },
                        supportingContent = { Text("${draggedSection.limit} items") },
                        leadingContent = {
                            Text(
                                "⠿",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = draggedSection.enabled,
                                onCheckedChange = null
                            )
                        }
                    )
                }
            }
        }
    }

    if (pickerVisible) {
        PinPickerSheet(
            viewModel = viewModel,
            onDismiss = {
                pickerVisible = false
                viewModel.clearPinCandidates()
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PinPickerSheet(
    viewModel: HomeViewModel,
    onDismiss: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var source by remember { mutableStateOf(LmsLibraryItemType.ARTIST) }
    var specialSource by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Add pinned item", style = MaterialTheme.typography.titleLarge)
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = !specialSource && source == LmsLibraryItemType.ARTIST,
                        onClick = {
                            specialSource = false
                            source = LmsLibraryItemType.ARTIST
                            viewModel.searchPinCandidates(source, searchText)
                        },
                        label = { Text("Artist") }
                    )
                }
                item {
                    FilterChip(
                        selected = !specialSource && source == LmsLibraryItemType.ALBUM,
                        onClick = {
                            specialSource = false
                            source = LmsLibraryItemType.ALBUM
                            viewModel.searchPinCandidates(source, searchText)
                        },
                        label = { Text("Album") }
                    )
                }
                item {
                    FilterChip(
                        selected = !specialSource && source == LmsLibraryItemType.GENRE,
                        onClick = {
                            specialSource = false
                            source = LmsLibraryItemType.GENRE
                            viewModel.searchPinCandidates(source, searchText)
                        },
                        label = { Text("Genre") }
                    )
                }
                item {
                    FilterChip(
                        selected = !specialSource && source == LmsLibraryItemType.PLAYLIST,
                        onClick = {
                            specialSource = false
                            source = LmsLibraryItemType.PLAYLIST
                            viewModel.searchPinCandidates(source, searchText)
                        },
                        label = { Text("Playlist") }
                    )
                }
                item {
                    FilterChip(
                        selected = specialSource,
                        onClick = {
                            specialSource = true
                            viewModel.clearPinCandidates()
                        },
                        label = { Text("Special") }
                    )
                }
            }
            if (specialSource) {
                specialActions().forEach { (action, title) ->
                    ListItem(
                        modifier = Modifier.fillMaxWidth(),
                        headlineContent = { Text(title) },
                        supportingContent = { Text("Special action") },
                        trailingContent = {
                            TextButton(onClick = {
                                viewModel.addPin(HomePin.special(action, title))
                            }) { Text("Add") }
                        }
                    )
                }
            } else {
                OutlinedTextField(
                    value = searchText,
                    onValueChange = {
                        searchText = it
                        viewModel.searchPinCandidates(source, it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Search library") },
                    singleLine = true
                )
                state.pinSearchError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                state.pinCandidates.forEach { item ->
                    ListItem(
                        modifier = Modifier.fillMaxWidth(),
                        headlineContent = { Text(item.title) },
                        supportingContent = {
                            val detail = when (source) {
                                LmsLibraryItemType.ARTIST -> "Artist"
                                LmsLibraryItemType.ALBUM -> item.artist ?: "Album"
                                LmsLibraryItemType.GENRE -> "Genre"
                                LmsLibraryItemType.PLAYLIST -> "Playlist"
                                LmsLibraryItemType.TRACK -> "Track"
                            }
                            Text(detail)
                        },
                        trailingContent = {
                            TextButton(onClick = {
                                val filter = when (source) {
                                    LmsLibraryItemType.ARTIST ->
                                        LmsLibraryFilter(artistId = item.id)
                                    LmsLibraryItemType.ALBUM ->
                                        LmsLibraryFilter(albumId = item.id)
                                    LmsLibraryItemType.GENRE ->
                                        LmsLibraryFilter(genreId = item.id)
                                    LmsLibraryItemType.PLAYLIST ->
                                        LmsLibraryFilter(playlistId = item.id)
                                    LmsLibraryItemType.TRACK ->
                                        LmsLibraryFilter()
                                }
                                viewModel.addPin(
                                    HomePin.libraryItem(
                                        itemType = source,
                                        itemId = item.id,
                                        title = item.title,
                                        filter = filter,
                                        artworkId = item.artworkId,
                                        artworkUrl = item.artworkUrl
                                    )
                                )
                            }) { Text("Add") }
                        }
                    )
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text("Done")
            }
        }
    }
}

private fun sectionTitle(type: HomeSectionType): String = when (type) {
    HomeSectionType.RECENTLY_ADDED_ALBUMS -> "Recently added albums"
    HomeSectionType.MOST_PLAYED_ALBUMS -> "Most played albums"
    HomeSectionType.RECENTLY_PLAYED_ALBUMS -> "Recently played albums"
    HomeSectionType.RECENTLY_ADDED_ARTISTS -> "Recently added artists"
    HomeSectionType.MOST_PLAYED_ARTISTS -> "Most played artists"
    HomeSectionType.RECENTLY_PLAYED_ARTISTS -> "Recently played artists"
}

private fun specialActions(): List<Pair<HomeSpecialAction, String>> = listOf(
    HomeSpecialAction.SHUFFLE_TRACKS to "Shuffle",
    HomeSpecialAction.SHUFFLE_ARTISTS to "Shuffle artists",
    HomeSpecialAction.SHUFFLE_ALBUMS to "Shuffle albums",
    HomeSpecialAction.FAVORITES to "Favorites"
)
