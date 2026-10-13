package dev.quantumink.hermesgadget.wear

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.hierarchicalFocusGroup
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import dev.quantumink.hermesgadget.protocol.ConnectionStatus

@Composable
fun ProfileCarousel(
    watch: WatchState,
    navigation: WatchNavigation,
    service: GadgetService,
    connect: () -> Unit,
    review: (ProfilePage) -> Unit,
    conversation: @Composable (
        ProfilePage,
        TransformingLazyColumnState,
        @Composable () -> Unit
    ) -> Unit
) {
    val incoming =
        remember(watch.profiles, watch.catalog) {
            ProfilePages.merge(watch.profiles, watch.catalog)
        }
    val locked = watch.selectionLocked
    // Keep both stable pager keys and the approved conversation projection mounted for the
    // entire turn. Catalog URL changes/removals reconcile only after the selection lock clears.
    val projection = remember { CarouselProjection(incoming) }
    val pages = if (locked) projection.pages else incoming
    SideEffect { projection.pages = pages }
    if (pages.isEmpty()) return
    val keys = pages.map { it.key }
    LaunchedEffect(keys) { navigation.retainScrolls(keys.toSet()) }
    val initialKey = navigation.page.value.ifBlank { watch.selectedPage }
    val initial = pages.indexOfFirst { it.key == initialKey }.takeIf { it >= 0 }
        ?: pages.indexOfFirst {
            it.approved?.endpoint?.url == watch.profile?.endpoint?.url
        }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = initial) { pages.size }
    var correcting by remember { mutableStateOf(true) }
    LaunchedEffect(keys, watch.selectedPage) {
        correcting = true
        val key = watch.selectedPage.ifBlank { navigation.page.value }
        val index = pages.indexOfFirst { it.key == key }.takeIf { it >= 0 }
            ?: pages.indexOfFirst {
                it.approved?.endpoint?.url == watch.profile?.endpoint?.url
            }.coerceAtLeast(0)
        pager.scrollToPage(index)
        correcting = false
    }
    LaunchedEffect(
        pager.settledPage,
        pager.isScrollInProgress,
        correcting,
        locked,
        watch.profile?.endpoint?.url,
        pages
    ) {
        if (correcting || pager.isScrollInProgress) return@LaunchedEffect
        val index = pager.settledPage
        val page = pages.getOrNull(index) ?: return@LaunchedEffect
        navigation.page.value = page.key
        if (watch.selectedPage != page.key) service.rememberPage(page.key)
        service.preparePages((index - 1..index + 1).mapNotNull { pages.getOrNull(it)?.key })
        if (locked) return@LaunchedEffect
        val approved = page.approved
        if (approved == null) {
            if (watch.connection.status != ConnectionStatus.OFFLINE) service.disconnect()
        } else if (approved.endpoint.url != watch.profile?.endpoint?.url) {
            service.selectProfile(approved.endpoint.url) {
                if (service.state.value.selectedPage == page.key &&
                    page.status == CatalogStatus.AVAILABLE
                ) {
                    connect()
                }
            }
        } else if (page.status == CatalogStatus.AVAILABLE &&
            watch.connection.status == ConnectionStatus.OFFLINE
        ) {
            connect()
        }
    }
    fun select(key: String): Boolean {
        if (locked || pages.none { it.key == key }) return false
        // Persist the exact key; the existing pager effect resolves its current position.
        service.rememberPage(key)
        return true
    }
    val density = LocalDensity.current
    val position = pager.settledPage.coerceIn(pages.indices)
    val selectedScroll = navigation.scrollFor(pages[position].key)
    val layout = selectedScroll.layoutInfo
    val bandTop = layout.viewportSize.height - with(density) { 20.dp.toPx() }
    val bandBottom = layout.viewportSize.height - with(density) { 8.dp.toPx() }
    val indicatorClear = layout.visibleItems.isNotEmpty() &&
        !pager.isScrollInProgress && watch.connection.conversation.prompt == null &&
        !navigation.pickerOpen.value && layout.visibleItems.none {
            it.offset < bandBottom && it.offset + it.transformedHeight > bandTop
        }
    Box(
        Modifier.fillMaxSize().semantics {
            stateDescription = "Profile ${position + 1} of ${pages.size}"
            customActions = listOf(
                CustomAccessibilityAction("Choose a profile") {
                    navigation.pickerOpen.value = true
                    true
                }
            )
        }
    ) {
        HorizontalPager(state = pager, key = {
            pages[it].key
        }, userScrollEnabled = !locked, modifier = Modifier.fillMaxSize()) { index ->
            val page = pages[index]
            val pageScroll = navigation.scrollFor(page.key)
            // Nested vertical scrolling also puts the pager in progress. Keep its settled
            // page mounted until a different page settles so the list retains state/focus.
            val selected = index == pager.settledPage
            val controls: @Composable () -> Unit = {
                FilledTonalButton(
                    onClick = { navigation.pickerOpen.value = true },
                    modifier = Modifier.size(48.dp).semantics {
                        contentDescription = "Choose a profile"
                        stateDescription = "${page.label} · Profile ${index + 1} of ${pages.size}"
                        customActions = if (locked) {
                            emptyList()
                        } else {
                            buildList {
                                pages.getOrNull(index - 1)?.let { previous ->
                                    add(
                                        CustomAccessibilityAction("Previous profile") {
                                            select(previous.key)
                                        }
                                    )
                                }
                                pages.getOrNull(index + 1)?.let { next ->
                                    add(
                                        CustomAccessibilityAction("Next profile") {
                                            select(next.key)
                                        }
                                    )
                                }
                            }
                        }
                    }
                ) { Text("≡", style = MaterialTheme.typography.titleLarge) }
            }
            Box(
                Modifier.fillMaxSize().hierarchicalFocusGroup(
                    active = selected && !navigation.pickerOpen.value
                )
            ) {
                if (selected && page.approved != null &&
                    page.approved.endpoint.url == watch.profile?.endpoint?.url
                ) {
                    conversation(page, pageScroll, controls)
                } else if (selected) {
                    RoundPage(topPadding = 28.dp, state = pageScroll) {
                        item {
                            ProfileIdentity(
                                page,
                                watch.artwork[page.key],
                                watch.copy(pet = null),
                                selected
                            )
                        }
                        item {
                            Body(
                                if (page.approved !=
                                    null
                                ) {
                                    "Preparing connection"
                                } else if (page.discovered?.profile?.gadgetUrl ==
                                    null
                                ) {
                                    "Watch setup needed"
                                } else {
                                    "Review connection before pairing"
                                }
                            )
                        }
                        item {
                            Secondary("Review connection", {
                                review(page)
                            }, enabled = !watch.saving)
                        }
                        item {
                            Secondary("Refresh profiles", {
                                service.refreshCatalog()
                            }, enabled = !watch.catalogRefreshing)
                        }
                        if (watch.catalogNotice.isNotBlank()) {
                            item {
                                Body(watch.catalogNotice, muted = true)
                            }
                        }
                    }
                } else {
                    // Neighbors are static previews without rotary focus or a Gadget session.
                    Column(
                        Modifier.fillMaxSize().padding(36.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        ProfileIdentity(page, watch.artwork[page.key], WatchState(), false)
                    }
                }
                val firstItem = pageScroll.layoutInfo.visibleItems.firstOrNull { it.index == 0 }
                val portraitAtTop = firstItem != null &&
                    firstItem.offset >= with(density) { 28.dp.roundToPx() }
                if (selected && portraitAtTop && watch.connection.conversation.prompt == null) {
                    Box(Modifier.align(Alignment.BottomEnd).padding(end = 40.dp, bottom = 40.dp)) {
                        controls()
                    }
                }
            }
        }
        if (indicatorClear) {
            ProfilePageIndicator(
                pager.settledPage,
                pages.size,
                Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
            )
        }
    }
    if (navigation.pickerOpen.value && navigation.pinKey.value != null) {
        ProfilePinEditor(pages, requireNotNull(navigation.pinKey.value), locked) {
            navigation.pinKey.value = null
        }
    } else if (navigation.pickerOpen.value) {
        ProfilePicker(
            pages,
            // A refreshed catalog can precede the pager's position reconciliation.
            keys.firstOrNull {
                it == watch.selectedPage.ifBlank { navigation.page.value }
            }.orEmpty(),
            locked,
            select = { key -> if (select(key)) navigation.pickerOpen.value = false },
            close = { navigation.pickerOpen.value = false },
            pin = { navigation.pinKey.value = it }
        )
    }
}

@Composable
fun ProfileIdentity(
    page: ProfilePage,
    artwork: PageArtwork?,
    watch: WatchState,
    selected: Boolean,
    modifier: Modifier = Modifier,
    recordingHint: String = ""
) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        val pet = if (selected) watch.pet ?: artwork?.pet else artwork?.pet
        Box(Modifier.requiredSize(160.dp, 168.dp).then(modifier)) {
            Crossfade(
                targetState = pet,
                modifier = Modifier.requiredSize(160.dp, 168.dp),
                animationSpec = tween(220),
                label = "profile artwork"
            ) { shown ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (shown == null && artwork?.portrait != null) {
                        AnimatedPortrait(
                            artwork.animation,
                            artwork.portrait,
                            "${page.label} profile picture",
                            selected && page.status == CatalogStatus.AVAILABLE,
                            Modifier.size(160.dp)
                        )
                    } else {
                        PetView(
                            watch.copy(pet = shown),
                            animated = selected && page.status == CatalogStatus.AVAILABLE
                        )
                    }
                }
            }
            RecordingRings(watch.captureActive, selected, watch.connection.conversation.level)
            if (recordingHint.isNotEmpty()) {
                val ink = MaterialTheme.colorScheme.onSurface
                Canvas(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp)
                        .size(
                            28.dp
                        ).background(MaterialTheme.colorScheme.surfaceContainer, CircleShape)
                        .semantics { contentDescription = recordingHint }
                ) {
                    val unit = size.width / 28f
                    val stroke = Stroke(1.5f * unit)
                    drawRoundRect(
                        ink,
                        Offset(11f * unit, 5f * unit),
                        Size(6f * unit, 11f * unit),
                        CornerRadius(3f * unit),
                        style = stroke
                    )
                    drawArc(
                        ink,
                        0f,
                        180f,
                        false,
                        Offset(8f * unit, 8f * unit),
                        Size(
                            12f * unit,
                            12f * unit
                        ),
                        style = stroke
                    )
                    drawLine(
                        ink,
                        Offset(14f * unit, 20f * unit),
                        Offset(14f * unit, 24f * unit),
                        strokeWidth =
                        1.5f * unit
                    )
                    drawLine(
                        ink,
                        Offset(10f * unit, 24f * unit),
                        Offset(18f * unit, 24f * unit),
                        strokeWidth =
                        1.5f * unit
                    )
                }
            }
        }
        Text(
            page.label,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
        Text(
            page.source,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
        if (page.status != CatalogStatus.AVAILABLE) {
            Text(
                when (page.status) {
                    CatalogStatus.ATTENTION -> "Catalog needs attention"
                    CatalogStatus.WITHDRAWN -> "Withdrawn · setup retained"
                    else -> "Offline · cached profile"
                },
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center
            )
        }
    }
}

private class CarouselProjection(var pages: List<ProfilePage>)
