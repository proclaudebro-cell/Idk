package app.touchbot

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Floating control panel, touch-capture layer used while recording, and AI prompt box. */
class OverlayController(private val svc: AutomationService) {
    private enum class Mode { IDLE, RECORDING, PLAYING, AI }

    private val wm = svc.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val prefs = Prefs(svc)
    private val store = RecordingStore(svc)
    private val runner = ActionRunner(svc)
    private val agent = AiAgent(svc, runner)
    private val density = svc.resources.displayMetrics.density

    private var mode = Mode.IDLE
    private var panel: LinearLayout? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var status: TextView? = null
    private var recBtn: TextView? = null
    private var playBtn: TextView? = null
    private var aiBtn: TextView? = null
    private var extraRow: LinearLayout? = null
    private var scrollRow: LinearLayout? = null
    private var dblRowView: LinearLayout? = null
    private var doubleTapArmed = false
    private var dblBtn: TextView? = null
    private var capture: View? = null
    private var captureLp: WindowManager.LayoutParams? = null
    private var promptBox: View? = null

    private val recorded = ArrayList<Action>()
    private var lastEventTime = 0L
    private var recW = 0
    private var recH = 0

    private fun dp(v: Int) = (v * density).toInt()

    fun isShowing() = panel != null

    fun show() {
        if (panel != null) return
        val p = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(8))
            background = rounded(Color.argb(235, 28, 28, 36), 16)
        }
        val header = LinearLayout(svc).apply { gravity = Gravity.CENTER_VERTICAL }
        val handle = label("☰ TouchBot", 12, Color.LTGRAY).apply { setPadding(dp(4), dp(4), dp(12), dp(4)) }
        val close = label("✕", 14, Color.LTGRAY).apply { setPadding(dp(10), dp(4), dp(4), dp(4)) }
        close.setOnClickListener { hide() }
        header.addView(handle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(close)
        p.addView(header)

        status = label("Ready", 11, Color.rgb(170, 220, 255)).apply {
            maxWidth = dp(190)
            setPadding(dp(4), 0, dp(4), dp(4))
        }
        p.addView(status)

        val row = LinearLayout(svc)
        recBtn = button("● Rec", Color.rgb(200, 50, 50)) { onRecClicked() }
        playBtn = button("▶ Play", Color.rgb(40, 150, 80)) { onPlayClicked() }
        aiBtn = button("✦ AI", Color.rgb(100, 90, 220)) { onAiClicked() }
        row.addView(recBtn); row.addView(playBtn); row.addView(aiBtn)
        p.addView(row)

        extraRow = LinearLayout(svc).apply { visibility = View.GONE }
        extraRow!!.addView(button("◀ Back", Color.rgb(70, 70, 90)) { recordGlobal("back") })
        extraRow!!.addView(button("⌂ Home", Color.rgb(70, 70, 90)) { recordGlobal("home") })
        extraRow!!.addView(button("▣ Apps", Color.rgb(70, 70, 90)) { recordGlobal("recents") })
        p.addView(extraRow)
        scrollRow = LinearLayout(svc).apply { visibility = View.GONE }
        scrollRow!!.addView(button("\u21E9", Color.rgb(70, 70, 90)) { recordSwipe(0f, 0.28f) })
        scrollRow!!.addView(button("\u21E7", Color.rgb(70, 70, 90)) { recordSwipe(0f, -0.28f) })
        scrollRow!!.addView(button("\u21E8", Color.rgb(70, 70, 90)) { recordSwipe(0.3f, 0f) })
        dblBtn = button("2\u00D7 Tap", Color.rgb(70, 70, 90)) { setDoubleTapArmed(!doubleTapArmed) }
        scrollRow!!.addView(button("\u21E6", Color.rgb(70, 70, 90)) { recordSwipe(-0.3f, 0f) })
        p.addView(scrollRow)
        val dblRow = LinearLayout(svc).apply { visibility = View.GONE }
        dblRow.addView(dblBtn!!)
        dblRowView = dblRow
        p.addView(dblRow)

        val lp = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.panelX
            y = prefs.panelY
        }
        attachDrag(handle, lp)
        panel = p
        panelLp = lp
        wm.addView(p, lp)
        refreshButtons()
    }

    fun hide() {
        runner.cancel()
        agent.cancel()
        replayQueue.clear()
        replaying = false
        removePrompt()
        removeCapture()
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        mode = Mode.IDLE
    }

    // ---- recording ----

    private fun onRecClicked() {
        when (mode) {
            Mode.IDLE -> startRecording()
            Mode.RECORDING -> stopRecording()
            else -> setStatus("Busy - stop the current run first")
        }
    }

    private fun startRecording() {
        val (w, h) = svc.screenSize()
        recW = w; recH = h
        recorded.clear()
        lastEventTime = SystemClock.uptimeMillis()
        val view = View(svc).apply {
            background = GradientDrawable().apply {
                setColor(Color.argb(10, 255, 0, 0))
                setStroke(dp(3), Color.argb(200, 255, 40, 40))
            }
            setOnTouchListener(captureTouchListener())
        }
        val lp = overlayParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        )
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        capture = view
        captureLp = lp
        wm.addView(view, lp)
        // Put the control panel back above the capture layer so it stays tappable.
        panel?.let { p ->
            runCatching { wm.removeView(p) }
            wm.addView(p, panelLp)
        }
        mode = Mode.RECORDING
        extraRow?.visibility = View.VISIBLE
        scrollRow?.visibility = View.VISIBLE
        dblRowView?.visibility = View.VISIBLE
        setDoubleTapArmed(false)
        setStatus("Recording - use the phone normally")
        refreshButtons()
    }

    private fun stopRecording() {
        removeCapture()
        extraRow?.visibility = View.GONE
        scrollRow?.visibility = View.GONE
        dblRowView?.visibility = View.GONE
        mode = Mode.IDLE
        if (recorded.isEmpty()) {
            setStatus("Nothing recorded")
        } else {
            val name = "Rec " + SimpleDateFormat("MMM d HH:mm:ss", Locale.US).format(Date())
            store.save(Recording(name, recW, recH, recorded.toList()))
            prefs.selected = name
            setStatus("Saved \"$name\" (${recorded.size} steps)")
        }
        refreshButtons()
    }

    private fun recordGlobal(type: String) {
        if (mode != Mode.RECORDING) return
        val now = SystemClock.uptimeMillis()
        recorded.add(Action(type, delay = delayFrom(now)))
        lastEventTime = now
        runner.execute(Action(type), recW, recH) {}
        setStatus("Recorded $type (${recorded.size} steps)")
    }

    private fun delayFrom(now: Long): Long {
        val d = (now - lastEventTime).coerceIn(0, 20_000)
        return if (recorded.isEmpty()) minOf(d, 300) else d
    }

    private fun captureTouchListener(): View.OnTouchListener {
        var pts = ArrayList<Pt>()
        var downTime = 0L
        var pointerId = -1
        return View.OnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pts = ArrayList()
                    downTime = ev.eventTime
                    pointerId = ev.getPointerId(0)
                    val loc = IntArray(2)
                    v.getLocationOnScreen(loc)
                    v.tag = loc
                    pts.add(Pt(ev.x + loc[0], ev.y + loc[1], 0))
                }
                MotionEvent.ACTION_MOVE -> {
                    val idx = ev.findPointerIndex(pointerId)
                    if (idx >= 0 && pts.isNotEmpty()) {
                        val loc = v.tag as IntArray
                        for (h in 0 until ev.historySize) {
                            pts.add(Pt(
                                ev.getHistoricalX(idx, h) + loc[0], ev.getHistoricalY(idx, h) + loc[1],
                                ev.getHistoricalEventTime(h) - downTime,
                            ))
                        }
                        pts.add(Pt(ev.getX(idx) + loc[0], ev.getY(idx) + loc[1], ev.eventTime - downTime))
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (pts.isNotEmpty() && ev.actionMasked == MotionEvent.ACTION_UP) {
                        val idx = ev.findPointerIndex(pointerId)
                        if (idx >= 0) {
                            val loc = v.tag as IntArray
                            pts.add(Pt(ev.getX(idx) + loc[0], ev.getY(idx) + loc[1], ev.eventTime - downTime))
                        }
                        finishStroke(pts, downTime, ev.eventTime)
                    }
                    pts = ArrayList()
                }
            }
            true
        }
    }

    private fun finishStroke(pts: List<Pt>, downTime: Long, upTime: Long) {
        val first = pts.first()
        val dur = pts.last().t.coerceAtLeast(1)
        val slop = dp(10)
        val action = if (Geometry.pathLength(pts) < slop) {
            if (dur >= 500) Action("long_press", x = first.x, y = first.y, duration = dur)
            else Action("tap", x = first.x, y = first.y)
        } else {
            Action("gesture", duration = dur, points = Geometry.downsample(pts, 40))
        }
        // downTime/upTime are event-time (uptime) values, the same clock as lastEventTime.
        var rec = action
        if (action.type == "tap" && doubleTapArmed) {
            // The toggle makes this single tap register as a double tap, then switches itself off.
            rec = Action("double_tap", x = action.x, y = action.y, duration = 120)
            setDoubleTapArmed(false)
        }
        recorded.add(rec.copy(delay = delayFrom(downTime)))
        lastEventTime = upTime
        setStatus("Recorded ${recorded.size} steps" + if (rec.type == "double_tap") " (double tap)" else "")
        replayThroughCapture(rec.copy(delay = 0))
    }

    private fun setDoubleTapArmed(on: Boolean) {
        doubleTapArmed = on
        dblBtn?.background = rounded(if (on) Color.rgb(230, 150, 20) else Color.rgb(70, 70, 90), 10)
        dblBtn?.text = if (on) "2\u00D7 Tap ON" else "2\u00D7 Tap"
    }

    private fun recordSwipe(dxFrac: Float, dyFrac: Float) {
        if (mode != Mode.RECORDING) return
        val (w, h) = svc.screenSize()
        val cx = w / 2f; val cy = h / 2f
        val a = Action(
            "swipe", x = cx + dxFrac * w, y = cy + dyFrac * h,
            x2 = cx - dxFrac * w, y2 = cy - dyFrac * h, duration = 350,
        )
        val now = SystemClock.uptimeMillis()
        recorded.add(a.copy(delay = delayFrom(now)))
        lastEventTime = now
        setStatus("Recorded scroll (${recorded.size} steps)")
        replayThroughCapture(a)
    }

    private val replayQueue = ArrayDeque<Action>()
    private var replaying = false

    /** Re-injects a captured stroke so the app underneath still receives it. */
    private fun replayThroughCapture(a: Action) {
        replayQueue.addLast(a)
        if (!replaying) nextReplay()
    }

    private fun nextReplay() {
        val a = replayQueue.removeFirstOrNull()
        if (a == null) {
            replaying = false
            setCaptureTouchable(true)
            return
        }
        replaying = true
        setCaptureTouchable(false)
        runner.execute(a, recW, recH) { ok ->
            main.post {
                if (!ok) setStatus("Warning: that touch could not be passed through")
                nextReplay()
            }
        }
    }

    private fun setCaptureTouchable(touchable: Boolean) {
        val v = capture ?: return
        val lp = captureLp ?: return
        lp.flags = if (touchable) lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { wm.updateViewLayout(v, lp) }
    }

    private fun removeCapture() {
        capture?.let { runCatching { wm.removeView(it) } }
        capture = null
        captureLp = null
    }

    // ---- playback ----

    private fun onPlayClicked() {
        when (mode) {
            Mode.PLAYING -> { runner.cancel(); finishPlayback("Stopped") }
            Mode.IDLE -> startPlayback()
            else -> setStatus("Busy - stop the current run first")
        }
    }

    private fun startPlayback() {
        val rec = store.load(prefs.selected)
        if (rec == null) { setStatus("No recording selected - record one or pick one in the app"); return }
        if (rec.actions.isEmpty()) { setStatus("Recording is empty"); return }
        mode = Mode.PLAYING
        refreshButtons()
        val loops = prefs.loops
        runner.run(
            rec.actions, rec.screenW, rec.screenH, loops, prefs.speed,
            onProgress = { loop, i, n ->
                val of = if (loops > 0) "/$loops" else ""
                setStatus("Playing \"${rec.name}\" run ${loop + 1}$of - step ${i + 1}/$n")
            },
            onDone = { ok, err -> finishPlayback(if (ok) "Finished \"${rec.name}\"" else err ?: "Failed") },
        )
    }

    private fun finishPlayback(msg: String) {
        mode = Mode.IDLE
        setStatus(msg)
        refreshButtons()
    }

    // ---- AI ----

    private fun onAiClicked() {
        when (mode) {
            Mode.AI -> { agent.cancel(); setStatus("Stopping...") }
            Mode.IDLE -> if (prefs.apiKey.isEmpty()) setStatus("Add your Anthropic API key in the app first")
            else showPrompt()
            else -> setStatus("Busy - stop the current run first")
        }
    }

    private fun showPrompt() {
        if (promptBox != null) return
        val box = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(Color.rgb(34, 34, 44), 16)
        }
        val input = EditText(svc).apply {
            hint = "What should I do on the phone?"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setText(prefs.lastGoal)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setOnKeyListener { _, code, ev ->
                if (code == KeyEvent.KEYCODE_BACK && ev.action == KeyEvent.ACTION_UP) {
                    removePrompt(); true
                } else false
            }
        }
        box.addView(input)
        val row = LinearLayout(svc).apply { gravity = Gravity.END }
        row.addView(button("Cancel", Color.rgb(70, 70, 90)) { removePrompt() })
        row.addView(button("Run", Color.rgb(100, 90, 220)) {
            val goal = input.text.toString().trim()
            if (goal.isNotEmpty()) {
                prefs.lastGoal = goal
                removePrompt()
                startAi(goal)
            }
        })
        box.addView(row)
        val (w, _) = svc.screenSize()
        val lp = overlayParams(
            (w * 0.92f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(72)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        }
        promptBox = box
        wm.addView(box, lp)
        input.requestFocus()
    }

    private fun removePrompt() {
        promptBox?.let { runCatching { wm.removeView(it) } }
        promptBox = null
    }

    private fun startAi(goal: String) {
        mode = Mode.AI
        refreshButtons()
        setStatus("AI starting...")
        agent.start(goal, prefs.apiKey, prefs.model, onStatus = { setStatus(it) }) { res ->
            mode = Mode.IDLE
            var msg = (if (res.success) "Done: " else "Stopped: ") + res.message
            if (res.success && res.actions.isNotEmpty()) {
                val name = "AI " + goal.take(28).replace('\n', ' ')
                store.save(Recording(name, res.screenW, res.screenH, res.actions))
                prefs.selected = name
                msg += " - saved as replayable \"$name\""
            }
            setStatus(msg)
            refreshButtons()
        }
    }

    // ---- UI helpers ----

    private fun setStatus(s: String) {
        main.post { status?.text = s }
    }

    private fun refreshButtons() {
        recBtn?.text = if (mode == Mode.RECORDING) "■ Stop" else "● Rec"
        playBtn?.text = if (mode == Mode.PLAYING) "■ Stop" else "▶ Play"
        aiBtn?.text = if (mode == Mode.AI) "■ Stop" else "✦ AI"
        recBtn?.alpha = if (mode == Mode.IDLE || mode == Mode.RECORDING) 1f else 0.4f
        playBtn?.alpha = if (mode == Mode.IDLE || mode == Mode.PLAYING) 1f else 0.4f
        aiBtn?.alpha = if (mode == Mode.IDLE || mode == Mode.AI) 1f else 0.4f
    }

    private fun overlayParams(w: Int, h: Int, flags: Int) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT,
    )

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * density
    }

    private fun label(text: String, sp: Int, color: Int) = TextView(svc).apply {
        this.text = text
        textSize = sp.toFloat()
        setTextColor(color)
    }

    private fun button(text: String, color: Int, onClick: () -> Unit) = TextView(svc).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = rounded(color, 10)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }
        setOnClickListener { onClick() }
    }

    private fun attachDrag(handle: View, lp: WindowManager.LayoutParams) {
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f
        handle.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y; touchX = ev.rawX; touchY = ev.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (ev.rawX - touchX).toInt()
                    lp.y = startY + (ev.rawY - touchY).toInt()
                    panel?.let { runCatching { wm.updateViewLayout(it, lp) } }
                }
                MotionEvent.ACTION_UP -> { prefs.panelX = lp.x; prefs.panelY = lp.y }
            }
            true
        }
    }
}
