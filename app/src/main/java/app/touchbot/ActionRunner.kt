package app.touchbot

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.accessibilityservice.AccessibilityService
import kotlin.math.max

/** Executes [Action]s on the main thread, one at a time. */
class ActionRunner(private val svc: AutomationService) {
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var token = 0

    val running: Boolean get() = active

    private var active = false

    /** Plays [actions] [loops] times (<= 0 means until cancelled). */
    fun run(
        actions: List<Action>,
        srcW: Int,
        srcH: Int,
        loops: Int,
        speed: Float,
        onProgress: (loop: Int, index: Int, total: Int) -> Unit,
        onDone: (completed: Boolean, error: String?) -> Unit,
    ) {
        val my = ++token
        active = true
        val spd = speed.coerceIn(0.1f, 10f)

        fun finish(ok: Boolean, err: String?) {
            if (token != my) return
            active = false
            onDone(ok, err)
        }

        fun step(loop: Int, i: Int) {
            if (token != my) return
            if (i >= actions.size) {
                if (loops > 0 && loop + 1 >= loops) { finish(true, null); return }
                handler.postDelayed({ step(loop + 1, 0) }, 500)
                return
            }
            val a = actions[i]
            handler.postDelayed({
                if (token != my) return@postDelayed
                onProgress(loop, i, actions.size)
                execute(a.copy(delay = 0, duration = scaleDur(a, spd)), srcW, srcH) { ok ->
                    if (token != my) return@execute
                    if (!ok) finish(false, "Step ${i + 1} (${a.type}) failed")
                    else step(loop, i + 1)
                }
            }, (a.delay / spd).toLong().coerceAtLeast(0))
        }

        if (actions.isEmpty()) { finish(true, null); return }
        step(0, 0)
    }

    private fun scaleDur(a: Action, spd: Float): Long =
        if (a.type == "wait") (a.duration / spd).toLong() else a.duration

    fun cancel() {
        token++
        active = false
        handler.removeCallbacksAndMessages(null)
    }

    /** Runs one action now, scaling coordinates from the source screen size to the current one. */
    fun execute(a: Action, srcW: Int, srcH: Int, onResult: (Boolean) -> Unit) {
        val (w, h) = svc.screenSize()
        val sx = if (srcW > 0) w.toFloat() / srcW else 1f
        val sy = if (srcH > 0) h.toFloat() / srcH else 1f
        fun px(v: Float) = Geometry.clamp(v * sx, w)
        fun py(v: Float) = Geometry.clamp(v * sy, h)
        try {
            when (a.type) {
                "tap" -> gesture(listOf(Pt(px(a.x), py(a.y), 0)), 50, onResult)
                "long_press" -> gesture(listOf(Pt(px(a.x), py(a.y), 0)), max(a.duration, 700), onResult)
                "swipe" -> gesture(
                    listOf(Pt(px(a.x), py(a.y), 0), Pt(px(a.x2), py(a.y2), 0)),
                    if (a.duration > 0) a.duration else 400, onResult,
                )
                "gesture" -> {
                    val pts = a.points.orEmpty()
                    if (pts.isEmpty()) { onResult(false); return }
                    gesture(pts.map { Pt(px(it.x), py(it.y), it.t) }, max(a.duration, 50), onResult)
                }
                "back" -> onResult(svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
                "home" -> onResult(svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
                "recents" -> onResult(svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS))
                "text" -> onResult(svc.typeText(a.text ?: ""))
                "enter" -> onResult(svc.pressEnter())
                "open_app" -> onResult(svc.openApp(a.text ?: "") == null)
                "wait" -> handler.postDelayed({ onResult(true) }, a.duration.coerceAtLeast(0))
                else -> onResult(false)
            }
        } catch (e: Exception) {
            onResult(false)
        }
    }

    private fun gesture(pts: List<Pt>, durationMs: Long, onResult: (Boolean) -> Unit) {
        val path = Path()
        path.moveTo(pts[0].x, pts[0].y)
        if (pts.size > 1) {
            for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
        }
        val dur = durationMs.coerceIn(1, GestureDescription.getMaxGestureDuration())
        val desc = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, dur))
            .build()
        val accepted = svc.dispatchGesture(desc, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = onResult(true)
            override fun onCancelled(gestureDescription: GestureDescription?) = onResult(false)
        }, handler)
        if (!accepted) onResult(false)
    }
}
