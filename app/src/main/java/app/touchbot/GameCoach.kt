package app.touchbot

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** How often the screen is sampled. The accessibility screenshot API allows about 3 per second. */
private const val FRAME_INTERVAL_MS = 400L

/**
 * Watches the screen while the user plays and pairs each touch with what the screen looked
 * like just before it. Moments where the user did nothing are kept too, so the policy
 * learns when to wait.
 */
class GameWatcher(private val svc: AutomationService, private val mask: () -> FloatArray?) {
    private val lock = Any()
    private val samples = ArrayList<Sample>()
    private var latest: ByteArray? = null
    private var latestUsed = true
    private var pending: ByteArray? = null
    private var touching = false
    private var lastIdle: ByteArray? = null
    @Volatile private var running = false
    private var thread: Thread? = null

    val moves: Int get() = synchronized(lock) { samples.count { it.action != null } }

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "touchbot-watch").also { it.start() }
    }

    /** Stops watching and returns everything observed. */
    fun stop(): List<Sample> {
        running = false
        thread?.interrupt()
        thread = null
        return synchronized(lock) { ArrayList(samples) }
    }

    /** The user put a finger down: remember the screen as it was just before. */
    fun touchDown() = synchronized(lock) {
        pending = latest
        latestUsed = true
        touching = true
    }

    /** The touch that started at the last [touchDown] turned out to be [action]. */
    fun record(action: Action, w: Int, h: Int) = synchronized(lock) {
        val feat = pending ?: return@synchronized
        samples.add(Sample(feat, action.copy(delay = 0), w, h))
        pending = null
    }

    /** The touch has been passed on to the game; frames count as "between moves" again. */
    fun touchEnd() = synchronized(lock) { touching = false }

    private fun loop() {
        var interval = FRAME_INTERVAL_MS
        while (running) {
            val t0 = SystemClock.uptimeMillis()
            val bmp = svc.captureBitmap()
            if (bmp == null) {
                if (svc.lastShotError == 3) interval = minOf(interval + 200, 1200)
            } else {
                val feat = svc.fingerprint(bmp, mask())
                bmp.recycle()
                if (feat != null) onFrame(feat)
            }
            try {
                Thread.sleep((interval - (SystemClock.uptimeMillis() - t0)).coerceAtLeast(30))
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    private fun onFrame(feat: ByteArray) = synchronized(lock) {
        val prev = latest
        if (prev != null && !latestUsed && !touching) {
            // A whole frame interval passed without a touch: the player chose to wait here.
            val last = lastIdle
            if (last == null || Features.distance(last, prev) > 0.004f) {
                samples.add(Sample(prev, null, 0, 0))
                lastIdle = prev
            }
        }
        latest = feat
        latestUsed = touching
    }
}

/**
 * Plays the game: copies the user's demonstrated moves whenever the screen looks familiar,
 * and asks Claude (armed with the researched guide) what to do when it does not.
 */
class GamePlayer(
    private val svc: AutomationService,
    private val runner: ActionRunner,
    private val mask: () -> FloatArray?,
) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var cancelled = false
    @Volatile var running = false
        private set

    fun cancel() {
        cancelled = true
    }

    fun start(
        profile: GameProfile, samples: List<Sample>, apiKey: String, model: String,
        onStatus: (String) -> Unit, onFinish: (String) -> Unit,
    ) {
        if (running) return
        running = true
        cancelled = false
        Thread({
            val msg = try {
                loop(profile, samples, apiKey, model) { s -> main.post { onStatus(s) } }
            } catch (e: Exception) {
                "Auto-play error: ${e.message ?: e.javaClass.simpleName}"
            }
            running = false
            main.post { onFinish(msg) }
        }, "touchbot-play").start()
    }

    private fun loop(
        profile: GameProfile, demo: List<Sample>, apiKey: String, model: String, status: (String) -> Unit,
    ): String {
        val all = ArrayList(demo)
        var policy = Policy(all, profile.threshold)
        val style = Trainer.describeStyle(demo)
        val history = ArrayList<String>()
        var moves = 0; var assists = 0
        var unknownStreak = 0; var repeatStreak = 0; var lastIndex = -1
        var lastFeat: ByteArray? = null
        var lastAi = 0L
        var interval = FRAME_INTERVAL_MS
        var aiNote = ""

        fun summary() = "$moves moves copied from you" + if (assists > 0) ", $assists AI assists" else ""

        while (!cancelled) {
            val t0 = SystemClock.uptimeMillis()
            val fg = svc.foregroundPackage()
            if (fg != null && fg != profile.pkg) {
                status("Paused - ${profile.label} is not on screen")
                if (!pause(1000)) break
                continue
            }
            val bmp = svc.captureBitmap()
            if (bmp == null) {
                if (svc.lastShotError == 3) interval = minOf(interval + 200, 1200)
                if (!pause(interval)) break
                continue
            }
            val feat = svc.fingerprint(bmp, mask())
            if (feat == null) { bmp.recycle(); if (!pause(interval)) break; continue }

            var decision = policy.decide(feat)
            if (decision is Decision.Act) {
                // The same move over and over on a screen that never changes means it is not working.
                val still = lastFeat?.let { Features.distance(it, feat) < 0.003f } ?: false
                repeatStreak = if (decision.index == lastIndex && still) repeatStreak + 1 else 0
                lastIndex = decision.index
                if (repeatStreak >= 5) decision = Decision.Unknown(0f)
            }
            lastFeat = feat

            when (decision) {
                is Decision.Act -> {
                    unknownStreak = 0
                    val s = decision.sample
                    if (execute(s.action!!, s.w, s.h)) moves++
                    status("Playing like you: ${summary()}$aiNote")
                }
                is Decision.Idle -> {
                    unknownStreak = 0
                    status("Playing like you: ${summary()} (waiting)$aiNote")
                }
                is Decision.Unknown -> {
                    unknownStreak++
                    val now = SystemClock.uptimeMillis()
                    val stuck = repeatStreak >= 5
                    if (apiKey.isEmpty()) {
                        status("Unfamiliar screen - waiting (${summary()}). Add an API key so the AI can handle these.")
                    } else if (assists >= MAX_ASSISTS) {
                        status("Unfamiliar screen - AI assist limit reached (${summary()})")
                    } else if ((unknownStreak >= 3 || stuck) && now - lastAi > 2500) {
                        status("Unfamiliar screen - asking the AI (${summary()})")
                        val (sw, sh) = svc.screenSize()
                        val scale = minOf(1f, AI_DIM.toFloat() / maxOf(sw, sh))
                        val shot = svc.toJpeg(bmp, scale)
                        bmp.recycle()
                        val choice = try {
                            if (shot == null) null else askAi(profile, style, history, shot, scale, stuck, apiKey, model)
                        } catch (e: Exception) {
                            aiNote = " (AI: ${e.message ?: "error"})"
                            null
                        }
                        lastAi = SystemClock.uptimeMillis()
                        assists++
                        repeatStreak = 0; lastIndex = -1; unknownStreak = 0
                        if (cancelled) break
                        if (choice != null) {
                            aiNote = ""
                            history.add(choice.note)
                            if (history.size > 6) history.removeAt(0)
                            status("AI: ${choice.note}")
                            val a = choice.action
                            if (a == null) {
                                // "Wait" on this screen: remember it so the AI is not asked again.
                                all.add(Sample(feat, null, 0, 0))
                                policy = Policy(all, profile.threshold)
                                if (!pause(choice.waitMs)) break
                            } else if (execute(a, sw, sh)) {
                                if (!pause(900)) break
                                // Keep the move for this session only if it actually changed the screen.
                                val after = svc.captureBitmap()
                                val afterFeat = after?.let { b -> svc.fingerprint(b, mask()).also { b.recycle() } }
                                if (afterFeat != null && Features.distance(feat, afterFeat) > 0.01f && a.type != "back") {
                                    all.add(Sample(feat, a, sw, sh))
                                    policy = Policy(all, profile.threshold)
                                }
                            }
                        }
                        if (!pause(FRAME_INTERVAL_MS)) break
                        continue
                    } else {
                        status("Unfamiliar screen - looking (${summary()})")
                    }
                }
            }
            bmp.recycle()
            if (!pause((interval - (SystemClock.uptimeMillis() - t0)).coerceAtLeast(30))) break
        }
        return "Auto-play stopped: ${summary()}"
    }

    /** Sleeps in small steps; returns false if playback was cancelled meanwhile. */
    private fun pause(ms: Long): Boolean {
        var left = ms
        while (left > 0 && !cancelled) {
            val step = minOf(left, 100)
            Thread.sleep(step)
            left -= step
        }
        return !cancelled
    }

    private fun execute(a: Action, w: Int, h: Int): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        main.post {
            if (cancelled) latch.countDown()
            else runner.execute(a.copy(delay = 0), w, h) { ok = it; latch.countDown() }
        }
        latch.await(70, TimeUnit.SECONDS)
        return ok
    }

    private class Choice(val action: Action?, val note: String, val waitMs: Long)

    private fun askAi(
        profile: GameProfile, style: String, history: List<String>, shot: Screenshot, scale: Float,
        stuck: Boolean, apiKey: String, model: String,
    ): Choice? {
        val system = buildString {
            append("You help an automation play the Android game \"").append(profile.label)
            append("\" on the user's own device, at their request. A local model copies the user's recorded ")
            append("play and calls you only for screens it does not recognise: menus, pop-ups, game over, ")
            append("level select, tutorials. Look at the screenshot and choose ONE action that gets the game ")
            append("back to normal play, the way this player would. Always answer by calling the act tool.\n")
            append("Rules: never buy anything or confirm a purchase; never open ads, links or other apps; ")
            append("never type text or sign in; close offers and ads with their close button or back; if the ")
            append("screen is loading or animating, wait. A small floating TouchBot panel may be visible; ")
            append("never touch it.\n\nHow this player plays: ").append(style)
            if (profile.guide.isNotBlank()) append("\n\nGame guide from research:\n").append(profile.guide)
        }
        val text = buildString {
            append("Screenshot size: ${shot.width}x${shot.height}; coordinates use this space.\n")
            if (stuck) append("The copied move keeps repeating here without changing the screen, so try something else.\n")
            if (history.isNotEmpty()) append("Your recent actions on earlier unfamiliar screens: ${history.joinToString(" | ")}\n")
            append("What single action should be performed now?")
        }
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 2048)
            .put("output_config", JSONObject().put("effort", "low"))
            .put("system", system)
            .put("tools", JSONArray().put(PLAY_TOOL))
            .put("tool_choice", JSONObject().put("type", "auto").put("disable_parallel_tool_use", true))
            .put("messages", JSONArray().put(
                JSONObject().put("role", "user").put(
                    "content", JSONArray().put(ClaudeApi.imageBlock(shot.base64)).put(ClaudeApi.textBlock(text)),
                ),
            ))
        val reply = ClaudeApi.call(apiKey, body, cancelled = { cancelled })
        if (reply.optString("stop_reason") == "refusal") throw IllegalStateException("declined")
        val input = ClaudeApi.firstBlock(reply.getJSONArray("content"), "tool_use")?.optJSONObject("input")
            ?: return null
        val kind = input.optString("action")
        val why = input.optString("reason").take(60)
        val note = if (why.isEmpty()) kind else "$kind - $why"
        fun px(k: String): Float? =
            if (input.has(k)) input.optDouble(k).toFloat().takeIf { !it.isNaN() }?.div(scale) else null
        return when (kind) {
            "tap" -> Choice(Action("tap", x = px("x") ?: return null, y = px("y") ?: return null), note, 0)
            "long_press" -> Choice(
                Action("long_press", x = px("x") ?: return null, y = px("y") ?: return null, duration = 900), note, 0,
            )
            "swipe" -> Choice(
                Action(
                    "swipe", x = px("x") ?: return null, y = px("y") ?: return null,
                    x2 = px("x2") ?: return null, y2 = px("y2") ?: return null,
                    duration = input.optLong("duration_ms", 300).coerceIn(80, 3000),
                ), note, 0,
            )
            "back" -> Choice(Action("back"), note, 0)
            "wait" -> Choice(null, note, input.optLong("duration_ms", 1500).coerceIn(300, 8000))
            else -> null
        }
    }

    companion object {
        private const val MAX_ASSISTS = 80
        private const val AI_DIM = 768

        private val PLAY_TOOL: JSONObject = JSONObject(
            """
            {
              "name": "act",
              "description": "Perform one action in the game.",
              "input_schema": {
                "type": "object",
                "properties": {
                  "action": {"type": "string", "enum": ["tap","long_press","swipe","back","wait"]},
                  "x": {"type": "number"}, "y": {"type": "number"},
                  "x2": {"type": "number", "description": "Swipe end x."},
                  "y2": {"type": "number", "description": "Swipe end y."},
                  "duration_ms": {"type": "integer", "description": "Swipe or wait length."},
                  "reason": {"type": "string", "description": "A few words on why."}
                },
                "required": ["action"]
              }
            }
            """.trimIndent()
        )
    }
}

/** Looks the game up on the web and writes a short play guide for the auto-player. */
object GameResearcher {
    private const val MAX_CONTINUATIONS = 4

    fun research(label: String, pkg: String, apiKey: String, model: String, cancelled: () -> Boolean): String {
        val prompt =
            "Research the Android game \"$label\" (package $pkg) on the web, then write a play guide for an " +
                "automated player that sees screenshots and can tap, long-press and swipe.\n" +
                "Cover, briefly and concretely: the objective and how a run or level ends; the core loop; the " +
                "exact controls and gestures; the main screens and pop-ups (menus, game over, rewards, ads, " +
                "offers) and how to get past each one back into play; the strategy that matters most; and " +
                "what to avoid (purchases, ads, anything that spends premium currency).\n" +
                "If you cannot find this exact game, say so in the first line and give the best guidance you " +
                "can for its genre. Put the final guide, at most 350 words of plain text, inside " +
                "<guide></guide> tags."
        val user = JSONObject().put("role", "user").put("content", prompt)
        val tools = JSONArray().put(
            JSONObject().put("type", "web_search_20260209").put("name", "web_search").put("max_uses", 6),
        )
        val assistant = JSONArray()
        val text = StringBuilder()
        for (round in 0..MAX_CONTINUATIONS) {
            val messages = JSONArray().put(user)
            // A paused search turn is resumed by sending back everything the model produced so far.
            if (assistant.length() > 0) messages.put(JSONObject().put("role", "assistant").put("content", assistant))
            val body = JSONObject()
                .put("model", model).put("max_tokens", 8000)
                .put("tools", tools).put("messages", messages)
            val reply = ClaudeApi.call(apiKey, body, readTimeoutMs = 300_000, cancelled = cancelled)
            val content = reply.getJSONArray("content")
            for (i in 0 until content.length()) {
                val b = content.getJSONObject(i)
                assistant.put(b)
                if (b.optString("type") == "text") text.append(b.optString("text"))
            }
            val stop = reply.optString("stop_reason")
            if (stop == "refusal") throw IllegalStateException("The AI declined to research this game")
            if (stop != "pause_turn") break
        }
        val all = text.toString()
        val guide = Regex("<guide>(.*?)</guide>", RegexOption.DOT_MATCHES_ALL).findAll(all).lastOrNull()
            ?.groupValues?.get(1) ?: all
        return guide.trim().take(6000).ifEmpty { throw IllegalStateException("The AI returned no guide") }
    }
}
