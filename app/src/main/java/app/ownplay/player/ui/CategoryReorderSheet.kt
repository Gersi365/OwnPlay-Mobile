package app.ownplay.player.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.ownplay.player.live.LiveCategory
import app.ownplay.player.personalization.ManualOrderPlacement

private data class CategoryDropTarget(
    val anchorKey: String,
    val placement: ManualOrderPlacement,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CategoryReorderSheet(
    categories: List<LiveCategory>,
    onOrderChanged: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val isTelevision =
        configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    val doneFocusRequester = remember { FocusRequester() }
    var working by remember(categories) { mutableStateOf(categories) }
    var draggedKey by remember { mutableStateOf<String?>(null) }
    var pointerY by remember { mutableStateOf<Float?>(null) }
    var dropTarget by remember { mutableStateOf<CategoryDropTarget?>(null) }
    var dragAutoScrollStep by remember { mutableFloatStateOf(0f) }
    val listState = rememberLazyListState()

    LaunchedEffect(isTelevision) {
        if (isTelevision) {
            doneFocusRequester.requestFocus()
        }
    }

    fun clearDrag() {
        draggedKey = null
        pointerY = null
        dropTarget = null
        dragAutoScrollStep = 0f
    }

    fun applyOrder(next: List<LiveCategory>) {
        if (next == working) return
        working = next
        onOrderChanged(next.map(LiveCategory::providerCategoryKey))
    }

    fun moveWithRemote(index: Int, delta: Int) {
        val category = working.getOrNull(index) ?: return
        val anchor = working.getOrNull(index + delta) ?: return
        applyOrder(
            moveRelative(
                categories = working,
                draggedKey = category.providerCategoryKey,
                anchorKey = anchor.providerCategoryKey,
                placement = if (delta < 0) {
                    ManualOrderPlacement.BEFORE
                } else {
                    ManualOrderPlacement.AFTER
                },
            ),
        )
    }

    LaunchedEffect(draggedKey, dragAutoScrollStep) {
        val dragged = draggedKey ?: return@LaunchedEffect
        if (dragAutoScrollStep == 0f) return@LaunchedEffect
        while (draggedKey == dragged && dragAutoScrollStep != 0f) {
            val consumed = listState.scrollBy(dragAutoScrollStep)
            pointerY?.let { currentPointerY ->
                dropTarget = resolveCategoryTarget(
                    pointerY = currentPointerY,
                    draggedKey = dragged,
                    visibleItems = listState.layoutInfo.visibleItemsInfo,
                )
            }
            if (consumed == 0f) {
                dragAutoScrollStep = 0f
                break
            }
            withFrameNanos { }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 360.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "Reorder categories",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (isTelevision) {
                            "Use Up / Down with the remote. Press Done when finished."
                        } else {
                            "Hold a category, then drag it. Keep holding near an edge to scroll."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isTelevision) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.focusRequester(doneFocusRequester),
                    ) { Text("Done") }
                }
            }

            val listDragModifier = if (isTelevision) {
                Modifier
            } else {
                Modifier.pointerInput(working) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { start ->
                            val itemInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                                start.y >= info.offset && start.y <= info.offset + info.size
                            }
                            val key = itemInfo?.key as? String
                            if (key == null) {
                                clearDrag()
                            } else {
                                draggedKey = key
                                pointerY = start.y
                                dragAutoScrollStep = 0f
                                dropTarget = resolveCategoryTarget(
                                    pointerY = start.y,
                                    draggedKey = key,
                                    visibleItems = listState.layoutInfo.visibleItemsInfo,
                                )
                            }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val dragged = draggedKey ?: return@detectDragGesturesAfterLongPress
                            val nextY = (pointerY ?: return@detectDragGesturesAfterLongPress) + dragAmount.y
                            pointerY = nextY
                            val layout = listState.layoutInfo
                            dragAutoScrollStep = categoryAutoScrollStepForPointer(
                                pointerY = nextY,
                                viewportStartOffset = layout.viewportStartOffset,
                                viewportEndOffset = layout.viewportEndOffset,
                            )
                            dropTarget = resolveCategoryTarget(
                                pointerY = nextY,
                                draggedKey = dragged,
                                visibleItems = layout.visibleItemsInfo,
                            )
                        },
                        onDragEnd = {
                            val dragged = draggedKey
                            val target = dropTarget
                            if (dragged != null && target != null) {
                                applyOrder(
                                    moveRelative(
                                        categories = working,
                                        draggedKey = dragged,
                                        anchorKey = target.anchorKey,
                                        placement = target.placement,
                                    ),
                                )
                            }
                            clearDrag()
                        },
                        onDragCancel = ::clearDrag,
                    )
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(listDragModifier),
                contentPadding = PaddingValues(
                    horizontal = 12.dp,
                    vertical = 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(
                    items = working,
                    key = { _, category -> category.providerCategoryKey },
                ) { index, category ->
                    val key = category.providerCategoryKey
                    val isDragging = draggedKey == key
                    val isTarget = dropTarget?.anchorKey == key

                    Box(modifier = Modifier.fillMaxWidth()) {
                        if (
                            isTarget &&
                            dropTarget?.placement == ManualOrderPlacement.BEFORE
                        ) {
                            CategoryInsertionIndicator(
                                modifier = Modifier.align(Alignment.TopCenter),
                            )
                        }

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .zIndex(if (isDragging) 2f else 0f),
                            shape = RoundedCornerShape(12.dp),
                            color = when {
                                isDragging -> MaterialTheme.colorScheme.primaryContainer
                                isTarget -> MaterialTheme.colorScheme.surfaceVariant
                                else -> MaterialTheme.colorScheme.surface
                            },
                            shadowElevation = if (isDragging) 8.dp else 0.dp,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                if (!isTelevision) {
                                    Text(
                                        text = "≡",
                                        modifier = Modifier
                                            .background(
                                                color = if (isDragging) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.surfaceVariant
                                                },
                                                shape = RoundedCornerShape(8.dp),
                                            )
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        style = MaterialTheme.typography.titleLarge,
                                        color = if (isDragging) {
                                            MaterialTheme.colorScheme.onPrimary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = category.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (category.isHidden) {
                                        Text(
                                            text = "Hidden",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                if (isTelevision) {
                                    TextButton(onClick = { moveWithRemote(index, -1) }) {
                                        Text("Up")
                                    }
                                    TextButton(onClick = { moveWithRemote(index, 1) }) {
                                        Text("Down")
                                    }
                                } else if (isDragging) {
                                    Text(
                                        text = "MOVING",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }

                        if (
                            isTarget &&
                            dropTarget?.placement == ManualOrderPlacement.AFTER
                        ) {
                            CategoryInsertionIndicator(
                                modifier = Modifier.align(Alignment.BottomCenter),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryInsertionIndicator(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .zIndex(3f),
        thickness = 3.dp,
        color = MaterialTheme.colorScheme.primary,
    )
}

private fun categoryAutoScrollStepForPointer(
    pointerY: Float,
    viewportStartOffset: Int,
    viewportEndOffset: Int,
): Float {
    val edge = 96f
    val step = 28f
    return when {
        pointerY < viewportStartOffset + edge -> -step
        pointerY > viewportEndOffset - edge -> step
        else -> 0f
    }
}

private fun resolveCategoryTarget(
    pointerY: Float,
    draggedKey: String,
    visibleItems: List<androidx.compose.foundation.lazy.LazyListItemInfo>,
): CategoryDropTarget? {
    val candidates = visibleItems.mapNotNull { info ->
        val key = info.key as? String ?: return@mapNotNull null
        if (key == draggedKey) return@mapNotNull null
        info to key
    }
    val containing = candidates.firstOrNull { (info, _) ->
        pointerY >= info.offset && pointerY <= info.offset + info.size
    } ?: candidates.minByOrNull { (info, _) ->
        kotlin.math.abs(pointerY - (info.offset + info.size / 2f))
    } ?: return null
    val (info, key) = containing
    return CategoryDropTarget(
        anchorKey = key,
        placement = if (pointerY < info.offset + info.size / 2f) {
            ManualOrderPlacement.BEFORE
        } else {
            ManualOrderPlacement.AFTER
        },
    )
}

internal fun moveRelative(
    categories: List<LiveCategory>,
    draggedKey: String,
    anchorKey: String,
    placement: ManualOrderPlacement,
): List<LiveCategory> {
    if (draggedKey == anchorKey) return categories
    val dragged = categories.firstOrNull { it.providerCategoryKey == draggedKey } ?: return categories
    if (categories.none { it.providerCategoryKey == anchorKey }) return categories
    val without = categories.filterNot { it.providerCategoryKey == draggedKey }.toMutableList()
    val anchorIndex = without.indexOfFirst { it.providerCategoryKey == anchorKey }
    if (anchorIndex < 0) return categories
    val insertionIndex = if (placement == ManualOrderPlacement.BEFORE) {
        anchorIndex
    } else {
        anchorIndex + 1
    }
    without.add(insertionIndex.coerceIn(0, without.size), dragged)
    return without
}
