package app.touchbot

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Text description of the visible screen plus the tap target for every numbered node. */
class ScreenDump(val text: String, val centers: List<PointF>)

class Screenshot(val base64: String, val width: Int, val height: Int)

class AutomationService : AccessibilityService() {
    private var overlay: OverlayController? = null

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        overlay?.hide()
        overlay = null
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        overlay?.hide()
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun showOverlay() {
        val o = overlay ?: OverlayController(this).also { overlay = it }
        o.show()
    }

    fun overlayVisible(): Boolean = overlay?.isShowing() == true

    @Suppress("DEPRECATION")
    fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dm = android.util.DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun pressEnter(): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val root = rootInActiveWindow ?: return false
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    private fun findEditable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (n.isEditable && n.isFocused) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            findEditable(c)?.let { return it }
        }
        return null
    }

    /** Returns null on success or an error message. */
    fun openApp(query: String): String? {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
        val q = query.trim()
        val hit = apps.firstOrNull { it.activityInfo.packageName.equals(q, true) }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().equals(q, true) }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().contains(q, true) }
            ?: return "No installed app matches \"$query\""
        val intent = pm.getLaunchIntentForPackage(hit.activityInfo.packageName)
            ?: return "Cannot launch ${hit.activityInfo.packageName}"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        startActivity(intent)
        return null
    }

    /**
     * Builds a numbered list of interesting nodes in the active window. Coordinates are
     * multiplied by [scale] so they match the (possibly downscaled) screenshot.
     */
    fun dumpScreen(scale: Float): ScreenDump {
        val root = rootInActiveWindow
            ?: return ScreenDump("(no accessibility info for the current screen; rely on the screenshot)", emptyList())
        val sb = StringBuilder()
        val centers = ArrayList<PointF>()
        sb.append("Foreground app: ").append(root.packageName ?: "unknown").append('\n')
        val rect = Rect()
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (centers.size >= MAX_NODES || depth > 40) return
            if (n.isVisibleToUser) {
                val text = n.text?.toString()?.trim().orEmpty()
                val desc = n.contentDescription?.toString()?.trim().orEmpty()
                val interesting = text.isNotEmpty() || desc.isNotEmpty() || n.isClickable ||
                    n.isEditable || n.isCheckable || n.isScrollable
                if (interesting) {
                    n.getBoundsInScreen(rect)
                    if (rect.width() > 0 && rect.height() > 0) {
                        val cx = rect.exactCenterX() * scale
                        val cy = rect.exactCenterY() * scale
                        val flags = buildList {
                            if (n.isClickable) add("clickable")
                            if (n.isEditable) add("editable")
                            if (n.isScrollable) add("scrollable")
                            if (n.isCheckable) add(if (n.isChecked) "checked" else "unchecked")
                            if (n.isFocused) add("focused")
                            if (!n.isEnabled) add("disabled")
                        }
                        sb.append('[').append(centers.size).append("] ")
                            .append(n.className?.toString()?.substringAfterLast('.') ?: "View")
                        if (text.isNotEmpty()) sb.append(" \"").append(text.take(80)).append('"')
                        if (desc.isNotEmpty()) sb.append(" desc=\"").append(desc.take(80)).append('"')
                        sb.append(" center=(").append(cx.toInt()).append(',').append(cy.toInt()).append(')')
                        if (flags.isNotEmpty()) sb.append(' ').append(flags.joinToString(","))
                        sb.append('\n')
                        centers.add(PointF(cx, cy))
                    }
                }
            }
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                walk(c, depth + 1)
            }
        }
        walk(root, 0)
        if (centers.isEmpty()) sb.append("(no readable elements; rely on the screenshot)\n")
        return ScreenDump(sb.toString(), centers)
    }

    /** Captures the screen as a JPEG scaled by [scale]. Blocks; call from a worker thread. */
    fun screenshot(scale: Float): Screenshot? {
        if (Build.VERSION.SDK_INT < 30) return null
        val latch = CountDownLatch(1)
        var result: Screenshot? = null
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY, Executors.newSingleThreadExecutor(),
                object : TakeScreenshotCallback {
                    override fun onSuccess(shot: ScreenshotResult) {
                        try {
                            val hw = Bitmap.wrapHardwareBuffer(shot.hardwareBuffer, shot.colorSpace)
                            if (hw != null) {
                                val soft = hw.copy(Bitmap.Config.ARGB_8888, false)
                                val w = (soft.width * scale).toInt().coerceAtLeast(1)
                                val h = (soft.height * scale).toInt().coerceAtLeast(1)
                                val scaled = Bitmap.createScaledBitmap(soft, w, h, true)
                                val out = ByteArrayOutputStream()
                                scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
                                result = Screenshot(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), w, h)
                            }
                            shot.hardwareBuffer.close()
                        } catch (e: Exception) {
                            // fall through with null result
                        }
                        latch.countDown()
                    }

                    override fun onFailure(errorCode: Int) {
                        latch.countDown()
                    }
                },
            )
        } catch (e: Exception) {
            return null
        }
        latch.await(5, TimeUnit.SECONDS)
        return result
    }

    companion object {
        private const val MAX_NODES = 220

        @Volatile
        var instance: AutomationService? = null
            private set
    }
}
