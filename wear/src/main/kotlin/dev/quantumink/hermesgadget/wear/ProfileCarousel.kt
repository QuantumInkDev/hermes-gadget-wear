package dev.quantumink.hermesgadget.wear

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import dev.quantumink.hermesgadget.protocol.ConnectionStatus
import dev.quantumink.hermesgadget.protocol.ConversationMode
import kotlinx.coroutines.launch

@Composable
fun ProfileCarousel(
    watch: WatchState,
    navigation: WatchNavigation,
    service: GadgetService,
    connect: () -> Unit,
    review: (ProfilePage) -> Unit,
    conversation: @Composable (ProfilePage, @Composable () -> Unit) -> Unit
) {
    val pages =
        remember(watch.profiles, watch.catalog) {
            ProfilePages.merge(watch.profiles, watch.catalog)
        }
    if (pages.isEmpty()) return
    val keys = pages.map { it.key }
    val initialKey = navigation.page.value.ifBlank { watch.selectedPage }
    val initial = pages.indexOfFirst { it.key == initialKey }.takeIf { it >= 0 }
        ?: pages.indexOfFirst {
            it.approved?.endpoint?.url == watch.profile?.endpoint?.url
        }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = initial) { pages.size }
    val scope = rememberCoroutineScope()
    var correcting by remember { mutableStateOf(true) }
    val locked = watch.saving || watch.connection.conversation.prompt != null ||
        watch.connection.conversation.mode == ConversationMode.LISTENING
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
        watch.saving,
        watch.profile?.endpoint?.url,
        keys
    ) {
        if (correcting || pager.isScrollInProgress) return@LaunchedEffect
        val index = pager.settledPage
        val page = pages.getOrNull(index) ?: return@LaunchedEffect
        navigation.page.value = page.key
        if (watch.selectedPage != page.key) service.rememberPage(page.key)
        service.preparePages((index - 1..index + 1).mapNotNull { pages.getOrNull(it)?.key })
        if (watch.saving) return@LaunchedEffect
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
    HorizontalPager(state = pager, key = {
        pages[it].key
    }, userScrollEnabled = !locked, modifier = Modifier.fillMaxSize()) { index ->
        val page = pages[index]
        val selected = index == pager.settledPage && !pager.isScrollInProgress
        val controls: @Composable () -> Unit = {
            PageControls(
                index,
                pages.size,
                locked,
                previous = { scope.launch { pager.animateScrollToPage(index - 1) } },
                next = { scope.launch { pager.animateScrollToPage(index + 1) } }
            )
        }
        if (selected && page.approved != null &&
            page.approved.endpoint.url == watch.profile?.endpoint?.url
        ) {
            conversation(page, controls)
        } else if (selected) {
            RoundPage {
                item {
                    ProfileIdentity(page, watch.artwork[page.key], watch.copy(pet = null), false)
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
                item { controls() }
                item { Secondary("Review connection", { review(page) }, enabled = !watch.saving) }
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
            // Neighbors are static previews and never own rotary focus or a Gadget session.
            Column(
                Modifier.fillMaxSize().padding(36.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ProfileIdentity(page, watch.artwork[page.key], WatchState(), false)
            }
        }
    }
}

@Composable
fun ProfileIdentity(
    page: ProfilePage,
    artwork: PageArtwork?,
    watch: WatchState,
    selected: Boolean
) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        val pet = if (selected) watch.pet ?: artwork?.pet else artwork?.pet
        Crossfade(
            targetState = pet,
            modifier = Modifier.size(96.dp, 104.dp),
            animationSpec = tween(220),
            label = "profile artwork"
        ) { shown ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (shown == null && artwork?.portrait != null) {
                    Image(
                        remember(artwork.portrait) { artwork.portrait.asImageBitmap() },
                        "${page.label} profile picture",
                        Modifier.size(80.dp)
                    )
                } else {
                    PetView(
                        watch.copy(pet = shown),
                        animated = selected && page.status == CatalogStatus.AVAILABLE
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

@Composable
private fun PageControls(
    index: Int,
    count: Int,
    locked: Boolean,
    previous: () -> Unit,
    next: () -> Unit
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(previous, Modifier.weight(1f), enabled = index > 0 && !locked) {
                Text("Previous", style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
            FilledTonalButton(next, Modifier.weight(1f), enabled = index + 1 < count && !locked) {
                Text("Next", style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
        Text("${index + 1} / $count", style = MaterialTheme.typography.labelSmall)
    }
}
