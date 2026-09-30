package com.pragon.mobile

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * The phone's own Pragon: a chat screen that works with no PC. It is shown
 * whenever the app is not showing the PC's PhoneView page. Text and voice in,
 * spoken replies out, and Gemini can operate the phone through phone_control.
 */
class StandaloneView(
    context: Context,
    private val onMic: () -> Unit,
    private val onSettings: () -> Unit,
    private val onSpeak: (String) -> Unit,
    private val onStopSpeaking: () -> Unit,
) : LinearLayout(context) {

    val statusLine: TextView
    private val spinner: ProgressBar
    private val list: LinearLayout
    private val scroll: ScrollView
    private val input: EditText
    private val history = mutableListOf<JSONObject>()
    private val worker = Executors.newSingleThreadExecutor()
    private var busy = false
    private var muted = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun pill(color: Int, stroke: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        setColor(color)
        setStroke(dp(1), stroke)
    }

    private fun iconButton(label: String, desc: String, click: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 20f
        gravity = Gravity.CENTER
        contentDescription = desc
        setTextColor(0xFF00D4FF.toInt())
        setPadding(dp(10), dp(6), dp(10), dp(6))
        setOnClickListener { click() }
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(0xFF0A0A0F.toInt())
        isClickable = true

        // ---- header ----
        val title = TextView(context).apply {
            text = "P.R.A.G.O.N"
            textSize = 18f
            letterSpacing = 0.08f
            setTextColor(0xFF00D4FF.toInt())
        }
        statusLine = TextView(context).apply {
            textSize = 12f
            setTextColor(0xFF9FB3C8.toInt())
            text = "Standalone mode"
        }
        val titleCol = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(title)
            addView(statusLine)
        }
        spinner = ProgressBar(context).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        val speaker = iconButton("\uD83D\uDD0A", "Mute or unmute spoken replies") { }
        speaker.setOnClickListener {
            muted = !muted
            speaker.text = if (muted) "\uD83D\uDD07" else "\uD83D\uDD0A"
            if (muted) onStopSpeaking()
        }
        val bar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(8), dp(8))
            addView(titleCol, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(spinner, LayoutParams(dp(22), dp(22)))
            addView(speaker)
            addView(iconButton("\u2699", "Settings") { onSettings() })
        }
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // ---- messages ----
        list = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(8))
        }
        scroll = ScrollView(context).apply { addView(list) }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- input row ----
        input = EditText(context).apply {
            hint = "Message Pragon..."
            setHintTextColor(0x66FFFFFF)
            setTextColor(0xFFE8EAF0.toInt())
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 4
            imeOptions = EditorInfo.IME_ACTION_SEND
            background = pill(0xFF15161F.toInt(), 0x3300D4FF)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    submit(text.toString(), false)
                    true
                } else false
            }
        }
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(10))
            addView(input, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(iconButton("\uD83C\uDFA4", "Talk to Pragon") { onMic() })
            addView(iconButton("\u27A4", "Send") { submit(input.text.toString(), false) })
        }
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        addBubble(
            "Hi, I'm Pragon. I work right here on your phone: ask me anything, or tell me to open an app, " +
                "search YouTube or change the volume. Pair with your PC in Settings to link up with it.",
            false
        )
    }

    private fun addBubble(text: String, mine: Boolean): TextView {
        val tv = TextView(context).apply {
            this.text = text
            textSize = 15f
            setTextColor(if (mine) 0xFFFFFFFF.toInt() else 0xFFE8EAF0.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = pill(if (mine) 0xFF0B3A4A.toInt() else 0xFF15161F.toInt(), 0x3300D4FF)
            maxWidth = (resources.displayMetrics.widthPixels * 0.82).toInt()
            setTextIsSelectable(true)
        }
        val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        lp.gravity = if (mine) Gravity.END else Gravity.START
        lp.setMargins(0, dp(4), 0, dp(4))
        list.addView(tv, lp)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        return tv
    }

    /** Send a message (typed or spoken). Spoken messages get spoken replies. */
    fun submit(text: String, viaVoice: Boolean) {
        val t = text.trim()
        if (t.isEmpty() || busy) return
        busy = true
        input.setText("")
        addBubble(t, true)
        val pending = addBubble("...", false)
        spinner.visibility = View.VISIBLE
        worker.execute {
            val reply = try {
                GeminiClient.reply(context, history, t)
            } catch (e: Exception) {
                "Something went wrong: ${e.message}"
            }
            post {
                pending.text = reply
                spinner.visibility = View.GONE
                busy = false
                scroll.fullScroll(View.FOCUS_DOWN)
                if (viaVoice && !muted) onSpeak(reply)
            }
        }
    }
}
