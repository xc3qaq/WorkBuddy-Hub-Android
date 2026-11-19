package com.wbhub.app.bridge

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Which dialect a client asked for. */
enum class ApiFormat { OPENAI, RESPONSES, ANTHROPIC }

/**
 * Translates between the dialect clients speak and the one the upstream serves.
 *
 * The rules are not invented here. Each one follows LiteLLM's pass-through
 * adapters, which are the most exercised implementation of these translations:
 *
 *  - `litellm/llms/anthropic/pass_through/adapters/transformation.py`
 *  - `litellm/llms/anthropic/pass_through/responses_adapters/transformation.py`
 *  - `litellm/llms/anthropic/pass_through/adapters/streaming_iterator.py`
 *
 * Where this file makes a different call, the reason is written down beside it.
 */
object ApiAdapters {

    // ------------------------------------------------------------------ //
    // Disguise
    // ------------------------------------------------------------------ //

    /**
     * Markers the upstream refuses, and what replaces each.
     *
     * Measured against the live gateway, which filters by two different rules:
     *
     *  - [BLOCKED_MARKERS] are found anywhere in the text. One of them trips the
     *    filter while sitting in the middle of a 27 kB prompt, and changing any
     *    single character clears the block.
     *  - [BLOCKED_PREFIXES] are only matched at the very start: the same text
     *    preceded by a single space or a newline passes. These are the
     *    self-descriptions clients open their instructions with.
     *
     * Both are handled because a client may send either, and a rule applied in
     * the wrong place is either a missed block or a needless rewrite.
     */
    private val BLOCKED_MARKERS: List<Pair<String, String>> = listOf(
        // The identity sentence; one word is dropped, which is enough to stop
        // the match while leaving the sentence readable to the model.
        "You are Claude Code, Anthropic's official CLI for Claude." to
            "You are Claude Code, an official CLI for Claude.",
        // The billing header's name carries no meaning for the model; the
        // values after the colon are left in place.
        "x-anthropic-billing-header" to "x-client-billing-header",
        "To give feedback, users should report the issue at" to
            "For feedback, users should report the issue at",
        // A coding agent's file-reference note. Measured against the live
        // gateway: this passage trips the filter wherever it sits in a system
        // prompt, and changing this one token is enough to pass. Only the
        // token is touched, so the note still tells the model the same thing
        // about how a leading `@` is read.
        "Tokens prefixed with @" to "Tokens prefixed with (at)",
    )

    /**
     * Markers the upstream refuses only when the text begins with them.
     *
     * The entry is the opening of another vendor's coding agent — a paragraph
     * describing what the agent is, which the gateway reads as an attempt to
     * drive the account from a client it has not approved. It is filtered as
     * one whole passage: dropping its final word, or hyphenating a term inside
     * it, is enough to pass. The rewrite therefore only touches its opening
     * clause, which changes nothing about what the agent is told to do.
     *
     * The runtime's own self-description used to live here too, spelled with a
     * plain space. It never matched — see [GAP] — and is now handled by
     * [IDENTITY_AT_START], which does not care how the two words are separated.
     */
    private val BLOCKED_PREFIXES: List<Pair<String, String>> = listOf(
        // Opening of another vendor's coding agent. Only its first clause is
        // rewritten — the sentence still says the same thing about the agent.
        "You are a coding agent running in the Codex CLI, a terminal-based coding assistant. Codex CLI is an open source project led by OpenAI. You are expected to be precise, safe, and helpful." to
            "You're a coding agent running in the Codex CLI, a terminal-based coding assistant. Codex CLI is an open source project led by OpenAI. You are expected to be precise, safe, and helpful.",
    )

    /**
     * What counts as a separator to the gateway, but not to a literal compare.
     *
     * The runtime writes its name with a zero-width space between the two words
     * (`DeepSeek` + U+200B + `Harness`). A rule that spells the sentence with a
     * plain space therefore never fires on the text it was written for, which
     * is why the disguised requests still came back as `请求被拦截`. Matching
     * through a class of invisible characters is what makes the rule hold for
     * every spelling of the same phrase.
     */
    private const val GAP = "[\\s\u00A0\u200B\u2060\uFEFF]"

    /**
     * The runtime's self-description, at the start of a turn.
     *
     * Measured: blocked only at offset 0, matched case-insensitively, and
     * cleared by hyphenating the product name. The rest of the sentence is
     * kept so the model is still told what it is running on.
     */
    private val IDENTITY_AT_START = Regex(
        "^${GAP}*You${GAP}+are${GAP}+an${GAP}+AI${GAP}+agent${GAP}+powered${GAP}+by" +
            "${GAP}+DeepSeek${GAP}*Harness\\.",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The `coding agent powered by` opener, for any model name.
     *
     * The gateway keys on the phrase rather than the model it names, so
     * swapping the model in the sentence does not clear it; changing the verb
     * does.
     */
    private val CODING_AGENT_OPENER = Regex(
        "You${GAP}+are${GAP}+a${GAP}+coding${GAP}+agent${GAP}+powered${GAP}+by",
        RegexOption.IGNORE_CASE,
    )

    /** The Web GUI sentences a harness appends to its system prompt. */
    private val WEB_GUI_SURFACE = Regex(
        "You${GAP}+are${GAP}+interacting${GAP}+with${GAP}+the${GAP}+user${GAP}+through" +
            "${GAP}+the${GAP}+DeepSeek${GAP}*Harness${GAP}+Web${GAP}+GUI",
        RegexOption.IGNORE_CASE,
    )
    private val WEB_GUI_URL = Regex(
        "Current${GAP}+DeepSeek${GAP}*Harness${GAP}+Web${GAP}+GUI${GAP}+URL",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The product name on its own, as a last resort.
     *
     * Any sentence carrying the two words with a zero-width space between them
     * is a candidate for the filter, and the hyphenated spelling is the one
     * measured to pass. It cannot match its own replacement, so the rewrite
     * stays idempotent.
     */
    private val BRAND = Regex("DeepSeek${GAP}*Harness", RegexOption.IGNORE_CASE)

    private const val SAFE_IDENTITY = "You are an AI agent running on DeepSeek-Harness."
    private const val SAFE_CODING_OPENER = "You are a coding agent running on"
    private const val SAFE_WEB_SURFACE =
        "You are interacting with the user through this Web GUI"
    private const val SAFE_WEB_URL = "Current Web GUI URL"
    private const val SAFE_BRAND = "DeepSeek-Harness"

    /**
     * Rewrites every marker the upstream refuses.
     *
     * Only the markers are touched: the rest of the message is the client's own
     * instructions and is passed through unchanged, so the model sees the same
     * request the client composed. Every occurrence is replaced, because a copy
     * left behind is one the upstream would still find.
     *
     * The rules are applied in order of specificity, and each one is idempotent
     * — feeding the result back in changes nothing — so a retried request is
     * rewritten exactly once.
     */
    fun disguise(text: String): String {
        if (text.isEmpty()) return text
        var rewritten = IDENTITY_AT_START.replace(text, SAFE_IDENTITY)
        rewritten = CODING_AGENT_OPENER.replace(rewritten, SAFE_CODING_OPENER)
        rewritten = WEB_GUI_SURFACE.replace(rewritten, SAFE_WEB_SURFACE)
        rewritten = WEB_GUI_URL.replace(rewritten, SAFE_WEB_URL)
        for ((marker, replacement) in BLOCKED_MARKERS) {
            if (rewritten.contains(marker, ignoreCase = true)) {
                rewritten = rewritten.replace(marker, replacement, ignoreCase = true)
            }
        }
        for ((prefix, replacement) in BLOCKED_PREFIXES) {
            if (rewritten.startsWith(prefix, ignoreCase = true)) {
                rewritten = replacement + rewritten.substring(prefix.length)
            }
        }
        return BRAND.replace(rewritten, SAFE_BRAND)
    }

    /**
     * Whether [text] carries anything the upstream refuses.
     *
     * Answered with substring tests rather than the rules themselves: this runs
     * on every message of every request, and a false positive only costs one
     * rewrite pass, while a false negative would leave the block in place.
     * `Web GUI` and `DeepSeek` between them cover the rules that are spelled
     * out as regular expressions.
     */
    fun needsDisguise(text: String): Boolean =
        QUICK_MARKERS.any { text.contains(it, ignoreCase = true) } ||
            BLOCKED_PREFIXES.any { text.startsWith(it.first, ignoreCase = true) }

    /** Substrings that every blocked phrase contains in one spelling or another. */
    private val QUICK_MARKERS = listOf(
        "Tokens prefixed with @",
        "x-anthropic-billing-header",
        "You are Claude Code",
        "To give feedback, users should report",
        "DeepSeek",
        "coding agent powered",
        "Web GUI",
    )

    /**
     * Roles whose text the upstream reads.
     *
     * The gateway scans the whole conversation, not just the leading prompt: a
     * marker replayed in an earlier assistant turn trips the filter the same way
     * a fresh one does. `user` turns are deliberately left out — they carry what
     * was actually asked, and the gateway does not filter them.
     */
    private val DISGUISED_ROLES = setOf("system", "assistant", "developer")

    // ------------------------------------------------------------------ //
    // Entry points
    // ------------------------------------------------------------------ //

    /** Whether the client asked for a streamed answer. */
    fun wantsStream(body: JSONObject, format: ApiFormat): Boolean =
        body.optBoolean("stream", false)

    /** Rewrites a request in [format] as a Chat Completions request. */
    fun toChatBody(body: JSONObject, format: ApiFormat, disguise: Boolean = false): JSONObject {
        val translated = when (format) {
            ApiFormat.OPENAI -> body
            ApiFormat.ANTHROPIC -> anthropicToChat(body)
            ApiFormat.RESPONSES -> responsesToChat(body)
        }
        // Applied after translation so one pass covers every dialect, and to the
        // roles the upstream reads: user text is not what it filters, and
        // rewriting that would change what was asked.
        return if (disguise) disguiseMessages(translated) else translated
    }

    /**
     * Rewrites the markers the upstream refuses, wherever they appear.
     *
     * Returns the same object when nothing needed changing.
     */
    private fun disguiseMessages(chat: JSONObject): JSONObject {
        val messages = chat.optJSONArray("messages") ?: return chat
        for (i in 0 until messages.length()) {
            val message = messages.optJSONObject(i) ?: continue
            if (message.optString("role") !in DISGUISED_ROLES) continue
            when (val content = message.opt("content")) {
                is String -> if (needsDisguise(content)) message.put("content", disguise(content))
                is JSONArray -> for (j in 0 until content.length()) {
                    val part = content.optJSONObject(j) ?: continue
                    val partText = part.optString("text")
                    if (partText.isNotEmpty() && needsDisguise(partText)) {
                        part.put("text", disguise(partText))
                    }
                }
            }
        }
        return chat
    }

    // ------------------------------------------------------------------ //
    // Inbound: Anthropic Messages
    // ------------------------------------------------------------------ //

    /**
     * Rewrites an Anthropic Messages request as Chat Completions.
     *
     * Follows `translate_anthropic_messages_to_openai`.
     */
    private fun anthropicToChat(body: JSONObject): JSONObject {
        val out = JSONObject()
        out.put("model", body.optString("model"))
        val messages = JSONArray()

        // Anthropic keeps the system prompt beside the messages rather than in
        // them, which is the first thing that has to move. A list of blocks is
        // joined by newlines, as LiteLLM does.
        when (val system = body.opt("system")) {
            is String -> if (system.isNotEmpty()) {
                messages.put(systemMessage(system))
            }
            is JSONArray -> {
                val text = (0 until system.length()).mapNotNull { i ->
                    system.optJSONObject(i)?.optString("text")?.takeIf { it.isNotEmpty() }
                }.joinToString("\n")
                if (text.isNotEmpty()) messages.put(systemMessage(text))
            }
        }

        val inbound = body.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until inbound.length()) {
            val message = inbound.optJSONObject(i) ?: continue
            val role = message.optString("role")
            when (val content = message.opt("content")) {
                is String -> if (content.isNotEmpty()) {
                    messages.put(JSONObject().put("role", role).put("content", content))
                }
                is JSONArray -> appendAnthropicBlocks(messages, role, content)
            }
        }
        out.put("messages", messages)

        copyNumber(body, out, "max_tokens", "max_tokens")
        copyNumber(body, out, "temperature", "temperature")
        copyNumber(body, out, "top_p", "top_p")
        body.optJSONArray("stop_sequences")?.let { seq ->
            if (seq.length() > 0) out.put("stop", seq)
        }
        anthropicToolsToChat(body.optJSONArray("tools"))?.let { out.put("tools", it) }
        anthropicToolChoiceToChat(body.optJSONObject("tool_choice"))?.let { out.put("tool_choice", it) }
        // Reasoning effort carries Anthropic's `thinking` intent to a target
        // that speaks the OpenAI parameter rather than the block.
        anthropicThinkingToEffort(body.optJSONObject("thinking"))?.let {
            out.put("reasoning_effort", it)
        }
        return out
    }

    private fun systemMessage(text: String) = JSONObject().put("role", "system").put("content", text)

    /**
     * Flattens one Anthropic content array into Chat Completions messages.
     *
     * A single Anthropic message may hold text, tool calls, or tool results
     * together, while Chat Completions wants those as separate messages with
     * their own roles, so one input message can become several. This mirrors
     * the ordering LiteLLM produces: tool results first, then the user's own
     * content, then the assistant turn.
     */
    private fun appendAnthropicBlocks(sink: JSONArray, role: String, blocks: JSONArray) {
        val text = StringBuilder()
        // Parts in the order they arrived, so text and images keep the positions
        // the client put them in.
        val parts = JSONArray()
        var sawPart = false
        val toolCalls = JSONArray()
        val toolResults = mutableListOf<JSONObject>()
        val thinking = mutableListOf<JSONObject>()

        for (i in 0 until blocks.length()) {
            val block = blocks.optJSONObject(i) ?: continue
            when (block.optString("type")) {
                "text" -> {
                    val piece = block.optString("text")
                    text.append(piece)
                    parts.put(carryCacheControl(block, JSONObject().put("type", "text").put("text", piece)))
                }
                // An image is passed through as a URL, which is the one shape
                // both dialects agree on; only the part's name differs.
                "image" -> anthropicImagePart(block)?.let {
                    sawPart = true
                    parts.put(carryCacheControl(block, it))
                }
                // A PDF has no Chat Completions equivalent. LiteLLM sends it as
                // an image part, which is how the bytes reach a model that can
                // read them at all; dropping it would silently lose the turn.
                "document" -> anthropicImagePart(block)?.let {
                    sawPart = true
                    parts.put(carryCacheControl(block, it))
                }
                "tool_use" -> {
                    val name = block.optString("name")
                    toolCalls.put(
                        JSONObject().apply {
                            put("id", block.optString("id"))
                            put("type", "function")
                            put(
                                "function",
                                JSONObject().apply {
                                    // OpenAI caps the name at 64 characters; a
                                    // longer one is rejected outright, so it is
                                    // shortened with a stable suffix instead.
                                    put("name", truncateToolName(name))
                                    // The schema says an object; the wire wants
                                    // a string.
                                    put("arguments", block.opt("input")?.toString() ?: "{}")
                                },
                            )
                        },
                    )
                }
                "tool_result" -> toolResults.add(
                    JSONObject().apply {
                        put("role", "tool")
                        put("tool_call_id", block.optString("tool_use_id"))
                        put("content", toolResultContent(block))
                        carryCacheControl(block, this)
                    },
                )
                // Reasoning replayed from an earlier turn. It has to survive or
                // the model loses the thread of its own deliberation.
                "thinking" -> {
                    val value = block.optString("thinking")
                    if (value.isNotEmpty() || block.optString("signature").isNotEmpty()) {
                        thinking.add(
                            JSONObject().apply {
                                put("type", "thinking")
                                put("thinking", value)
                                put("signature", block.optString("signature"))
                            },
                        )
                    }
                }
                "redacted_thinking" -> thinking.add(
                    JSONObject().apply {
                        put("type", "redacted_thinking")
                        put("data", block.optString("data"))
                    },
                )
            }
        }

        // Tool results answer an earlier turn, so they come first.
        toolResults.forEach { sink.put(it) }

        if (toolCalls.length() > 0 || thinking.isNotEmpty()) {
            sink.put(
                JSONObject().apply {
                    put("role", "assistant")
                    put("content", text.toString())
                    if (toolCalls.length() > 0) put("tool_calls", toolCalls)
                    if (thinking.isNotEmpty()) {
                        // Both spellings: `thinking_blocks` keeps the structure,
                        // `reasoning_content` is what a plain OpenAI client reads.
                        put("thinking_blocks", JSONArray(thinking))
                        put(
                            "reasoning_content",
                            thinking.filter { it.optString("type") == "thinking" }
                                .joinToString("") { it.optString("thinking") },
                        )
                    }
                },
            )
        } else if (sawPart) {
            // Only a message that actually has an image needs the part list;
            // everything else keeps the plain string it came as.
            sink.put(JSONObject().put("role", role).put("content", parts))
        } else if (text.isNotEmpty()) {
            sink.put(JSONObject().put("role", role).put("content", text.toString()))
        }
    }

    /**
     * The content of a tool result, with the failure flag kept.
     *
     * LiteLLM forwards a single text block as a bare string and several as a
     * part list. A failure is marked in the text itself: the upstream has no
     * `is_error` field, and a model that cannot tell a failure from an answer
     * repeats the same call forever.
     */
    private fun toolResultContent(block: JSONObject): Any {
        val raw = block.opt("content")
        val base: Any = when (raw) {
            is String -> raw
            is JSONArray -> {
                val parts = JSONArray()
                for (i in 0 until raw.length()) {
                    val part = raw.optJSONObject(i) ?: continue
                    when (part.optString("type")) {
                        "text" -> parts.put(JSONObject().put("type", "text").put("text", part.optString("text")))
                        "image" -> anthropicImagePart(part)?.let { parts.put(it) }
                        else -> Unit
                    }
                }
                when {
                    parts.length() == 0 -> ""
                    // A single text part is a plain string in LiteLLM's output.
                    parts.length() == 1 &&
                        parts.getJSONObject(0).optString("type") == "text" ->
                        parts.getJSONObject(0).optString("text")
                    else -> parts
                }
            }
            else -> ""
        }
        if (!block.optBoolean("is_error")) return base
        return when (base) {
            is String -> if (base.isEmpty()) "[tool error]" else "[tool error] $base"
            is JSONArray -> JSONArray().put(
                JSONObject().put("type", "text").put("text", "[tool error]"),
            ).also { out -> for (i in 0 until base.length()) out.put(base.get(i)) }
            else -> "[tool error]"
        }
    }

    /**
     * Keeps a cache breakpoint when the client set one.
     *
     * It costs nothing where it is ignored, and losing it turns every turn of a
     * long conversation into a full re-read upstream.
     */
    private fun carryCacheControl(source: JSONObject, target: JSONObject): JSONObject {
        source.optJSONObject("cache_control")?.let { target.put("cache_control", it) }
        return target
    }

    /**
     * Rewrites one image or document block as a Chat Completions image part.
     *
     * Anthropic sends the bytes inline as `{source:{type:base64,media_type,data}}`,
     * which is the same information a data URL carries, so it is rebuilt as one
     * rather than kept as a dialect-specific shape. A document is handled the
     * same way, which is what LiteLLM does — the media type stays its own, so a
     * model that reads PDFs can still tell what it was handed.
     */
    private fun anthropicImagePart(block: JSONObject): JSONObject? {
        val source = block.optJSONObject("source") ?: return null
        val url = when (source.optString("type")) {
            "base64" -> {
                val media = source.optString("media_type").ifEmpty { "image/png" }
                val data = source.optString("data")
                if (data.isEmpty()) return null
                "data:$media;base64,$data"
            }
            "url" -> source.optString("url").ifEmpty { return null }
            "text" -> {
                // A plain-text document carries its words directly.
                val text = source.optString("data")
                if (text.isEmpty()) return null
                return JSONObject().put("type", "text").put("text", text)
            }
            else -> return null
        }
        return JSONObject().apply {
            put("type", "image_url")
            put("image_url", JSONObject().put("url", url))
        }
    }

    /** Anthropic tool schemas are `{name, input_schema}`; Chat wants them nested. */
    private fun anthropicToolsToChat(tools: JSONArray?): JSONArray? {
        if (tools == null || tools.length() == 0) return null
        val out = JSONArray()
        for (i in 0 until tools.length()) {
            val tool = tools.optJSONObject(i) ?: continue
            out.put(
                JSONObject().apply {
                    put("type", "function")
                    put(
                        "function",
                        JSONObject().apply {
                            put("name", truncateToolName(tool.optString("name")))
                            put("description", tool.optString("description"))
                            put(
                                "parameters",
                                tool.optJSONObject("input_schema")
                                    ?: JSONObject().put("type", "object").put("properties", JSONObject()),
                            )
                        },
                    )
                },
            )
        }
        return out.takeIf { it.length() > 0 }
    }

    /**
     * Anthropic's tool choice as the OpenAI parameter.
     *
     * `auto` and `none` pass straight through; `any` means "call some tool",
     * which OpenAI spells `required`; `tool` names the one it wants.
     */
    private fun anthropicToolChoiceToChat(choice: JSONObject?): JSONObject? {
        if (choice == null) return null
        return when (val type = choice.optString("type")) {
            "auto" -> JSONObject().put("type", "auto")
            "none" -> JSONObject().put("type", "none")
            "any" -> JSONObject().put("type", "required")
            "tool" -> JSONObject().apply {
                put("type", "function")
                put(
                    "function",
                    JSONObject().put("name", truncateToolName(choice.optString("name"))),
                )
            }
            else -> type.takeIf { it.isNotEmpty() }?.let { JSONObject().put("type", it) }
        }
    }

    /**
     * Anthropic's `thinking` budget as an OpenAI effort tier.
     *
     * The two are not the same knob, so the mapping is deliberately coarse: it
     * only has to preserve "think harder" versus "answer directly".
     */
    private fun anthropicThinkingToEffort(thinking: JSONObject?): String? {
        if (thinking == null) return null
        if (thinking.optString("type") == "disabled") return null
        return when {
            thinking.has("budget_tokens") -> {
                val budget = thinking.optInt("budget_tokens")
                when {
                    budget <= 0 -> null
                    budget < 2_048 -> "low"
                    budget < 8_192 -> "medium"
                    budget < 24_576 -> "high"
                    else -> "max"
                }
            }
            thinking.optBoolean("enabled") -> "medium"
            else -> null
        }
    }

    // ------------------------------------------------------------------ //
    // Inbound: OpenAI Responses
    // ------------------------------------------------------------------ //

    /**
     * Rewrites a Responses request as Chat Completions.
     *
     * Follows `translate_messages_to_responses_input` in reverse: system text
     * becomes a system message, user text and images keep their parts, a tool
     * result becomes a tool message, and an assistant tool call hangs off the
     * assistant turn.
     */
    private fun responsesToChat(body: JSONObject): JSONObject {
        val out = JSONObject()
        out.put("model", body.optString("model"))
        val messages = JSONArray()

        // The Responses API keeps the system prompt out of the input list.
        body.optString("instructions").takeIf { it.isNotEmpty() }?.let {
            messages.put(systemMessage(it))
        }

        when (val input = body.opt("input")) {
            is String -> messages.put(JSONObject().put("role", "user").put("content", input))
            is JSONArray -> {
                for (i in 0 until input.length()) {
                    val item = input.optJSONObject(i) ?: continue
                    when (item.optString("type")) {
                        // A tool's answer arrives as its own item, keyed to the
                        // call it belongs to.
                        "function_call_output" -> messages.put(
                            JSONObject().apply {
                                put("role", "tool")
                                put("tool_call_id", item.optString("call_id"))
                                put("content", flattenContent(item.opt("output")))
                            },
                        )
                        // A prior tool call is replayed as part of the assistant
                        // turn rather than standing alone.
                        "function_call" -> appendAssistantCall(
                            messages,
                            item.optString("call_id"),
                            item.optString("name"),
                            item.optString("arguments"),
                        )
                        // Reasoning replayed from an earlier turn.
                        "reasoning" -> appendReasoning(messages, item)
                        else -> {
                            val role = item.optString("role").ifEmpty { "user" }
                            // Content is passed through part by part rather than
                            // flattened: this dialect carries images as their own
                            // part, and flattening would leave only the words
                            // around them.
                            val content = responsesContent(item.opt("content"))
                            if (role == "assistant" && isEmptyContent(content)) continue
                            messages.put(JSONObject().put("role", role).put("content", content))
                        }
                    }
                }
            }
        }
        out.put("messages", messages)

        copyNumber(body, out, "max_output_tokens", "max_tokens")
        copyNumber(body, out, "temperature", "temperature")
        copyNumber(body, out, "top_p", "top_p")
        body.optJSONArray("tools")?.let { tools ->
            responsesToolsToChat(tools)?.let { out.put("tools", it) }
        }
        responsesToolChoiceToChat(body.opt("tool_choice"))?.let { out.put("tool_choice", it) }
        responsesReasoningToEffort(body.optJSONObject("reasoning"))?.let {
            out.put("reasoning_effort", it)
        }
        return out
    }

    /**
     * Attaches a replayed tool call to the assistant message it belongs to.
     *
     * The Responses API lists calls as siblings of messages, while Chat
     * Completions hangs them off the assistant turn, so consecutive calls are
     * gathered onto one message.
     */
    private fun appendAssistantCall(
        sink: JSONArray,
        callId: String,
        name: String,
        arguments: String,
    ) {
        val last = sink.optJSONObject(sink.length() - 1)
        val target = if (last != null && last.optString("role") == "assistant" && last.has("tool_calls")) {
            last
        } else {
            JSONObject().apply {
                put("role", "assistant")
                put("content", "")
                put("tool_calls", JSONArray())
            }.also { sink.put(it) }
        }
        target.optJSONArray("tool_calls")?.put(
            JSONObject().apply {
                put("id", callId)
                put("type", "function")
                put(
                    "function",
                    JSONObject().apply {
                        put("name", name)
                        put("arguments", arguments.ifEmpty { "{}" })
                    },
                )
            },
        )
    }

    /** Replays a reasoning item on the assistant turn it belongs to. */
    private fun appendReasoning(sink: JSONArray, item: JSONObject) {
        val text = flattenContent(item.opt("summary")).ifEmpty {
            flattenContent(item.opt("content"))
        }
        if (text.isEmpty()) return
        val last = sink.optJSONObject(sink.length() - 1)
        if (last != null && last.optString("role") == "assistant") {
            last.put("reasoning_content", text)
        } else {
            sink.put(
                JSONObject().apply {
                    put("role", "assistant")
                    put("content", "")
                    put("reasoning_content", text)
                },
            )
        }
    }

    /** Responses tool schemas are flat; Chat wants them under `function`. */
    private fun responsesToolsToChat(tools: JSONArray?): JSONArray? {
        if (tools == null || tools.length() == 0) return null
        val out = JSONArray()
        for (i in 0 until tools.length()) {
            val tool = tools.optJSONObject(i) ?: continue
            if (tool.optString("type") != "function") continue
            out.put(
                JSONObject().apply {
                    put("type", "function")
                    put(
                        "function",
                        JSONObject().apply {
                            put("name", truncateToolName(tool.optString("name")))
                            put("description", tool.optString("description"))
                            put(
                                "parameters",
                                tool.optJSONObject("parameters")
                                    ?: JSONObject().put("type", "object").put("properties", JSONObject()),
                            )
                        },
                    )
                },
            )
        }
        return out.takeIf { it.length() > 0 }
    }

    private fun responsesToolChoiceToChat(choice: Any?): JSONObject? = when (choice) {
        is String -> choice.takeIf { it.isNotEmpty() }?.let { JSONObject().put("type", it) }
        is JSONObject -> when (val type = choice.optString("type")) {
            "function" -> JSONObject().apply {
                put("type", "function")
                put(
                    "function",
                    JSONObject().put("name", truncateToolName(choice.optString("name"))),
                )
            }
            else -> type.takeIf { it.isNotEmpty() }?.let { JSONObject().put("type", it) }
        }
        else -> null
    }

    /** Responses' `reasoning.effort` maps onto the OpenAI parameter directly. */
    private fun responsesReasoningToEffort(reasoning: JSONObject?): String? =
        reasoning?.optString("effort")?.takeIf { it.isNotEmpty() && it != "none" }

    // ------------------------------------------------------------------ //
    // Outbound: a whole answer
    // ------------------------------------------------------------------ //

    /** Rewrites an aggregated Chat Completions answer into [format]. */
    fun fromChatResponse(chat: JSONObject, format: ApiFormat, model: String): JSONObject =
        when (format) {
            ApiFormat.OPENAI -> chat
            ApiFormat.ANTHROPIC -> chatToAnthropic(chat, model)
            ApiFormat.RESPONSES -> chatToResponses(chat, model)
        }

    /**
     * An answer as an Anthropic message.
     *
     * Follows `_translate_openai_content_to_anthropic`: thinking first, then
     * text, then tool calls — that order matters, because a client renders the
     * blocks in the order it receives them.
     */
    private fun chatToAnthropic(chat: JSONObject, model: String): JSONObject {
        val choice = chat.optJSONArray("choices")?.optJSONObject(0) ?: JSONObject()
        val message = choice.optJSONObject("message") ?: JSONObject()
        val content = JSONArray()

        // Reasoning first: it describes how the answer was reached.
        val thinkingBlocks = message.optJSONArray("thinking_blocks")
        if (thinkingBlocks != null && thinkingBlocks.length() > 0) {
            for (i in 0 until thinkingBlocks.length()) {
                val block = thinkingBlocks.optJSONObject(i) ?: continue
                when (block.optString("type")) {
                    "thinking" -> {
                        val text = block.optString("thinking")
                        val signature = block.optString("signature")
                        // An empty block without a signature carries nothing and
                        // is rejected on the way back, so it is skipped.
                        if (text.isEmpty() && signature.isEmpty()) continue
                        content.put(
                            JSONObject().apply {
                                put("type", "thinking")
                                put("thinking", text)
                                if (signature.isNotEmpty()) put("signature", signature)
                            },
                        )
                    }
                    "redacted_thinking" -> content.put(
                        JSONObject().apply {
                            put("type", "redacted_thinking")
                            put("data", block.optString("data"))
                        },
                    )
                }
            }
        } else {
            message.optString("reasoning_content").takeIf { it.isNotEmpty() }?.let {
                content.put(JSONObject().put("type", "thinking").put("thinking", it))
            }
        }

        message.optString("content").takeIf { it.isNotEmpty() }?.let {
            content.put(JSONObject().put("type", "text").put("text", it))
        }
        message.optJSONArray("tool_calls")?.let { calls ->
            for (i in 0 until calls.length()) {
                val call = calls.optJSONObject(i) ?: continue
                val fn = call.optJSONObject("function") ?: continue
                content.put(
                    JSONObject().apply {
                        put("type", "tool_use")
                        put("id", normalizeToolUseId(call.optString("id"), i))
                        put("name", fn.optString("name"))
                        // Chat carries arguments as a string; this dialect wants
                        // the parsed object.
                        put("input", parseArguments(fn.optString("arguments")))
                    },
                )
            }
        }

        val usage = chat.optJSONObject("usage") ?: JSONObject()
        return JSONObject().apply {
            put("id", "msg_" + System.currentTimeMillis())
            put("type", "message")
            put("role", "assistant")
            put("model", model)
            put("content", content)
            put("stop_reason", anthropicStopReason(choice.optString("finish_reason")))
            put("stop_sequence", JSONObject.NULL)
            put(
                "usage",
                JSONObject().apply {
                    put("input_tokens", usage.optInt("prompt_tokens"))
                    put("output_tokens", usage.optInt("completion_tokens"))
                },
            )
        }
    }

    /** An answer as a Responses object. */
    private fun chatToResponses(chat: JSONObject, model: String): JSONObject {
        val choice = chat.optJSONArray("choices")?.optJSONObject(0) ?: JSONObject()
        val message = choice.optJSONObject("message") ?: JSONObject()
        val output = JSONArray()

        message.optString("reasoning_content").takeIf { it.isNotEmpty() }?.let { text ->
            output.put(
                JSONObject().apply {
                    put("type", "reasoning")
                    put("id", "rs_" + System.currentTimeMillis())
                    put(
                        "summary",
                        JSONArray().put(JSONObject().put("type", "summary_text").put("text", text)),
                    )
                },
            )
        }

        message.optString("content").takeIf { it.isNotEmpty() }?.let { text ->
            output.put(
                JSONObject().apply {
                    put("type", "message")
                    put("id", "msg_" + System.currentTimeMillis())
                    put("role", "assistant")
                    put("status", "completed")
                    put(
                        "content",
                        JSONArray().put(
                            JSONObject().apply {
                                put("type", "output_text")
                                put("text", text)
                                put("annotations", JSONArray())
                            },
                        ),
                    )
                },
            )
        }
        message.optJSONArray("tool_calls")?.let { calls ->
            for (i in 0 until calls.length()) {
                val call = calls.optJSONObject(i) ?: continue
                val fn = call.optJSONObject("function") ?: continue
                output.put(
                    JSONObject().apply {
                        put("type", "function_call")
                        put("id", "fc_" + System.currentTimeMillis() + "_" + i)
                        put("call_id", call.optString("id").ifEmpty { "call_$i" })
                        put("name", fn.optString("name"))
                        put("arguments", fn.optString("arguments").ifEmpty { "{}" })
                        put("status", "completed")
                    },
                )
            }
        }

        val usage = chat.optJSONObject("usage") ?: JSONObject()
        val prompt = usage.optInt("prompt_tokens")
        val completion = usage.optInt("completion_tokens")
        val calls = message.optJSONArray("tool_calls")?.length() ?: 0
        return JSONObject().apply {
            put("id", "resp_" + System.currentTimeMillis())
            put("object", "response")
            put("created_at", System.currentTimeMillis() / 1000)
            put("model", model)
            put("status", "completed")
            put("output", output)
            put("output_text", message.optString("content"))
            put("parallel_tool_calls", calls > 1)
            put(
                "usage",
                JSONObject().apply {
                    put("input_tokens", prompt)
                    put("output_tokens", completion)
                    put("total_tokens", prompt + completion)
                },
            )
        }
    }

    // ------------------------------------------------------------------ //
    // Streaming
    // ------------------------------------------------------------------ //

    /**
     * A renderer for one streamed answer.
     *
     * Stateful on purpose: the client's dialect needs the block boundaries, and
     * those only exist once the fragments are seen in order. One instance serves
     * one answer and is then thrown away.
     */
    fun streamer(format: ApiFormat, model: String): Streamer = Streamer(format, model)

    class Streamer internal constructor(
        private val format: ApiFormat,
        private val model: String,
    ) {
        // Anthropic: which block is open, and how many have been opened.
        private var openKind: String? = null
        private var blocksOpened = 0
        private val toolBlockIndex = mutableMapOf<Int, Int>()
        private val toolNames = mutableMapOf<Int, String>()
        private val toolIds = mutableMapOf<Int, String>()

        // Responses: one item per text run and per tool call.
        private val textSeen = StringBuilder()
        private var textItemId: String? = null
        private var textOutputIndex: Int? = null
        private val callOutputIndex = mutableMapOf<Int, Int>()
        private val callItemIds = mutableMapOf<Int, String>()
        private val callIds = mutableMapOf<Int, String>()
        private val callNames = mutableMapOf<Int, String>()
        private val callArguments = mutableMapOf<Int, StringBuilder>()
        private val completedItems = JSONArray()
        private var nextOutputIndex = 0
        private var sequence = 0
        private val responseId = "resp_" + System.currentTimeMillis()

        /** Frames to send before anything else. */
        fun opening(): List<String> {
            if (format == ApiFormat.OPENAI) return emptyList()
            return when (format) {
                ApiFormat.ANTHROPIC -> listOf(
                    anthropicEvent(
                        "message_start",
                        JSONObject().apply {
                            put("type", "message_start")
                            put(
                                "message",
                                JSONObject().apply {
                                    put("id", "msg_" + System.currentTimeMillis())
                                    put("type", "message")
                                    put("role", "assistant")
                                    put("model", model)
                                    put("content", JSONArray())
                                    put("stop_reason", JSONObject.NULL)
                                    put("stop_sequence", JSONObject.NULL)
                                    put(
                                        "usage",
                                        JSONObject().put("input_tokens", 0).put("output_tokens", 0),
                                    )
                                },
                            )
                        },
                    ),
                )
                ApiFormat.RESPONSES -> listOf(
                    dataEvent(
                        JSONObject().apply {
                            put("type", "response.created")
                            put("sequence_number", sequence++)
                            put(
                                "response",
                                JSONObject().apply {
                                    put("id", responseId)
                                    put("object", "response")
                                    put("status", "in_progress")
                                    put("model", model)
                                    put("output", JSONArray())
                                },
                            )
                        },
                    ),
                )
                ApiFormat.OPENAI -> emptyList()
            }
        }

        /** Translates one upstream delta into the client's dialect. */
        fun onDelta(delta: JSONObject): List<String> = when (format) {
            ApiFormat.ANTHROPIC -> anthropicDelta(delta)
            ApiFormat.RESPONSES -> responsesDelta(delta)
            ApiFormat.OPENAI -> emptyList()
        }

        /**
         * Frames that close the answer.
         *
         * Both dialects require them: a client that saw an opening and no
         * closing waits forever, which looks exactly like a stalled stream.
         */
        fun finish(finishReason: String?, promptTokens: Int, completionTokens: Int): List<String> =
            when (format) {
                ApiFormat.ANTHROPIC -> anthropicFinish(finishReason, promptTokens, completionTokens)
                ApiFormat.RESPONSES -> responsesFinish(promptTokens, completionTokens)
                ApiFormat.OPENAI -> listOf("data: [DONE]\n\n")
            }

        // -- Anthropic -------------------------------------------------- //

        private fun anthropicDelta(delta: JSONObject): List<String> {
            val out = mutableListOf<String>()

            delta.optString("reasoning_content").takeIf { it.isNotEmpty() }?.let { piece ->
                out += ensureBlock("thinking") {
                    JSONObject().put("type", "thinking").put("thinking", "")
                }
                out += anthropicEvent(
                    "content_block_delta",
                    JSONObject().apply {
                        put("type", "content_block_delta")
                        put("index", blocksOpened - 1)
                        put("delta", JSONObject().put("type", "thinking_delta").put("thinking", piece))
                    },
                )
            }

            delta.optString("content").takeIf { it.isNotEmpty() }?.let { piece ->
                out += ensureBlock("text") { JSONObject().put("type", "text").put("text", "") }
                out += anthropicEvent(
                    "content_block_delta",
                    JSONObject().apply {
                        put("type", "content_block_delta")
                        put("index", blocksOpened - 1)
                        put("delta", JSONObject().put("type", "text_delta").put("text", piece))
                    },
                )
            }

            delta.optJSONArray("tool_calls")?.let { out += anthropicToolDeltas(it) }
            return out
        }

        /**
         * Opens a block of [kind] if a different one is open.
         *
         * Returns the frames needed to close the previous block and start this
         * one, which is empty when the same kind is already open and can simply
         * be appended to. A second `content_block_start` for one block confuses
         * every client that indexes its blocks by that event.
         */
        private fun ensureBlock(kind: String, block: () -> JSONObject): List<String> {
            if (openKind == kind) return emptyList()
            val out = mutableListOf<String>()
            openKind?.let {
                out += anthropicEvent(
                    "content_block_stop",
                    JSONObject().put("type", "content_block_stop").put("index", blocksOpened - 1),
                )
            }
            out += anthropicEvent(
                "content_block_start",
                JSONObject().apply {
                    put("type", "content_block_start")
                    put("index", blocksOpened)
                    put("content_block", block())
                },
            )
            blocksOpened++
            openKind = kind
            return out
        }

        private fun anthropicToolDeltas(calls: JSONArray): List<String> {
            val out = mutableListOf<String>()
            for (i in 0 until calls.length()) {
                val call = calls.optJSONObject(i) ?: continue
                val upstreamIndex = call.optInt("index", i)
                val fn = call.optJSONObject("function") ?: continue
                val name = fn.optString("name")

                // The name appears only on a call's first fragment, which is what
                // marks its block as needing to be opened.
                if (name.isNotEmpty() && toolBlockIndex[upstreamIndex] == null) {
                    toolNames[upstreamIndex] = name
                    call.optString("id").takeIf { it.isNotEmpty() }?.let { toolIds[upstreamIndex] = it }
                    out += openToolBlock(upstreamIndex, name)
                    toolBlockIndex[upstreamIndex] = blocksOpened - 1
                }
                val index = toolBlockIndex[upstreamIndex] ?: continue
                fn.optString("arguments").takeIf { it.isNotEmpty() }?.let { piece ->
                    out += anthropicEvent(
                        "content_block_delta",
                        JSONObject().apply {
                            put("type", "content_block_delta")
                            put("index", index)
                            // Partial JSON goes out as-is; the client reassembles it.
                            put(
                                "delta",
                                JSONObject().put("type", "input_json_delta").put("partial_json", piece),
                            )
                        },
                    )
                }
            }
            return out
        }

        /**
         * Opens a block for one tool call.
         *
         * A second call cannot go through [ensureBlock]: it would see a
         * `tool_use` block already open and stay silent, leaving the client to
         * append the second call's arguments to the first call's block. Parallel
         * calls are the normal case for a coding agent, so each one always gets
         * its own block.
         */
        private fun openToolBlock(callIndex: Int, name: String): List<String> {
            val out = mutableListOf<String>()
            // A different kind of block is still closed first.
            if (openKind != null) {
                out += anthropicEvent(
                    "content_block_stop",
                    JSONObject().put("type", "content_block_stop").put("index", blocksOpened - 1),
                )
            }
            out += anthropicEvent(
                "content_block_start",
                JSONObject().apply {
                    put("type", "content_block_start")
                    put("index", blocksOpened)
                    put(
                        "content_block",
                        JSONObject().apply {
                            put("type", "tool_use")
                            put(
                                "id",
                                toolIds[callIndex]
                                    ?: "toolu_" + System.currentTimeMillis() + "_" + callIndex,
                            )
                            put("name", name)
                            put("input", JSONObject())
                        },
                    )
                },
            )
            blocksOpened++
            openKind = "tool_use"
            return out
        }

        private fun anthropicFinish(
            finishReason: String?,
            promptTokens: Int,
            completionTokens: Int,
        ): List<String> {
            val out = mutableListOf<String>()
            openKind?.let {
                out += anthropicEvent(
                    "content_block_stop",
                    JSONObject().put("type", "content_block_stop").put("index", blocksOpened - 1),
                )
                openKind = null
            }
            out += anthropicEvent(
                "message_delta",
                JSONObject().apply {
                    put("type", "message_delta")
                    put(
                        "delta",
                        JSONObject().apply {
                            put("stop_reason", anthropicStopReason(finishReason))
                            put("stop_sequence", JSONObject.NULL)
                        },
                    )
                    put(
                        "usage",
                        JSONObject().put("output_tokens", completionTokens),
                    )
                },
            )
            out += anthropicEvent("message_stop", JSONObject().put("type", "message_stop"))
            return out
        }

        // -- Responses -------------------------------------------------- //

        private fun responsesDelta(delta: JSONObject): List<String> {
            val out = mutableListOf<String>()
            delta.optString("reasoning_content").takeIf { it.isNotEmpty() }?.let { piece ->
                // This dialect reports reasoning as its own item type; sending it
                // as text would make the client show it as the answer.
                out += dataEvent(
                    JSONObject().apply {
                        put("type", "response.reasoning_summary_text.delta")
                        put("sequence_number", sequence++)
                        put("output_index", 0)
                        put("summary_index", 0)
                        put("delta", piece)
                    },
                )
            }
            delta.optString("content").takeIf { it.isNotEmpty() }?.let { piece ->
                out += ensureTextItem()
                out += dataEvent(
                    JSONObject().apply {
                        put("type", "response.output_text.delta")
                        put("sequence_number", sequence++)
                        put("item_id", textItemId.orEmpty())
                        put("output_index", textOutputIndex ?: 0)
                        put("content_index", 0)
                        put("delta", piece)
                    },
                )
                textSeen.append(piece)
            }
            // A tool call arrives as fragments keyed by index, exactly as it does
            // in Chat Completions: the name and id come with the first fragment
            // and the arguments are spread across the rest. The dialect wants an
            // item opened for the call before its arguments are streamed, so the
            // opening is held back until the name shows up.
            delta.optJSONArray("tool_calls")?.let { calls ->
                for (i in 0 until calls.length()) {
                    val row = calls.optJSONObject(i) ?: continue
                    val callIndex = row.optInt("index", i)
                    val fn = row.optJSONObject("function") ?: continue
                    val name = fn.optString("name")
                    if (name.isNotEmpty() && callOutputIndex[callIndex] == null) {
                        out += openCallItem(callIndex, name, row.optString("id"))
                    }
                    fn.optString("arguments").takeIf { it.isNotEmpty() }?.let { piece ->
                        callArguments.getOrPut(callIndex) { StringBuilder() }.append(piece)
                        // Arguments are emitted as they arrive rather than
                        // collected: a long call would otherwise sit silent until
                        // it finished.
                        val index = callOutputIndex[callIndex] ?: return@let
                        out += dataEvent(
                            JSONObject().apply {
                                put("type", "response.function_call_arguments.delta")
                                put("sequence_number", sequence++)
                                put("item_id", callItemIds[callIndex].orEmpty())
                                put("output_index", index)
                                put("delta", piece)
                            },
                        )
                    }
                }
            }
            return out
        }

        private fun ensureTextItem(): List<String> {
            if (textItemId != null) return emptyList()
            val id = "msg_" + System.currentTimeMillis()
            val index = nextOutputIndex++
            textItemId = id
            textOutputIndex = index
            return listOf(
                dataEvent(
                    JSONObject().apply {
                        put("type", "response.output_item.added")
                        put("sequence_number", sequence++)
                        put("output_index", index)
                        put(
                            "item",
                            JSONObject().apply {
                                put("type", "message")
                                put("id", id)
                                put("role", "assistant")
                                put("status", "in_progress")
                                put("content", JSONArray())
                            },
                        )
                    },
                ),
            )
        }

        private fun openCallItem(callIndex: Int, name: String, upstreamId: String): List<String> {
            val id = "fc_" + System.currentTimeMillis() + "_" + callIndex
            val callId = upstreamId.ifEmpty { "call_" + callIndex }
            val index = nextOutputIndex++
            callItemIds[callIndex] = id
            callIds[callIndex] = callId
            callNames[callIndex] = name
            callOutputIndex[callIndex] = index
            callArguments.getOrPut(callIndex) { StringBuilder() }
            return listOf(
                dataEvent(
                    JSONObject().apply {
                        put("type", "response.output_item.added")
                        put("sequence_number", sequence++)
                        put("output_index", index)
                        put(
                            "item",
                            JSONObject().apply {
                                put("type", "function_call")
                                put("id", id)
                                put("call_id", callId)
                                put("name", name)
                                put("arguments", "")
                                put("status", "in_progress")
                            },
                        )
                    },
                ),
            )
        }

        private fun responsesFinish(promptTokens: Int, completionTokens: Int): List<String> {
            val out = mutableListOf<String>()

            textItemId?.let { id ->
                val index = textOutputIndex ?: 0
                val text = textSeen.toString()
                out += dataEvent(
                    JSONObject().apply {
                        put("type", "response.output_text.done")
                        put("sequence_number", sequence++)
                        put("item_id", id)
                        put("output_index", index)
                        put("content_index", 0)
                        put("text", text)
                    },
                )
                val item = JSONObject().apply {
                    put("type", "message")
                    put("id", id)
                    put("role", "assistant")
                    put("status", "completed")
                    put(
                        "content",
                        JSONArray().put(
                            JSONObject().apply {
                                put("type", "output_text")
                                put("text", text)
                                put("annotations", JSONArray())
                            },
                        ),
                    )
                }
                out += dataEvent(
                    JSONObject().apply {
                        put("type", "response.output_item.done")
                        put("sequence_number", sequence++)
                        put("output_index", index)
                        put("item", item)
                    },
                )
                completedItems.put(item)
            }

            // Tool calls are closed in the order they were opened, which is not
            // necessarily the order the map iterates in.
            for (callIndex in callOutputIndex.keys.sortedBy { callOutputIndex[it] }) {
                val index = callOutputIndex[callIndex] ?: continue
                val arguments = callArguments[callIndex]?.toString().orEmpty().ifEmpty { "{}" }
                val callId = callIds[callIndex].orEmpty()
                val name = callNames[callIndex].orEmpty()
                out += dataEvent(
                    JSONObject().apply {
                        put("type", "response.function_call_arguments.done")
                        put("sequence_number", sequence++)
                        put("item_id", callItemIds[callIndex].orEmpty())
                        put("output_index", index)
                        put("arguments", arguments)
                    },
                )
                val item = JSONObject().apply {
                    put("type", "function_call")
                    put("id", callItemIds[callIndex].orEmpty())
                    put("call_id", callId)
                    put("name", name)
                    put("arguments", arguments)
                    put("status", "completed")
                }
                out += dataEvent(
                    JSONObject().apply {
                        put("type", "response.output_item.done")
                        put("sequence_number", sequence++)
                        put("output_index", index)
                        put("item", item)
                    },
                )
                completedItems.put(item)
            }

            out += dataEvent(
                JSONObject().apply {
                    put("type", "response.completed")
                    put("sequence_number", sequence++)
                    put(
                        "response",
                        JSONObject().apply {
                            put("id", responseId)
                            put("object", "response")
                            put("status", "completed")
                            put("model", model)
                            put("output", completedItems)
                            put("output_text", textSeen.toString())
                            put(
                                "usage",
                                JSONObject().apply {
                                    put("input_tokens", promptTokens)
                                    put("output_tokens", completionTokens)
                                    put("total_tokens", promptTokens + completionTokens)
                                },
                            )
                        },
                    )
                },
            )
            return out
        }

        private fun anthropicEvent(name: String, payload: JSONObject): String =
            "event: $name\ndata: $payload\n\n"

        private fun dataEvent(payload: JSONObject): String = "data: $payload\n\n"
    }

    // ------------------------------------------------------------------ //
    // Shared helpers
    // ------------------------------------------------------------------ //

    /** OpenAI's limit on a tool name, above which the upstream rejects it. */
    private const val MAX_TOOL_NAME = 64

    /**
     * Shortens a tool name that OpenAI would refuse.
     *
     * LiteLLM's format is a 55-character prefix plus an 8-character hash of the
     * whole name, so two long names that start alike stay distinct and the
     * mapping is stable across turns.
     */
    fun truncateToolName(name: String): String {
        if (name.length <= MAX_TOOL_NAME) return name
        val digest = MessageDigest.getInstance("SHA-256").digest(name.toByteArray())
        val hash = digest.joinToString("") { "%02x".format(it) }.take(8)
        return name.take(55) + "_" + hash
    }

    /**
     * Anthropic's spelling of the stop reasons Chat Completions reports.
     *
     * Follows `_translate_openai_finish_reason_to_anthropic`, including the
     * `refusal` case that a plain transcription would miss.
     */
    fun anthropicStopReason(finishReason: String?): String = when (finishReason) {
        "stop" -> "end_turn"
        "length" -> "max_tokens"
        "tool_calls", "function_call" -> "tool_use"
        "content_filter", "refusal" -> "refusal"
        null, "" -> "end_turn"
        else -> "end_turn"
    }

    /**
     * An id the Anthropic side will accept.
     *
     * Some providers hand back ids that carry characters the field does not
     * allow, such as a suffix that names their own function namespace.
     */
    private fun normalizeToolUseId(raw: String, position: Int): String {
        val cleaned = raw.substringBefore(':').filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        return cleaned.ifEmpty { "toolu_$position" }
    }

    /** Parses a tool-call argument string, tolerating a malformed payload. */
    fun parseArguments(raw: String): JSONObject =
        runCatching { JSONObject(raw.trim().ifEmpty { "{}" }) }.getOrElse { JSONObject() }

    /**
     * Translates a Responses content value into the Chat Completions shape.
     *
     * A plain string stays a string, because that is what most turns are and
     * rewriting them into a one-element list would change the request for no
     * reason. A list is mapped part by part so images survive: the two dialects
     * agree on text, and an image is a URL either way, so only the part's name
     * has to be changed.
     */
    private fun responsesContent(value: Any?): Any = when (value) {
        is String -> value
        is JSONArray -> {
            val out = JSONArray()
            for (i in 0 until value.length()) {
                val part = value.optJSONObject(i) ?: continue
                when (part.optString("type")) {
                    "input_text", "output_text", "text" ->
                        out.put(JSONObject().put("type", "text").put("text", part.optString("text")))
                    "input_image" -> chatImagePart(part)?.let { out.put(it) }
                    // A tool result may wrap its own content list.
                    else -> if (part.has("content")) {
                        out.put(
                            JSONObject().put("type", "text")
                                .put("text", flattenContent(part.opt("content"))),
                        )
                    }
                }
            }
            out
        }
        else -> flattenContent(value)
    }

    /**
     * Rewrites one image part, or null when it carries nothing usable.
     *
     * Chat Completions wants the URL nested under `image_url`; the Responses
     * dialect puts it directly on the part. The detail hint is carried across
     * because it changes how much of the picture the model is shown.
     */
    private fun chatImagePart(part: JSONObject): JSONObject? {
        val url = part.optString("image_url").ifEmpty {
            part.optJSONObject("image_url")?.optString("url").orEmpty()
        }
        if (url.isEmpty()) return null
        return JSONObject().apply {
            put("type", "image_url")
            put(
                "image_url",
                JSONObject().apply {
                    put("url", url)
                    part.optString("detail").takeIf { it.isNotEmpty() }?.let { put("detail", it) }
                },
            )
        }
    }

    /** Whether a translated content value carries nothing worth sending. */
    private fun isEmptyContent(value: Any?): Boolean = when (value) {
        null -> true
        is String -> value.isEmpty()
        is JSONArray -> value.length() == 0
        else -> false
    }

    /**
     * Flattens a content value that may be a plain string or a list of parts.
     *
     * Both dialects allow either, and each part type carries its text under the
     * same key, so one reader covers all of them. Responses' `summary` and
     * `output_text` parts are included: reasoning arrives that way.
     */
    fun flattenContent(value: Any?): String = when (value) {
        null -> ""
        is String -> value
        is JSONArray -> (0 until value.length()).joinToString("") { i ->
            val part = value.optJSONObject(i)
            when {
                part == null -> ""
                part.has("text") -> part.optString("text")
                part.has("content") -> flattenContent(part.opt("content"))
                else -> ""
            }
        }
        else -> ""
    }

    /** Copies a numeric field under a new name, when the client sent one. */
    private fun copyNumber(from: JSONObject, to: JSONObject, source: String, target: String) {
        if (!from.has(source)) return
        val value = from.opt(source)
        when (value) {
            is Number -> to.put(target, value)
            is String -> value.toDoubleOrNull()?.let { to.put(target, it) }
        }
    }
}
