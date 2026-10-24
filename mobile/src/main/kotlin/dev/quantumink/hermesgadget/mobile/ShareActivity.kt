package dev.quantumink.hermesgadget.mobile

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle

/** Exported sharesheet target. It only bounds the share and opens the review; it never sends or selects a profile. */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val content = runCatching { ShareHandoff.read(intent) }
                .getOrDefault(SharedContent.Rejected(ShareRejection.UNREADABLE))
            startActivity(ShareHandoff.review(this, ShareHandoff.store.put(content)))
        }
        finish()
    }
}

internal object ShareHandoff {
    const val ACTION_REVIEW = "dev.quantumink.hermesgadget.REVIEW_SHARE"
    private const val EXTRA_TOKEN = "dev.quantumink.hermesgadget.extra.SHARE_TOKEN"

    /** Process-scoped: after process death the draft is gone and the review says so. */
    val store = ShareHandoffStore()

    fun read(intent: Intent): SharedContent = ShareIntake.read(
        intent.action,
        intent.type,
        intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT),
        intent.getCharSequenceExtra(Intent.EXTRA_TEXT),
        intent.hasExtra(Intent.EXTRA_STREAM)
    )

    fun review(context: Context, token: String): Intent =
        Intent(context, MainActivity::class.java).setAction(ACTION_REVIEW)
            .putExtra(EXTRA_TOKEN, token)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )

    /** The token only names in-process content; an unknown token (another app's intent, process death) shows nothing. */
    fun token(intent: Intent): String =
        runCatching { intent.getStringExtra(EXTRA_TOKEN) }.getOrNull().orEmpty().take(64)
}
