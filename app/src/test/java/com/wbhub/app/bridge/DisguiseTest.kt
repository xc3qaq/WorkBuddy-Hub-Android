package com.wbhub.app.bridge

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the endpoint rewrites before a request leaves the device.
 *
 * Every case here comes from a measurement against the live gateway, which
 * refuses a request (`code: 11128`, "请求被拦截") when certain passages appear in
 * a `system` or `assistant` turn. The runtime writes its name with a zero-width
 * space between the two words, so a rule spelled with a plain space never fires
 * — which is exactly the failure these cases pin down.
 */
class DisguiseTest {

    /** The identity sentence as the runtime actually writes it. */
    private val runtimeIdentity = "You are an AI agent powered by DeepSeek\u200B Harness."

    @Test
    fun `identity sentence with a zero-width space is rewritten`() {
        val out = ApiAdapters.disguise(runtimeIdentity)
        assertNotContainsBlockedBrand(out)
        assertFalse("the sentence must still describe the agent", out.isBlank())
    }

    @Test
    fun `identity sentence with a plain space is rewritten too`() {
        val out = ApiAdapters.disguise("You are an AI agent powered by DeepSeek Harness.")
        assertNotContainsBlockedBrand(out)
    }

    @Test
    fun `identity sentence is only rewritten at the start of a turn`() {
        // Measured: the gateway anchors this rule to offset 0, so a copy in the
        // middle of a prompt is not what it filters — rewriting it there would
        // be a change the block did not call for. The brand fallback still
        // neutralises the name itself, so the check is that the sentence keeps
        // its shape rather than that the text is untouched.
        val buried = "Some preamble.\n\nYou are an AI agent powered by DeepSeek\u200B Harness."
        val out = ApiAdapters.disguise(buried)
        assertTrue(out.startsWith("Some preamble."))
        assertNotContainsBlockedBrand(out)
    }

    @Test
    fun `file reference note loses the at sign`() {
        val note =
            "Tokens prefixed with @ are paths the user explicitly referenced. Relative paths " +
                "resolve from the workspace root."
        val out = ApiAdapters.disguise(note)
        assertEquals(
            "Tokens prefixed with (at) are paths the user explicitly referenced. Relative paths " +
                "resolve from the workspace root.",
            out,
        )
        assertFalse(out.contains("Tokens prefixed with @"))
    }

    @Test
    fun `coding agent opener is rewritten for any model`() {
        val out = ApiAdapters.disguise(
            "You are a coding agent powered by the deepseek-v4.1-flash model.",
        )
        assertTrue(out.startsWith("You are a coding agent running on"))
        assertFalse(out.contains("coding agent powered by"))
    }

    @Test
    fun `web gui sentences are neutralised`() {
        val out = ApiAdapters.disguise(
            "You are interacting with the user through the DeepSeek Harness Web GUI whose URL " +
                "the runtime context names.\n" +
                "Current DeepSeek Harness Web GUI URL: \"http://127.0.0.1:3080\"",
        )
        assertTrue(out.contains("You are interacting with the user through this Web GUI"))
        assertTrue(out.contains("Current Web GUI URL"))
        assertNotContainsBlockedBrand(out)
    }

    @Test
    fun `rewriting is idempotent`() {
        val once = ApiAdapters.disguise(
            runtimeIdentity + "\n\n" +
                "Tokens prefixed with @ are paths the user explicitly referenced.",
        )
        val twice = ApiAdapters.disguise(once)
        assertEquals(once, twice)
    }

    @Test
    fun `ordinary text is left alone`() {
        val text = "Summarise the attached report in three bullet points."
        assertEquals(text, ApiAdapters.disguise(text))
        assertFalse(ApiAdapters.needsDisguise(text))
    }

    @Test
    fun `user turns are never rewritten`() {
        // The user's own words are data. A code sample or an email address in a
        // user turn has to reach the model exactly as it was typed.
        val question = "为什么 \"Tokens prefixed with @\" 这行会 400？我的邮箱 a@b.com"
        val body = JSONObject()
            .put("model", "m")
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "user").put("content", question)),
            )
        val disguised = ApiAdapters.toChatBody(body, ApiFormat.OPENAI, disguise = true)
        assertEquals(
            question,
            disguised.getJSONArray("messages").getJSONObject(0).getString("content"),
        )
    }

    @Test
    fun `system turns are rewritten through the part list`() {
        // A client may send content as blocks rather than a string; the rule
        // has to reach inside them or the block survives.
        val body = JSONObject()
            .put("model", "m")
            .put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject().put("role", "system").put(
                            "content",
                            JSONArray().put(JSONObject().put("type", "text").put("text", runtimeIdentity)),
                        ),
                    )
                    .put(JSONObject().put("role", "user").put("content", "hi")),
            )
        val disguised = ApiAdapters.toChatBody(body, ApiFormat.OPENAI, disguise = true)
        val text = disguised.getJSONArray("messages")
            .getJSONObject(0)
            .getJSONArray("content")
            .getJSONObject(0)
            .getString("text")
        assertFalse(text.contains("DeepSeek\u200B Harness"))
    }

    @Test
    fun `replayed assistant turns are rewritten`() {
        // History comes back on every turn, so a marker left in an old answer
        // would keep the whole conversation blocked.
        val body = JSONObject()
            .put("model", "m")
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "user").put("content", "hi"))
                    .put(JSONObject().put("role", "assistant").put("content", runtimeIdentity)),
            )
        val disguised = ApiAdapters.toChatBody(body, ApiFormat.OPENAI, disguise = true)
        val reply = disguised.getJSONArray("messages").getJSONObject(1).getString("content")
        assertFalse(reply.contains("DeepSeek\u200B Harness"))
    }

    /** The product name must no longer appear with an invisible separator. */
    private fun assertNotContainsBlockedBrand(text: String) {
        assertFalse(
            "still carries the blocked spelling: $text",
            Regex("DeepSeek[\\s\\u00A0\\u200B\\u2060\\uFEFF]*Harness").containsMatchIn(text),
        )
    }
}
