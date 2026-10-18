package dev.quantumink.hermesgadget.protocol

import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** User content only: no enrollment, provider configuration, URLs or identity fields. */
data class QuickAction(
    val id: String,
    val profileKey: String?,
    val title: String,
    val template: String
) {
    init {
        require(QuickActions.validId(id)) { "Invalid action ID." }
        require(
            profileKey == null ||
                profileKey.matches(Regex("[A-Za-z0-9_-]{1,64}:[A-Za-z0-9_-]{1,64}"))
        ) {
            "Choose a catalog profile."
        }
        require(
            title.isNotBlank() && title.length <= 64 && title.none {
                it.isISOControl()
            }
        ) { "Title must be 1–64 characters." }
        require(template.isNotBlank() && template.length <= 4096) {
            "Prompt must be 1–4096 characters."
        }
        QuickActions.inputs(template)
    }
    override fun toString(): String = "QuickAction(content=redacted)"
}

object QuickActions {
    const val MAX_ACTIONS = 32
    const val MAX_BYTES = 262144
    private val placeholder = Regex("\\{\\{([a-z][a-z0-9_]{0,31})\\}\\}")
    fun validId(value: String): Boolean =
        value.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))

    fun inputs(template: String): List<String> {
        require(template.length <= 4096)
        val remaining = placeholder.replace(template, "")
        require("{{" !in remaining && "}}" !in remaining) {
            "Use inputs like {{topic}} with lowercase names."
        }
        return placeholder.findAll(template).map { it.groupValues[1] }.distinct().toList().also {
            require(it.size <= 8) { "Use at most eight inputs." }
        }
    }

    fun expand(action: QuickAction, values: Map<String, String>): String {
        require(action.profileKey != null) { "Choose a profile before reviewing this action." }
        val required = inputs(action.template)
        require(values.keys == required.toSet()) { "Complete the required inputs." }
        require(
            values.values.all {
                it.isNotBlank() && it.length <= 8192
            }
        ) { "Complete each input (at most 8192 characters)." }
        var length = action.template.length
        placeholder.findAll(action.template).forEach {
            length += values.getValue(it.groupValues[1]).length - it.value.length
            require(length <= 8192) { "Expanded prompt exceeds 8192 characters." }
        }
        return placeholder.replace(action.template) { values.getValue(it.groupValues[1]) }.also {
            require(it.length <= 8192)
        }
    }

    /** Names and list positions never participate in routing. Offline profiles remain selectable. */
    fun resolve(action: QuickAction, pages: List<CatalogPage>): CatalogPage? =
        pages.singleOrNull { it.key == action.profileKey }?.takeUnless {
            it.status ==
                CatalogStatus.WITHDRAWN
        }

    fun encode(actions: List<QuickAction>): String {
        require(
            actions.size <= MAX_ACTIONS && actions.map {
                it.id
            }.distinct().size == actions.size
        )
        return Message.create(
            "quick_actions",
            buildJsonObject {
                put("version", 1)
                put(
                    "actions",
                    JsonArray(
                        actions.map { action ->
                            buildJsonObject {
                                put("id", action.id)
                                put("profile", action.profileKey?.let(::JsonPrimitive) ?: JsonNull)
                                put("title", action.title)
                                put("template", action.template)
                            }
                        }
                    )
                )
            }
        ).encode().also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) }
    }

    fun decode(text: String): List<QuickAction> {
        require(text.length <= MAX_BYTES && text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val envelope = requireNotNull(Message.parse(text))
        require(envelope.type == "quick_actions" && envelope.int("version") == 1)
        val items = envelope.field("actions") as? JsonArray ?: error("Missing actions.")
        require(items.size <= MAX_ACTIONS)
        val result = items.map { element ->
            val fields = element as? JsonObject ?: error("Invalid action.")
            require(fields.keys == setOf("id", "profile", "title", "template"))
            fun string(key: String): String = requireNotNull(
                (fields[key] as? JsonPrimitive)?.takeIf {
                    it.isString
                }?.content
            )
            QuickAction(
                string("id"),
                if (fields["profile"] ==
                    JsonNull
                ) {
                    null
                } else {
                    string("profile")
                },
                string("title"),
                string("template")
            )
        }
        encode(result) // Applies aggregate and duplicate bounds too.
        return result
    }

    fun seeds(): List<QuickAction> = listOf(
        "Morning briefing" to
            "Prepare a concise morning briefing focused on {{priorities}}. Use only information available to you; identify anything you cannot check.",
        "Check builds" to
            "Review build status for {{project}} using the context or tools available to you. Report evidence, failures and the next useful step; say when build data is unavailable.",
        "Capture a thought" to
            "Help me capture and organize this thought: {{thought}}. Suggest a clear next step; do not claim it has been saved in another app.",
        "Summarize shared content" to
            "Summarize the following pasted content, keeping key facts and open questions: {{content}}",
        "Troubleshoot an issue" to
            "Help troubleshoot this issue: {{issue}}. Context and evidence: {{context}}. Separate observations from hypotheses and suggest a focused next check.",
        "Follow up a task" to
            "Help me follow up this task: {{task}}. Latest context: {{context}}. Summarize known progress and propose the next action without claiming access to a task service."
    ).map { (title, template) -> QuickAction(UUID.randomUUID().toString(), null, title, template) }
}
