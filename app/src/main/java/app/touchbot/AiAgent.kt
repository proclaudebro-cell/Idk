package app.touchbot

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Drives the phone toward a goal: look at the screen, ask Claude for one action, run it,
 * repeat. The executed actions are returned so they can be saved and replayed without AI.
 */
class AiAgent(private val svc: AutomationService, private val runner: ActionRunner) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var cancelled = false
    @Volatile var running = false
        private set

    class Result(val success: Boolean, val message: String, val actions: List<Action>, val screenW: Int, val screenH: Int)

    fun cancel() {
        cancelled = true
    }

    fun start(goal: String, apiKey: String, model: String, onStatus: (String) -> Unit, onFinish: (Result) -> Unit) {
        if (running) return
        running = true
        cancelled = false
        worker.execute {
            val (w, h) = svc.screenSize()
            val executed = ArrayList<Action>()
            val result = try {
                loop(goal, apiKey, model, w, h, executed, onStatus)
            } catch (e: Exception) {
                Result(false, "Error: ${e.message ?: e.javaClass.simpleName}", executed, w, h)
            }
            running = false
            main.post { onFinish(result) }
        }
    }

    private fun status(onStatus: (String) -> Unit, s: String) = main.post { onStatus(s) }

    private fun loop(
        goal: String, apiKey: String, model: String, w: Int, h: Int,
        executed: MutableList<Action>, onStatus: (String) -> Unit,
    ): Result {
        val scale = minOf(1f, MAX_DIM.toFloat() / maxOf(w, h))
        val messages = JSONArray()
        var lastResultBlock: JSONObject? = null
        var pendingToolId: String? = null
        var feedback = "Task started."
        var misses = 0

        for (step in 1..MAX_STEPS) {
            if (cancelled) return Result(false, "Stopped", executed, w, h)
            status(onStatus, "AI step $step: reading screen")
            val dump = svc.dumpScreen(scale)
            val shot = svc.screenshot(scale)

            val content = JSONArray()
            content.put(textBlock(
                "$feedback\nScreen size: ${(w * scale).toInt()}x${(h * scale).toInt()} (all coordinates use this space).\n" +
                    "Screen elements:\n${dump.text}"
            ))
            if (shot != null) content.put(imageBlock(shot))

            if (pendingToolId == null) {
                val first = JSONArray().put(textBlock("Goal: $goal"))
                for (i in 0 until content.length()) first.put(content.get(i))
                messages.put(JSONObject().put("role", "user").put("content", first))
            } else {
                // Older screens are no longer useful and cost tokens; keep only the latest.
                lastResultBlock?.put("content", JSONArray().put(textBlock("(earlier screen omitted)")))
                val block = JSONObject().put("type", "tool_result").put("tool_use_id", pendingToolId)
                    .put("content", content)
                lastResultBlock = block
                messages.put(JSONObject().put("role", "user").put("content", JSONArray().put(block)))
            }

            status(onStatus, "AI step $step: thinking")
            val reply = callApi(apiKey, model, messages)
            if (cancelled) return Result(false, "Stopped", executed, w, h)
            if (reply.optString("stop_reason") == "refusal") {
                return Result(false, "The AI declined this request", executed, w, h)
            }
            val assistantContent = ClaudeApi.withoutThinking(reply.getJSONArray("content"))
            val toolUse = ClaudeApi.firstBlock(assistantContent, "tool_use")
            if (toolUse == null) {
                if (++misses > 2) return Result(false, "The AI did not choose an action", executed, w, h)
                if (assistantContent.length() == 0) assistantContent.put(textBlock("(no action)"))
                messages.put(JSONObject().put("role", "assistant").put("content", assistantContent))
                pendingToolId = null
                feedback = "You must respond by calling the act tool (use done or fail to finish)."
                continue
            }
            misses = 0
            messages.put(JSONObject().put("role", "assistant").put("content", assistantContent))
            pendingToolId = toolUse.getString("id")
            val input = toolUse.getJSONObject("input")
            val kind = input.optString("action")
            val note = input.optString("reason")

            when (kind) {
                "done" -> return Result(true, input.optString("summary", "Done"), executed, w, h)
                "fail" -> return Result(false, input.optString("reason", "The AI gave up"), executed, w, h)
            }

            status(onStatus, "AI step $step: $kind" + if (note.isNotEmpty()) " - $note" else "")
            val action = toAction(input, dump, scale)
            if (action == null) {
                feedback = "Action '$kind' was invalid (missing or out-of-range arguments). Try again."
                continue
            }
            var errMsg: String? = null
            if (action.type == "open_app") {
                errMsg = onMain { svc.openApp(action.text ?: "") }
            }
            val ok = if (errMsg != null) false else executeOnMain(action, w, h)
            if (ok) {
                executed.add(action.copy(delay = if (action.type == "wait") 0 else SETTLE_MS))
                feedback = "Action '$kind' succeeded."
            } else {
                feedback = "Action '$kind' failed${errMsg?.let { ": $it" } ?: ""}."
            }
            Thread.sleep(SETTLE_MS)
        }
        return Result(false, "Stopped after $MAX_STEPS steps without finishing", executed, w, h)
    }

    /** Converts model arguments (in screenshot space) into an [Action] in screen pixels. */
    private fun toAction(input: JSONObject, dump: ScreenDump, scale: Float): Action? {
        fun num(k: String): Float? = if (input.has(k)) input.optDouble(k).toFloat().takeIf { !it.isNaN() } else null
        fun px(k: String): Float? = num(k)?.let { it / scale }
        return when (input.optString("action")) {
            "tap" -> Action("tap", x = px("x") ?: return null, y = px("y") ?: return null)
            "long_press" -> Action("long_press", x = px("x") ?: return null, y = px("y") ?: return null, duration = 900)
            "tap_node", "long_press_node" -> {
                val id = input.optInt("id", -1)
                val c = dump.centers.getOrNull(id) ?: return null
                val type = if (input.optString("action") == "tap_node") "tap" else "long_press"
                Action(type, x = c.x / scale, y = c.y / scale, duration = if (type == "tap") 0 else 900)
            }
            "swipe" -> Action(
                "swipe",
                x = px("x") ?: return null, y = px("y") ?: return null,
                x2 = px("x2") ?: return null, y2 = px("y2") ?: return null,
                duration = input.optLong("duration_ms", 400).coerceIn(100, 3000),
            )
            "type_text" -> Action("text", text = input.optString("text").takeIf { input.has("text") } ?: return null)
            "enter" -> Action("enter")
            "back", "home", "recents" -> Action(input.getString("action"))
            "open_app" -> Action("open_app", text = input.optString("text").takeIf { it.isNotBlank() } ?: return null)
            "wait" -> Action("wait", duration = input.optLong("duration_ms", 1500).coerceIn(200, 15000))
            else -> null
        }
    }

    private fun <T> onMain(block: () -> T): T? {
        val latch = CountDownLatch(1)
        var out: T? = null
        main.post {
            try { out = block() } finally { latch.countDown() }
        }
        latch.await(10, TimeUnit.SECONDS)
        return out
    }

    private fun executeOnMain(a: Action, w: Int, h: Int): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        main.post {
            runner.execute(a, w, h) { ok = it; latch.countDown() }
        }
        latch.await(70, TimeUnit.SECONDS)
        return ok
    }

    private fun textBlock(t: String) = ClaudeApi.textBlock(t)

    private fun imageBlock(s: Screenshot) = ClaudeApi.imageBlock(s.base64)

    private fun callApi(apiKey: String, model: String, messages: JSONArray): JSONObject {
        // Forced tool choice is rejected by current models, so the tool is requested via the prompt.
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 4096)
            .put("system", SYSTEM_PROMPT)
            .put("tools", JSONArray().put(TOOL))
            .put("tool_choice", JSONObject().put("type", "auto").put("disable_parallel_tool_use", true))
            .put("messages", messages)
        return ClaudeApi.call(apiKey, body, cancelled = { cancelled })
    }

    companion object {
        private const val MAX_STEPS = 40
        private const val MAX_DIM = 1024
        private const val SETTLE_MS = 900L

        private const val SYSTEM_PROMPT =
            "You operate an Android phone for the user through a tool that performs one UI action at a time. " +
                "After every action you receive the new screen as a numbered element list and a screenshot. " +
                "Always respond by calling the act tool, exactly once per turn. " +
                "Work toward the user's goal in as few steps as possible. Prefer tap_node with an element id over raw " +
                "coordinates; use coordinates only for things missing from the list (they are in the screenshot's " +
                "pixel space). To type, first tap the text field, then use type_text (it replaces the field's " +
                "content), then enter if a search or submit is needed. Scroll by swiping the opposite way of the " +
                "direction you want to move. If a screen is still loading, use wait. A small floating TouchBot " +
                "control panel may appear on screen; ignore it. Never enter passwords, payment details or send " +
                "messages the goal did not explicitly ask for. When the goal is achieved call done; if it " +
                "cannot be achieved call fail with the reason."

        private val TOOL: JSONObject = JSONObject(
            """
            {
              "name": "act",
              "description": "Perform one action on the phone.",
              "input_schema": {
                "type": "object",
                "properties": {
                  "action": {"type": "string", "enum": ["tap","tap_node","long_press","long_press_node","swipe","type_text","enter","back","home","recents","open_app","wait","done","fail"]},
                  "id": {"type": "integer", "description": "Element number for tap_node / long_press_node."},
                  "x": {"type": "number"}, "y": {"type": "number"},
                  "x2": {"type": "number", "description": "Swipe end x."}, "y2": {"type": "number", "description": "Swipe end y."},
                  "duration_ms": {"type": "integer"},
                  "text": {"type": "string", "description": "Text for type_text, or app name/package for open_app."},
                  "reason": {"type": "string", "description": "Short note on why; for fail, why the goal is impossible."},
                  "summary": {"type": "string", "description": "For done: what was accomplished."}
                },
                "required": ["action"]
              }
            }
            """.trimIndent()
        )
    }
}
