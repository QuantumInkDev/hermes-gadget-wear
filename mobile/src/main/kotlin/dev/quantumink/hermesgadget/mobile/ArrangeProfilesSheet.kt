package dev.quantumink.hermesgadget.mobile

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.quantumink.hermesgadget.protocol.CatalogPage

internal data class ArrangeBox(val id: String, val label: String, val pages: List<CatalogPage>)

private val RowHeight = 64.dp

/**
 * The one place to arrange the directory: drag profiles within a box, move boxes with arrows.
 * Each drop saves a single move, so the watch receives the finished order.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArrangeProfilesSheet(
    repository: CatalogRepository,
    boxes: List<ArrangeBox>,
    busy: Boolean,
    onMoveBox: (String, Int) -> Unit,
    onMoveProfile: (String, Int) -> Unit,
    dismiss: () -> Unit
) {
    val window = LocalWindowInfo.current.containerSize
    val height = with(LocalDensity.current) { window.height.toDp() * .9f }
    ModalBottomSheet(
        onDismissRequest = dismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = false,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = height)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Arrange profiles", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Drag a profile by its handle. Your watch uses the same order.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(onClick = dismiss) { Text("Done") }
            }
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                boxes.forEachIndexed { index, box ->
                    ArrangeBoxCard(
                        repository,
                        box,
                        busy,
                        showBoxArrows = boxes.size > 1,
                        canMoveUp = index > 0,
                        canMoveDown = index < boxes.lastIndex,
                        onMoveBox = { delta -> onMoveBox(box.id, delta) },
                        onMoveProfile = onMoveProfile
                    )
                }
            }
        }
    }
}

@Composable
private fun ArrangeBoxCard(
    repository: CatalogRepository,
    box: ArrangeBox,
    busy: Boolean,
    showBoxArrows: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveBox: (Int) -> Unit,
    onMoveProfile: (String, Int) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(20.dp)
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(
                    start = 16.dp,
                    end = 4.dp,
                    top = 8.dp,
                    bottom = 4.dp
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painterResource(R.drawable.ic_box),
                    null,
                    Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    box.label,
                    Modifier.weight(1f).padding(start = 10.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                if (showBoxArrows) {
                    ArrangeArrow(
                        "Move ${box.label} earlier",
                        R.drawable.ic_arrow_up,
                        !busy && canMoveUp
                    ) { onMoveBox(-1) }
                    ArrangeArrow(
                        "Move ${box.label} later",
                        R.drawable.ic_arrow_down,
                        !busy && canMoveDown
                    ) { onMoveBox(1) }
                }
            }
            if (box.pages.isEmpty()) {
                Text(
                    "No profiles yet",
                    Modifier.padding(start = 16.dp, bottom = 16.dp, top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                DraggableProfiles(repository, box.pages, busy, onMoveProfile)
            }
        }
    }
}

@Composable
private fun DraggableProfiles(
    repository: CatalogRepository,
    pages: List<CatalogPage>,
    busy: Boolean,
    onMove: (String, Int) -> Unit
) {
    val keys = pages.map { it.key }
    val byKey = pages.associateBy { it.key }
    // Local order follows the repository except while a drop is still being saved.
    var order by remember(keys) { mutableStateOf(keys) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var start by remember { mutableIntStateOf(0) }
    var offset by remember { mutableFloatStateOf(0f) }
    val rowPx = with(LocalDensity.current) { RowHeight.toPx() }
    val haptics = LocalHapticFeedback.current

    fun begin(key: String) {
        if (busy) return
        dragging = key
        start = order.indexOf(key)
        offset = 0f
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun drag(dy: Float) {
        val key = dragging ?: return
        offset += dy
        val index = order.indexOf(key)
        val target = when {
            offset > rowPx / 2 && index < order.lastIndex -> index + 1
            offset < -rowPx / 2 && index > 0 -> index - 1
            else -> return
        }
        order = order.toMutableList().apply { add(target, removeAt(index)) }
        offset -= (target - index) * rowPx
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    fun end() {
        val key = dragging ?: return
        dragging = null
        offset = 0f
        val delta = order.indexOf(key) - start
        if (delta != 0) onMove(key, delta)
    }

    fun nudge(key: String, delta: Int): Boolean {
        val index = order.indexOf(key)
        val target = index + delta
        if (busy || target !in order.indices) return false
        order = order.toMutableList().apply { add(target, removeAt(index)) }
        onMove(key, delta)
        return true
    }

    Column(Modifier.padding(bottom = 8.dp)) {
        order.forEachIndexed { index, key ->
            val page = byKey[key] ?: return@forEachIndexed
            val lifted = key == dragging
            val name = page.profile.name
            Surface(
                Modifier.fillMaxWidth().height(RowHeight).zIndex(if (lifted) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (lifted) offset else 0f
                        val scale = if (lifted) 1.02f else 1f
                        scaleX = scale
                        scaleY = scale
                    }
                    .pointerInput(key, busy) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { begin(key) },
                            onDrag = { change, amount ->
                                change.consume()
                                drag(amount.y)
                            },
                            onDragEnd = ::end,
                            onDragCancel = ::end
                        )
                    }
                    .semantics(mergeDescendants = true) {
                        contentDescription = "$name, position ${index + 1} of ${order.size}"
                        customActions = listOf(
                            CustomAccessibilityAction("Move $name earlier") { nudge(key, -1) },
                            CustomAccessibilityAction("Move $name later") { nudge(key, 1) }
                        )
                    },
                color = if (lifted) {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
                shape = RoundedCornerShape(if (lifted) 16.dp else 0.dp),
                shadowElevation = if (lifted) 8.dp else 0.dp
            ) {
                Row(
                    Modifier.padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "${index + 1}",
                        Modifier.size(20.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    DirectoryPortrait(repository, page, Modifier.size(40.dp))
                    Text(
                        name,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1
                    )
                    Box(
                        Modifier.size(48.dp).alpha(if (busy) 0.38f else 1f)
                            .pointerInput(key, busy) {
                                detectVerticalDragGestures(
                                    onDragStart = { begin(key) },
                                    onVerticalDrag = { change, amount ->
                                        change.consume()
                                        drag(amount)
                                    },
                                    onDragEnd = ::end,
                                    onDragCancel = ::end
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_drag_handle),
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ArrangeArrow(description: String, icon: Int, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(painterResource(icon), description)
    }
}
