package io.hooktrans.testapp

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ClickableSpan
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * The View-system target.
 *
 * The strings here are chosen to cover both what *should* be translated (ordinary UI copy) and
 * what must be left alone (data that happens to live in a TextView). The module's TextGuard is
 * supposed to refuse the second group; if it does not, that is a real bug and it shows up here
 * as text that changed when it should not have.
 */
class MainActivity : AppCompatActivity() {

    /** Copy a user reads. All of this is fair game for translation. */
    private val prose = listOf(
        "Welcome back",
        "Your message has been sent successfully",
        "Settings",
        "Delete this conversation?",
        "Connected",
        "Search for people and groups",
        "No new notifications",
        "OK",
    )

    /**
     * Data that happens to be displayed. A translator that touches any of these is breaking
     * the app: the URL stops resolving, the id stops matching, the template loses its slot.
     */
    private val mustNotTranslate = listOf(
        "https://example.com/api/v2/users",
        "com.example.app.MainActivity",
        "550e8400-e29b-41d4-a716-446655440000",
        "%1\$s sent you %2\$d files",
        "v2.14.3-beta",
        "42",
        "user_profile_image_key",
        "a3f5c9e17b2d4890",
    )

    private val tracked = ArrayList<Pair<String, TextView>>()
    private val byText = HashMap<String, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 60)
        }

        header(root, "Prose — should be translated")
        prose.forEach { s -> root.addView(track(s)) }

        header(root, "Data — must stay identical")
        mustNotTranslate.forEach { s -> root.addView(track(s)) }

        header(root, "Must never be touched")

        // An editable field. Translating what the user typed would corrupt their input.
        root.addView(EditText(this).apply {
            setText("Draft message the user typed")
            hint = "Type a message"
        })

        // A link. Its span offsets index the original string, so a replacement misaligns them.
        root.addView(TextView(this).apply {
            val s = SpannableString("Read the terms of service now")
            s.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {}
            }, 9, 25, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text = s
            setTextColor(Color.parseColor("#1565C0"))
        })

        // A button, which usually carries an all-caps transformation the module must preserve.
        root.addView(Button(this).apply {
            text = "Send message"
            isAllCaps = true
        })

        header(root, "Other pipelines")
        root.addView(CanvasLabelView(this))
        root.addView(Button(this).apply {
            text = "Open Compose screen"
            setOnClickListener { startActivity(Intent(this@MainActivity, ComposeActivity::class.java)) }
        })
        root.addView(Button(this).apply {
            text = "Open WebView screen"
            setOnClickListener { startActivity(Intent(this@MainActivity, WebActivity::class.java)) }
        })

        setContentView(ScrollView(this).apply {
            addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })

        Verify.scheduleChecks(tracked)
        Handler(Looper.getMainLooper()).postDelayed({ Verify.functionalChecks(byText) }, 6_500)

        // Text that arrives after the screen is already built — the "loaded while using the
        // app" half of the requirement. A hook that only runs at inflation misses this.
        Handler(Looper.getMainLooper()).postDelayed({
            val late = track("This text arrived three seconds after launch")
            root.addView(late, 1)
            Verify.scheduleChecks(listOf("This text arrived three seconds after launch" to late))
        }, 3_000)
    }

    private fun header(root: LinearLayout, title: String) {
        root.addView(TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.GRAY)
            setPadding(0, 40, 0, 12)
        })
    }

    private fun track(s: String): TextView {
        val tv = TextView(this).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, 14, 0, 14)
        }
        tracked += s to tv
        byText[s] = tv
        return tv
    }
}
