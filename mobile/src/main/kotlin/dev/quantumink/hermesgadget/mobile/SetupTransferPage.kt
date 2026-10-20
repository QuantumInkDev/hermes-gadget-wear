package dev.quantumink.hermesgadget.mobile

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.quantumink.hermesgadget.protocol.EndpointProfile
import dev.quantumink.hermesgadget.protocol.MigrationArchive
import dev.quantumink.hermesgadget.protocol.MigrationStagedReadResult
import dev.quantumink.hermesgadget.protocol.SetupCatalog
import dev.quantumink.hermesgadget.protocol.SetupConnectionPreview
import dev.quantumink.hermesgadget.protocol.SetupEndpoint
import dev.quantumink.hermesgadget.protocol.SetupMetadataCodec
import dev.quantumink.hermesgadget.protocol.UntrustedSetupReview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Screen states. Archive contents and passphrases never enter saved instance state. */
internal sealed interface TransferScreen {
    data object Home : TransferScreen
    class Share(val endpoints: List<EndpointProfile>, val sources: List<SetupCatalog>) :
        TransferScreen
    class ShareCode(val title: String, val text: String, val bitmap: Bitmap, val expires: Long) :
        TransferScreen
    class Review(val review: UntrustedSetupReview) : TransferScreen
    class Export(val bundle: ExportBundle) : TransferScreen
    class Unlock(val envelope: ArchiveInput) : TransferScreen
    class Preview(val preview: ImportPreview) : TransferScreen
    class Working(val message: String) : TransferScreen
    class Done(val title: String, val message: String, val publication: SetupPublication? = null) :
        TransferScreen
}

private fun TransferScreen.closeOwned() {
    when (this) {
        is TransferScreen.Export -> bundle.close()
        is TransferScreen.Preview -> preview.close()
        is TransferScreen.Unlock -> envelope.close()
        else -> Unit
    }
}

private const val ARCHIVE_TYPE = "application/octet-stream"

/** Generic read failure: it covers wrong passphrases, damage, unsupported files and I/O. */
internal const val ARCHIVE_UNREADABLE =
    "Could not open this archive. Check the passphrase and that the file is a complete " +
        "Quantum Relay export, then try again."

@Composable
internal fun SetupTransferPage(
    context: Context,
    back: () -> Unit,
    stores: () -> SetupTransferStores = { SetupTransferStores.get(context) },
    now: () -> Long = { System.currentTimeMillis() / 1000 },
    initial: TransferScreen = TransferScreen.Home,
    pictureReader: (
        Uri
    ) -> ByteArray = { readBounded(context.contentResolver, it, SetupQr.MAX_IMAGE_BYTES) }
) {
    val scope = rememberCoroutineScope()
    val lease = remember { TransferReadLease() }
    var screen by remember { mutableStateOf(initial) }
    val screenStart = remember { BringIntoViewRequester() }
    LaunchedEffect(screen) {
        androidx.compose.runtime.withFrameNanos { }
        screenStart.bringIntoView()
    }
    var notice by remember { mutableStateOf("") }
    var blocked by remember { mutableStateOf(false) }
    var replayProblem by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<CharArray?>(null) }
    var pendingBundle by remember { mutableStateOf<ExportBundle?>(null) }
    var pendingCode by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(screen) {
        if (screen != TransferScreen.Home) return@LaunchedEffect
        blocked = withContext(Dispatchers.IO) {
            runCatching { SetupImport.blocked(stores()) }.getOrDefault(true)
        }
        replayProblem = withContext(Dispatchers.IO) {
            runCatching {
                SetupReplayJournal(stores().directory).unreadable(now())
            }.getOrDefault(true)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            lease.dispose()
            pendingExport?.fill(' ')
            (screen as? TransferScreen.Preview)?.preview?.close()
            (screen as? TransferScreen.Unlock)?.envelope?.close()
            (screen as? TransferScreen.Export)?.bundle?.close()
        }
    }
    fun show(next: TransferScreen) {
        lease.invalidate()
        val previous = screen
        if (previous is TransferScreen.Export && next !== previous) previous.bundle.close()
        if (previous is TransferScreen.Preview && next !is TransferScreen.Preview) {
            previous.preview.close()
        }
        if (previous is TransferScreen.Unlock && next !is TransferScreen.Unlock &&
            next !is TransferScreen.Working
        ) {
            previous.envelope.close()
        }
        screen = next
    }
    fun work(message: String, task: suspend () -> TransferScreen) {
        if (screen is TransferScreen.Working) return
        val previous = screen
        val generation = lease.begin()
        notice = ""
        screen = TransferScreen.Working(message)
        scope.launch {
            var produced: TransferScreen? = null
            val next = runCatching {
                withContext(Dispatchers.IO) { task().also { produced = it } }
            }.getOrElse { failure ->
                if (failure is kotlinx.coroutines.CancellationException) {
                    produced?.closeOwned()
                    (previous as? TransferScreen.Export)?.bundle?.close()
                    (previous as? TransferScreen.Preview)?.preview?.close()
                    (previous as? TransferScreen.Unlock)?.envelope?.close()
                    throw failure
                }
                notice = (failure as? ImportPlanException)?.problems?.joinToString("\n")
                    ?: "That did not finish. Return to Move setup to check recovery status."
                previous
            }
            if (next !== previous) previous.closeOwned()
            if (lease.current(generation)) screen = next else next.closeOwned()
        }
    }
    fun readPicture(uri: Uri?) {
        if (uri == null) return
        work("Reading the picture…") {
            val bytes = pictureReader(uri)
            try {
                val text = SetupQr.decode(bytes)
                if (text == null) {
                    TransferScreen.Done(
                        "No setup code found",
                        "Choose a clear, uncropped picture of a Quantum Relay setup code."
                    )
                } else {
                    runCatching { SetupMetadataCodec.decode(text, now()) }.fold(
                        { TransferScreen.Review(it) },
                        {
                            TransferScreen.Done(
                                "Setup code not usable",
                                "This code is invalid or has expired. Ask for a new code; " +
                                    "codes last 10 minutes."
                            )
                        }
                    )
                }
            } finally {
                bytes.fill(0)
            }
        }
    }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
        readPicture(it)
    }
    val pictureDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { readPicture(it) }
    val saveCode = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri ->
        val bitmap = pendingCode
        pendingCode = null
        if (uri != null && bitmap != null) {
            scope.launch {
                notice = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri, "wt")!!.use {
                            it.write(SetupQr.png(bitmap))
                        }
                    }.fold({ "Setup code picture saved." }, { "Could not save the picture." })
                }
            }
        }
    }
    val createArchive = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ARCHIVE_TYPE)
    ) { uri ->
        val passphrase = pendingExport
        val bundle = pendingBundle
        pendingExport = null
        pendingBundle = null
        if (uri == null || passphrase == null || bundle == null) {
            passphrase?.fill(' ')
            return@rememberLauncherForActivityResult
        }
        work("Encrypting your configuration…") {
            withContext(Dispatchers.IO) {
                try {
                    writeExportBundle(context, bundle, uri, passphrase)
                } finally {
                    passphrase.fill(' ')
                }
            }
            bundle.close()
            TransferScreen.Done(
                "Configuration exported",
                "Keep the file and its passphrase separate. Anyone with both can read your " +
                    "private host names, prompts and pictures."
            )
        }
    }
    val openArchive = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            work("Reading the archive…") {
                withContext(Dispatchers.IO) {
                    runCatching {
                        requireNotNull(context.contentResolver.openInputStream(uri)).use {
                            readArchiveInput(context, it)
                        }
                    }.fold({ TransferScreen.Unlock(it) }, {
                        TransferScreen.Done("Could not open this file", ARCHIVE_UNREADABLE)
                    })
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(
            {
                if (screen == TransferScreen.Home) back() else show(TransferScreen.Home)
            },
            modifier = Modifier.bringIntoViewRequester(screenStart),
            enabled = screen !is TransferScreen.Working
        ) { Text(if (screen == TransferScreen.Home) "Back to Settings" else "Back") }
        when (val current = screen) {
            TransferScreen.Home -> {
                SetupImport.pendingPublication?.takeIf {
                    it.pending().isNotEmpty()
                }?.let { publication ->
                    OutlinedButton({
                        show(
                            TransferScreen.Done(
                                "Saved configuration needs sync",
                                "Local settings are saved. Retry the incomplete " +
                                    "metadata publication.",
                                publication
                            )
                        )
                    }) { Text("Retry saved setup sync") }
                }
                TransferHome(
                    blocked,
                    replayProblem,
                    clearReplay = {
                        work("Clearing unreadable setup-code history…") {
                            withContext(Dispatchers.IO) {
                                SetupTransferGate.exclusive {
                                    SetupReplayJournal(stores().directory).clearUnreadable(now())
                                }
                            }
                            TransferScreen.Home
                        }
                    },
                    share = {
                        work("Loading saved connections…") {
                            withContext(Dispatchers.IO) {
                                val transfer = stores()
                                TransferScreen.Share(
                                    transfer.profiles.profiles().filter {
                                        runCatching { SetupEndpoint.from(it) }.isSuccess
                                    },
                                    transfer.catalog.sources().mapNotNull {
                                        runCatching { SetupCatalog.from(it) }.getOrNull()
                                    }
                                )
                            }
                        }
                    },
                    photo = {
                        photo.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    },
                    file = { pictureDocument.launch(arrayOf("image/*")) },
                    export = {
                        work("Collecting your configuration…") {
                            withContext(Dispatchers.IO) {
                                TransferScreen.Export(SetupExport.build(stores()))
                            }
                        }
                    },
                    import = { openArchive.launch(arrayOf("*/*")) },
                    discard = {
                        work("Removing the unreadable record…") {
                            withContext(Dispatchers.IO) {
                                val transfer = stores()
                                SetupImport.discardUnreadableRecord(transfer)
                                blocked = SetupImport.blocked(transfer)
                            }
                            TransferScreen.Home
                        }
                    }
                )
            }
            is TransferScreen.Share -> ShareChooser(current) { title, endpoint, catalog ->
                work("Creating a setup code…") {
                    withContext(Dispatchers.Default) {
                        val metadata = SetupMetadataCodec.create(title, endpoint, catalog, now())
                        val text = SetupMetadataCodec.encode(metadata)
                        TransferScreen.ShareCode(
                            title,
                            text,
                            SetupQr.render(text),
                            metadata.expiresAtEpochSeconds
                        )
                    }
                }
            }
            is TransferScreen.ShareCode -> ShareCode(current, now) {
                pendingCode = current.bitmap
                saveCode.launch("quantum-relay-setup-code.png")
            }
            is TransferScreen.Review -> CodeReview(current.review, now()) { choice ->
                work("Adding the reviewed connection…") {
                    val result = withContext(Dispatchers.IO) {
                        SetupQrImport.addReviewed(stores(), current.review, choice, now())
                    }
                    val pending = result.publication?.await().orEmpty()
                    TransferScreen.Done(
                        when (result.result) {
                            QrAddResult.ADDED -> "Added for review"
                            QrAddResult.ALREADY_SAVED -> "Already saved"
                            QrAddResult.ALREADY_USED -> "Code already used"
                            QrAddResult.EXPIRED -> "Code expired"
                            QrAddResult.UNAVAILABLE -> "Setup code history unreadable"
                        },
                        when (result.result) {
                            QrAddResult.ADDED ->
                                "Only the address and name were saved. Nothing has connected. " +
                                    "Add its access key in Manual watch endpoints or Boxes, then " +
                                    "approve this phone on the Hermes host as usual."
                            QrAddResult.ALREADY_SAVED ->
                                "This connection is already on this phone. Nothing was changed."
                            QrAddResult.ALREADY_USED ->
                                "This phone already added this code. Ask for a new code."
                            QrAddResult.EXPIRED -> "Ask for a new code; codes last 10 minutes."
                            QrAddResult.UNAVAILABLE ->
                                "Nothing was added. Clear the unreadable history first."
                        } +
                            if (pending.isEmpty()) {
                                ""
                            } else {
                                " Retry needed: " +
                                    pending.joinToString(", ") +
                                    "."
                            },
                        result.publication?.takeIf { pending.isNotEmpty() }
                    )
                }
            }
            is TransferScreen.Export -> ExportConfirm(current.bundle.summary) { passphrase ->
                pendingExport = passphrase
                pendingBundle = current.bundle
                createArchive.launch("quantum-relay-configuration.hgmigr")
            }
            is TransferScreen.Unlock -> UnlockArchive { passphrase ->
                work("Unlocking and checking the archive…") {
                    withContext(Dispatchers.IO) {
                        try {
                            openPreview(stores(), current.envelope, passphrase)
                                ?.let { TransferScreen.Preview(it) }
                                ?: TransferScreen.Done(
                                    "Could not open this archive",
                                    ARCHIVE_UNREADABLE
                                )
                        } finally {
                            passphrase.fill(' ')
                        }
                    }
                }
            }
            is TransferScreen.Preview -> ImportReview(current.preview, stores) { selection ->
                work("Importing… keep this screen open.") {
                    val outcome = withContext(Dispatchers.IO) {
                        SetupImport.commit(stores(), current.preview, selection)
                    }
                    current.preview.close()
                    val pending = withContext(Dispatchers.IO) { outcome.publication.await() }
                    TransferScreen.Done(
                        "Configuration imported",
                        buildString {
                            append("Imported items are saved. No access keys were imported; ")
                            append("add keys and approve this phone on each host as usual.")
                            if (outcome.templates > 0) {
                                append(" Widget layouts are ready to choose when you add a ")
                                append("widget from your home screen; Android places widgets, ")
                                append("so imported layouts do not appear by themselves.")
                            }
                            if (pending.isNotEmpty()) {
                                append(" Retry needed: " + pending.joinToString(", ") + ".")
                            }
                        },
                        outcome.publication.takeIf { pending.isNotEmpty() }
                    )
                }
            }
            is TransferScreen.Working -> Text(current.message, Modifier.semantics { heading() })
            is TransferScreen.Done -> {
                Text(
                    current.title,
                    Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(current.message)
                current.publication?.let { previous ->
                    Button({
                        work("Retrying saved metadata publication…") {
                            val publication = withContext(Dispatchers.IO) {
                                SetupImport.retryPublication(stores(), previous)
                            }
                            val pending = withContext(Dispatchers.IO) { publication.await() }
                            TransferScreen.Done(
                                "Saved configuration sync",
                                if (pending.isEmpty()) {
                                    "Saved metadata was published."
                                } else {
                                    "Retry needed: " + pending.joinToString(", ") + "."
                                },
                                publication.takeIf { pending.isNotEmpty() }
                            )
                        }
                    }) { Text("Retry sync") }
                }
                Button({ show(TransferScreen.Home) }) { Text("Done") }
            }
        }
        if (notice.isNotBlank()) Text(notice, color = MaterialTheme.colorScheme.error)
    }
}

/** Builds, encrypts and writes the export entirely on the caller's IO worker. */
internal fun writeArchive(
    context: Context,
    stores: SetupTransferStores,
    uri: Uri,
    passphrase: CharArray
) {
    requireTransferWorker()
    SetupExport.build(stores).use { bundle ->
        writeExportBundle(context, bundle, uri, passphrase)
    }
}

/** Stable private ciphertext only; no passphrase or plaintext is persisted here. */
internal class ArchiveInput(private val file: java.io.File) : AutoCloseable {
    fun open(): java.io.InputStream {
        requireTransferWorker()
        return file.inputStream()
    }
    override fun close() {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            cleanup.execute { file.delete() }
        } else {
            file.delete()
        }
    }
    companion object {
        private val cleanup = java.util.concurrent.Executors.newSingleThreadExecutor { action ->
            Thread(action, "setup-input-cleanup").apply { isDaemon = true }
        }
        private val cleaned = mutableSetOf<String>()
        fun create(context: Context): java.io.File {
            val root = java.io.File(context.cacheDir, "setup-ciphertext")
            check(root.isDirectory || root.mkdirs())
            synchronized(cleaned) {
                if (cleaned.add(root.canonicalPath)) {
                    root.listFiles().orEmpty().forEach {
                        check(it.delete())
                    }
                }
            }
            return java.io.File.createTempFile("envelope-", ".tmp", root)
        }
    }
}

/** Unknown-length SAF input is bounded and frozen privately without an envelope allocation. */
internal fun readArchiveInput(context: Context, input: java.io.InputStream): ArchiveInput {
    requireTransferWorker()
    val file = ArchiveInput.create(context)
    val chunk = ByteArray(64 * 1024)
    try {
        file.outputStream().use { output ->
            var count = 0L
            while (true) {
                if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                val size = input.read(chunk)
                if (size < 0) break
                require(size > 0)
                count += size
                require(count <= MigrationArchive.MAX_ENVELOPE_BYTES)
                output.write(chunk, 0, size)
            }
        }
        return ArchiveInput(file)
    } catch (failure: Exception) {
        file.delete()
        throw failure
    } finally {
        chunk.fill(0)
    }
}

/** Entire ciphertext is authenticated before strict ZIP and bounded native member validation. */
internal fun openPreview(
    stores: SetupTransferStores,
    envelope: ArchiveInput,
    passphrase: CharArray
): ImportPreview? {
    requireTransferWorker()
    return envelope.use { owned ->
        val result = owned.open().use {
            MigrationArchive.readStaged(
                it,
                passphrase,
                java.io.File(stores.context.cacheDir, "setup-archive-stage")
            )
        }
        when (result) {
            is MigrationStagedReadResult.Success -> result.use {
                runCatching { SetupImport.preview(stores, it) }.getOrNull()
            }
            MigrationStagedReadResult.Invalid -> null
        }
    }
}

/** Writes exactly the snapshot whose contents the owner confirmed. */
private fun writeExportBundle(
    context: Context,
    bundle: ExportBundle,
    uri: Uri,
    passphrase: CharArray
) {
    requireTransferWorker()
    context.contentResolver.openOutputStream(uri, "wt")!!.use {
        MigrationArchive.writeEncrypted(bundle.snapshot, passphrase, bundle.artwork, it)
    }
}

/**
 * Opens a privately owned envelope (never shared, never mutated during the call), then
 * validates every picture natively. Returns null for every unreadable archive.
 */
internal fun openPreview(
    stores: SetupTransferStores,
    envelope: ByteArray,
    passphrase: CharArray
): ImportPreview? {
    requireTransferWorker()
    return try {
        val input = envelope.inputStream().use { readArchiveInput(stores.context, it) }
        openPreview(stores, input, passphrase)
    } finally {
        envelope.fill(0)
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
}

@Composable
private fun TransferHome(
    blocked: Boolean,
    replayProblem: Boolean,
    clearReplay: () -> Unit,
    share: () -> Unit,
    photo: () -> Unit,
    file: () -> Unit,
    export: () -> Unit,
    import: () -> Unit,
    discard: () -> Unit
) {
    Text(
        "Move setup",
        Modifier.semantics { heading() },
        style = MaterialTheme.typography.headlineLarge
    )
    Text(
        "Share a connection address, or copy your configuration to another phone. " +
            "Access keys, phone and watch identities and approvals never leave this phone.",
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    SettingsSection {
        Text("Setup code", style = MaterialTheme.typography.titleLarge)
        Text(
            "A setup code holds one address and name for 10 minutes. It is not a password " +
                "and it cannot connect, pair or approve anything."
        )
        Button(share, Modifier.fillMaxWidth()) { Text("Show a setup code") }
        OutlinedButton(photo, Modifier.fillMaxWidth()) { Text("Read a code from a photo") }
        OutlinedButton(file, Modifier.fillMaxWidth()) { Text("Read a code from a file") }
        Text(
            "Take a photo of the code with any camera app, then choose it here. " +
                "This app does not use the camera.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (replayProblem) {
            Text("Setup-code history cannot be read. Codes cannot be added until it is cleared.")
            OutlinedButton(clearReplay, Modifier.fillMaxWidth(), enabled = !blocked) {
                Text("Clear unreadable setup-code history")
            }
        }
    }
    SettingsSection {
        Text("Configuration archive", style = MaterialTheme.typography.titleLarge)
        Text(
            "An encrypted file with your connections, boxes, bots, pictures, animations, " +
                "quick actions, widget layouts and app appearance."
        )
        Button(export, Modifier.fillMaxWidth()) { Text("Export configuration") }
        OutlinedButton(import, Modifier.fillMaxWidth(), enabled = !blocked) {
            Text("Import configuration")
        }
        if (blocked) {
            Text(
                "An earlier import needs recovery. Configuration changes are paused. " +
                    "If its record is unreadable, removing it keeps current values as they are; " +
                    "they may contain only part of that import.",
                color = MaterialTheme.colorScheme.error
            )
            OutlinedButton(discard, Modifier.fillMaxWidth()) { Text("Remove unreadable record") }
        }
    }
}

@Composable
private fun ShareChooser(
    screen: TransferScreen.Share,
    create: (String, SetupEndpoint?, SetupCatalog?) -> Unit
) {
    Heading("Show a setup code")
    Text("Choose one saved connection or box. Its access key is never included.")
    if (screen.endpoints.isEmpty() && screen.sources.isEmpty()) {
        Text("No shareable connections are saved yet.")
    }
    screen.endpoints.forEach { profile ->
        OutlinedButton(
            { create(profile.label, SetupEndpoint.from(profile), null) },
            Modifier.fillMaxWidth()
        ) { Text("Connection: " + profile.label) }
    }
    screen.sources.forEach { source ->
        OutlinedButton(
            { create(source.label, null, source) },
            Modifier.fillMaxWidth()
        ) { Text("Box: " + source.label) }
    }
}

@Composable
private fun ShareCode(screen: TransferScreen.ShareCode, now: () -> Long, save: () -> Unit) {
    var remaining by remember {
        androidx.compose.runtime.mutableLongStateOf(screen.expires - now())
    }
    LaunchedEffect(screen) {
        while (remaining > 0) {
            delay(1000)
            remaining = screen.expires - now()
        }
    }
    Heading("Setup code")
    Text(screen.title, style = MaterialTheme.typography.titleLarge)
    if (remaining > 0) {
        Image(
            screen.bitmap.asImageBitmap(),
            "Setup code for " + screen.title,
            Modifier.fillMaxWidth().widthIn(max = 360.dp)
        )
        Text("Expires in ${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}")
        OutlinedButton(save, Modifier.fillMaxWidth()) { Text("Save code as a picture") }
    } else {
        Text("This code has expired. Go back to make a new one.")
    }
    Text(
        "Anyone who sees this code learns this private address. It contains no access key, " +
            "and the other phone must still be approved on the host.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ConnectionDetails(title: String, preview: SetupConnectionPreview) {
    SettingsSection {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text("Name: " + preview.label)
        Text("Scheme: " + preview.scheme)
        Text("Host: " + preview.host)
        Text("Port: " + preview.port)
        Text("Path: " + preview.path)
        Text("Full address: " + preview.url, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CodeReview(review: UntrustedSetupReview, now: Long, add: (QrAddChoice) -> Unit) {
    var local by remember { mutableStateOf(false) }
    var accepted by remember { mutableStateOf(false) }
    var credential by remember { mutableStateOf("") }
    Heading("Review setup code")
    Text(
        "Anyone can make a setup code. Check every part of the address before adding it; " +
            "do not add it if you do not recognize the host.",
        color = MaterialTheme.colorScheme.error
    )
    Text("Code name: " + review.metadata.label)
    Text("Expires in about ${((review.metadata.expiresAtEpochSeconds - now) / 60) + 1} min")
    review.endpoint?.let { ConnectionDetails("Watch connection", it) }
    review.catalog?.let { ConnectionDetails("Catalog box", it) }
    val cleartext = review.endpoint?.requiresCleartextReview == true
    if (cleartext) {
        Text(
            "This address is not encrypted (ws). Only use it on your own LAN or tailnet.",
            color = MaterialTheme.colorScheme.error
        )
        SettingsToggle("This is my LAN or tailnet", local, true) {
            local = it
            accepted = false
        }
        SettingsToggle("I accept unencrypted traffic", accepted, local) { accepted = it }
    }
    if (review.catalog != null) {
        OutlinedTextField(
            credential,
            { credential = it.take(2048) },
            Modifier.fillMaxWidth(),
            label = { Text("Catalog-only credential for this box") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )
    }
    Button(
        { add(QrAddChoice(local, accepted, credential)) },
        Modifier.fillMaxWidth(),
        enabled = (!cleartext || local && accepted) &&
            (review.catalog == null || credential.isNotBlank())
    ) { Text("Add reviewed address") }
    Text(
        "Adding saves only this address and name. Nothing connects, discovers or pairs.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun PassphraseFields(confirm: Boolean, submit: String, done: (CharArray) -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val normalized = java.text.Normalizer.normalize(first, java.text.Normalizer.Form.NFC)
    val length = normalized.codePointCount(0, normalized.length)
    val wellFormed = Charsets.UTF_8.newEncoder().canEncode(first)
    OutlinedTextField(
        first,
        { first = it.take(MigrationArchive.MAX_PASSPHRASE_CHARS) },
        Modifier.fillMaxWidth(),
        label = { Text("Passphrase") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
    )
    if (confirm) {
        OutlinedTextField(
            second,
            { second = it.take(MigrationArchive.MAX_PASSPHRASE_CHARS) },
            Modifier.fillMaxWidth(),
            label = { Text("Confirm passphrase") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )
        Text(
            when {
                length < MigrationArchive.MIN_PASSPHRASE_CODE_POINTS ->
                    "Use at least 12 characters. A long, unusual passphrase protects the file."
                first != second -> "The passphrases do not match yet."
                else -> "Passphrases match."
            },
            style = MaterialTheme.typography.bodySmall
        )
    }
    Button(
        {
            done(first.toCharArray())
            first = ""
            second = ""
        },
        Modifier.fillMaxWidth(),
        enabled = wellFormed && length >= MigrationArchive.MIN_PASSPHRASE_CODE_POINTS &&
            (!confirm || first == second)
    ) { Text(submit) }
}

@Composable
private fun ExportConfirm(summary: ExportSummary, export: (CharArray) -> Unit) {
    Heading("Export configuration")
    SettingsSection {
        Text("Included", style = MaterialTheme.typography.titleMedium)
        Text("${summary.endpoints} connections (addresses and names)")
        Text("${summary.sources} boxes (addresses and names)")
        Text("${summary.profiles} bots with their order")
        Text("${summary.pictures} pictures and ${summary.animations} animations")
        Text("${summary.actions} quick actions")
        Text("${summary.widgets} home screen widget layouts")
        Text(
            "App appearance: " + when (summary.appearance) {
                dev.quantumink.hermesgadget.client.Appearance.LIGHT -> "Light"
                dev.quantumink.hermesgadget.client.Appearance.DARK -> "Dark"
                dev.quantumink.hermesgadget.client.Appearance.MOONLIGHT -> "Electronic Moonlight"
            }
        )
        if (summary.skipped >
            0
        ) {
            Text("${summary.skipped} items cannot be exported and are left out.")
        }
        if (summary.omittedWidgets > 0) {
            Text(
                "${summary.omittedWidgets} widget layouts are left out because " +
                    "one export can contain at most 128 layouts."
            )
        }
    }
    SettingsSection {
        Text("Never included", style = MaterialTheme.typography.titleMedium)
        Text(
            "Access keys and catalog credentials, phone and watch identities and pairing, " +
                "speech provider keys, cleartext network consent, conversations, tasks, " +
                "recordings, approvals, session receipts, watch appearance and where widgets " +
                "sit on your home screen."
        )
    }
    Text(
        "The file contains private host names, prompts and pictures, so it is encrypted with " +
            "your passphrase. Its safety depends on that passphrase; it is not stored anywhere."
    )
    PassphraseFields(confirm = true, submit = "Choose where to save", done = export)
}

@Composable
private fun UnlockArchive(open: (CharArray) -> Unit) {
    Heading("Unlock archive")
    Text("Enter the passphrase used for this export. Nothing changes until you confirm the import.")
    PassphraseFields(confirm = false, submit = "Unlock and review", done = open)
}

@Composable
private fun CheckRow(
    title: String,
    detail: String?,
    checked: Boolean,
    enabled: Boolean,
    change: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, enabled, Role.Checkbox, onValueChange = change)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked, null, enabled = enabled)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(title)
            detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun KeepOrImport(keep: String, use: String, imported: Boolean, change: (Boolean) -> Unit) {
    Column(Modifier.selectableGroup().padding(start = 12.dp)) {
        listOf(false to keep, true to use).forEach { (value, label) ->
            Row(
                Modifier.fillMaxWidth().selectable(imported == value, role = Role.RadioButton) {
                    change(value)
                }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(imported == value, null)
                Text(label, Modifier.padding(start = 12.dp))
            }
        }
    }
}

@Composable
internal fun ImportReview(
    preview: ImportPreview,
    stores: () -> SetupTransferStores,
    commit: (ImportSelection) -> Unit
) {
    var selection by remember(preview) { mutableStateOf(preview.defaults()) }
    var problems by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(selection) {
        problems = withContext(Dispatchers.IO) {
            runCatching { SetupImport.plan(stores(), preview, selection) }.fold(
                { emptyList() },
                {
                    (it as? ImportPlanException)?.problems
                        ?: listOf("These choices cannot be applied.")
                }
            )
        }
    }
    Heading("Review import")
    Text(
        "Nothing has changed yet. Existing items stay as they are unless you choose otherwise. " +
            "No access keys, identities or approvals are in this file."
    )
    if (preview.endpoints.isNotEmpty()) {
        SettingsSection {
            Text("Connections", style = MaterialTheme.typography.titleMedium)
            preview.endpoints.forEach { item ->
                val choice = selection.endpoints.getValue(item.setup.url)
                fun set(value: EndpointChoice) {
                    selection =
                        selection.copy(endpoints = selection.endpoints + (item.setup.url to value))
                }
                if (item.existing != null) {
                    Text(item.existing.label + " — already on this phone")
                    Text(item.setup.url, style = MaterialTheme.typography.bodySmall)
                    KeepOrImport(
                        "Keep this phone's name and transport",
                        "Use \"${item.setup.label}\" and ${item.setup.transport.name}",
                        choice.update
                    ) { set(choice.copy(update = it)) }
                } else {
                    CheckRow(
                        item.setup.label,
                        item.setup.url,
                        choice.include,
                        !item.cleartext || choice.cleartextAccepted
                    ) {
                        set(choice.copy(include = it))
                    }
                    if (item.cleartext) {
                        Text(
                            "Unencrypted (ws). Earlier consent is not copied; " +
                                "confirm again to import.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                        SettingsToggle("This is my LAN or tailnet", choice.privateNetwork, true) {
                            set(
                                choice.copy(
                                    privateNetwork = it,
                                    cleartextAccepted = false,
                                    include = false
                                )
                            )
                        }
                        SettingsToggle(
                            "I accept unencrypted traffic",
                            choice.cleartextAccepted,
                            choice.privateNetwork
                        ) {
                            set(choice.copy(cleartextAccepted = it, include = it))
                        }
                    }
                }
            }
        }
    }
    if (preview.sources.isNotEmpty()) {
        SettingsSection {
            Text("Boxes", style = MaterialTheme.typography.titleMedium)
            preview.sources.forEach { item ->
                val choice = selection.sources.getValue(item.catalog.id)
                fun set(value: SourceChoice) {
                    selection =
                        selection.copy(sources = selection.sources + (item.catalog.id to value))
                }
                Text(item.catalog.label)
                Text(item.catalog.url, style = MaterialTheme.typography.bodySmall)
                if (item.merged) {
                    Text("Already on this phone; its bots merge into that box.")
                    KeepOrImport(
                        "Keep this phone's box name",
                        "Use \"${item.catalog.label}\"",
                        choice.updateLabel
                    ) {
                        set(choice.copy(updateLabel = it))
                    }
                } else {
                    OutlinedTextField(
                        choice.credential,
                        { set(choice.copy(credential = it.take(2048), include = it.isNotBlank())) },
                        Modifier.fillMaxWidth(),
                        label = { Text("Catalog-only credential (required)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                    Text(
                        "Credentials are never exported. Enter this box's key to import it " +
                            "and its bots.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
    if (preview.profiles.isNotEmpty()) {
        SettingsSection {
            Text("Bots and pictures", style = MaterialTheme.typography.titleMedium)
            preview.profiles.forEach { item ->
                val choice = selection.profiles.getValue(item.profile.key)
                fun set(value: ProfileChoice) {
                    selection =
                        selection.copy(profiles = selection.profiles + (item.profile.key to value))
                }
                val detail = "${item.pictures} pictures" +
                    if (item.endpointUrl != null) " · saved connection" else " · box bot"
                when {
                    item.blocked -> Text(item.profile.name + " — already listed on this phone")
                    item.existing -> {
                        Text(item.profile.name + " — already on this phone")
                        if (item.pictures > 0) {
                            KeepOrImport(
                                "Keep current pictures",
                                "Use imported pictures",
                                choice.usePictures
                            ) {
                                set(choice.copy(usePictures = it))
                            }
                        }
                    }
                    else -> CheckRow(item.profile.name, detail, choice.include, true) {
                        set(choice.copy(include = it))
                    }
                }
            }
            Text(
                "A bot is imported only with its connection or box.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
    if (preview.actions.isNotEmpty()) {
        SettingsSection {
            Text("Quick actions", style = MaterialTheme.typography.titleMedium)
            preview.actions.forEach { item ->
                val choice = selection.actions.getValue(item.action.id)
                fun set(value: ItemChoice) {
                    selection =
                        selection.copy(actions = selection.actions + (item.action.id to value))
                }
                when {
                    item.identical(preview.effectiveKey(item.action.profileKey, selection)) ->
                        Text(item.action.title + " — already on this phone")
                    item.existing != null -> {
                        Text(item.existing.title + " — a different version is on this phone")
                        Text("This phone: " + preview.recipient(item.existing.profileKey))
                        Text(
                            "Imported: " +
                                preview.recipient(
                                    preview.effectiveKey(item.action.profileKey, selection)
                                )
                        )
                        KeepOrImport(
                            "Keep this phone's action",
                            "Use \"${item.action.title}\"",
                            choice.replace
                        ) {
                            set(choice.copy(replace = it))
                        }
                    }
                    else -> CheckRow(item.action.title, null, choice.include, true) {
                        set(choice.copy(include = it))
                    }
                }
            }
        }
    }
    if (preview.templates.isNotEmpty()) {
        SettingsSection {
            Text("Widget layouts", style = MaterialTheme.typography.titleMedium)
            preview.templates.forEach { item ->
                val choice = selection.templates.getValue(item.widget.id)
                val preset = dev.quantumink.hermesgadget.mobile.widgets.HomeWidgetPreset
                    .valueOf(item.widget.preset.name)
                fun set(value: ItemChoice) {
                    selection =
                        selection.copy(templates = selection.templates + (item.widget.id to value))
                }
                if (item.existing != null) {
                    Text(preset.label + " — already saved")
                    KeepOrImport("Keep saved layout", "Use imported layout", choice.replace) {
                        set(choice.copy(replace = it))
                    }
                } else {
                    CheckRow(
                        preset.label,
                        "${item.widget.slots.count { it != null }} of ${preset.capacity} bots",
                        choice.include,
                        true
                    ) { set(choice.copy(include = it)) }
                }
            }
            Text(
                "Layouts are saved for later. Android decides where widgets go: add a widget " +
                    "from " +
                    "your home screen and choose a saved layout there.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
    SettingsSection {
        Text("App appearance", style = MaterialTheme.typography.titleMedium)
        KeepOrImport(
            "Keep this phone's appearance",
            "Use the imported appearance",
            selection.useAppearance
        ) {
            selection = selection.copy(useAppearance = it)
        }
    }
    problems.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(
        { commit(selection) },
        Modifier.fillMaxWidth(),
        enabled = problems.isEmpty()
    ) { Text("Import selected items") }
}
