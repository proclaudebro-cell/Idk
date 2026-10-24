package app.touchbot

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var store: RecordingStore
    private lateinit var root: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var listBox: LinearLayout
    private lateinit var keyField: EditText
    private lateinit var modelField: EditText
    private lateinit var loopsField: EditText
    private lateinit var speedField: EditText

    private val d get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        store = RecordingStore(this)

        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(32), dp(16), dp(24))
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(title("TouchBot"))
        root.addView(body("Record taps and swipes, replay them on a loop, or tell the AI what to do."))

        statusText = body("")
        root.addView(statusText)
        root.addView(button("1. Enable accessibility service") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(small(
            "Installed from an APK and the toggle is greyed out? Open Settings > Apps > TouchBot > " +
                "⋮ menu > Allow restricted settings, then try again."
        ))
        root.addView(button("2. Show floating controls") { showControls() })

        root.addView(section("Playback"))
        loopsField = field("Repeat count (0 = until stopped)", prefs.loops.toString(), InputType.TYPE_CLASS_NUMBER)
        speedField = field("Speed (1 = as recorded, 2 = twice as fast)", prefs.speed.toString(),
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        root.addView(loopsField)
        root.addView(speedField)

        root.addView(section("AI (Anthropic API)"))
        keyField = field("API key (sk-ant-...)", prefs.apiKey,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        modelField = field("Model", prefs.model, InputType.TYPE_CLASS_TEXT)
        root.addView(keyField)
        root.addView(modelField)
        root.addView(small(
            "The key stays on this device. While the AI runs, the screen text and a screenshot are " +
                "sent to Anthropic. Tap ✦ AI in the floating controls to give it a task."
        ))
        root.addView(button("Save settings") { saveSettings(); toast("Saved") })

        root.addView(section("Recordings"))
        root.addView(small("Tap to select what ▶ Play runs. Long-press to rename or delete."))
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        saveSettings()
        super.onPause()
    }

    private fun saveSettings() {
        prefs.apiKey = keyField.text.toString()
        prefs.model = modelField.text.toString()
        prefs.loops = loopsField.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 1
        prefs.speed = speedField.text.toString().toFloatOrNull()?.coerceIn(0.1f, 10f) ?: 1f
    }

    private fun showControls() {
        val svc = AutomationService.instance
        if (svc == null) {
            toast("Enable the TouchBot accessibility service first")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        saveSettings()
        svc.showOverlay()
        moveTaskToBack(true)
    }

    private fun refresh() {
        val on = AutomationService.instance != null
        statusText.text = if (on) "✅ Accessibility service is running" else "⚠ Accessibility service is off"
        statusText.setTextColor(if (on) Color.rgb(30, 130, 60) else Color.rgb(190, 90, 20))

        listBox.removeAllViews()
        val recs = store.list()
        if (recs.isEmpty()) listBox.addView(small("No recordings yet."))
        for (r in recs) {
            val selected = r.name == prefs.selected
            val row = TextView(this).apply {
                text = (if (selected) "▶  " else "     ") + r.name + "\n     ${r.actions.size} steps"
                textSize = 15f
                setTextColor(Color.BLACK)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply {
                    setColor(if (selected) Color.rgb(225, 225, 250) else Color.rgb(240, 240, 240))
                    cornerRadius = 10 * d
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { setMargins(0, dp(4), 0, dp(4)) }
                setOnClickListener { prefs.selected = r.name; refresh() }
                setOnLongClickListener { manage(r); true }
            }
            listBox.addView(row)
        }
    }

    private fun manage(r: Recording) {
        AlertDialog.Builder(this)
            .setTitle(r.name)
            .setItems(arrayOf("Rename", "Delete")) { _, which ->
                if (which == 0) rename(r) else {
                    store.delete(r.name)
                    if (prefs.selected == r.name) prefs.selected = ""
                    refresh()
                }
            }.show()
    }

    private fun rename(r: Recording) {
        val input = EditText(this).apply { setText(r.name) }
        AlertDialog.Builder(this)
            .setTitle("Rename")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotEmpty() && n != r.name) {
                    if (store.rename(r.name, n)) {
                        if (prefs.selected == r.name) prefs.selected = n
                    } else toast("A recording with that name already exists")
                    refresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun title(s: String) = TextView(this).apply {
        text = s; textSize = 28f; setTextColor(Color.BLACK)
    }

    private fun section(s: String) = TextView(this).apply {
        text = s; textSize = 18f; setTextColor(Color.rgb(70, 60, 190))
        setPadding(0, dp(24), 0, dp(4))
    }

    private fun body(s: String) = TextView(this).apply {
        text = s; textSize = 15f; setTextColor(Color.DKGRAY); setPadding(0, dp(6), 0, dp(6))
    }

    private fun small(s: String) = TextView(this).apply {
        text = s; textSize = 12f; setTextColor(Color.GRAY); setPadding(0, dp(2), 0, dp(6))
    }

    private fun field(hintText: String, value: String, type: Int) = EditText(this).apply {
        hint = hintText; setText(value); inputType = type; textSize = 15f
        setSingleLine()
    }

    private fun button(label: String, onClick: (View) -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; gravity = Gravity.CENTER
        setOnClickListener(onClick)
    }
}
