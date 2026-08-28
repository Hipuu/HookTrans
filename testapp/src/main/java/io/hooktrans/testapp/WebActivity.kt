package io.hooktrans.testapp

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity

/**
 * The WebView target. A third text pipeline that shares nothing with the other two.
 *
 * The page deliberately adds a paragraph after load, because that is the case the module's
 * MutationObserver exists for: content that was not in the HTML the app shipped.
 */
class WebActivity : AppCompatActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val wv = WebView(this)
        wv.settings.javaScriptEnabled = true
        setContentView(wv)

        val html = """
            <html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>body{font:16px sans-serif;padding:20px;line-height:1.7}</style></head>
            <body>
              <h2>Account overview</h2>
              <p>Your account is currently active and in good standing.</p>
              <p>Choose a plan that works for you.</p>
              <p>Contact support if you need help with your subscription.</p>
              <p>https://example.com/should/not/change</p>
              <input placeholder="Enter your email address">
              <div id="later"></div>
              <script>
                setTimeout(function(){
                  document.getElementById('later').innerHTML =
                    '<p>This paragraph was added by script after the page loaded.</p>';
                }, 2500);
              </script>
            </body></html>
        """.trimIndent()

        wv.loadDataWithBaseURL("https://local.test/", html, "text/html", "UTF-8", null)

        // Read the DOM back later: the page's own scripts must still see their own text.
        Handler(Looper.getMainLooper()).postDelayed({
            wv.evaluateJavascript("document.querySelectorAll('p')[3].innerText") { v ->
                android.util.Log.i(Verify.TAG, "webview DOM readback (url paragraph) = $v")
            }
        }, 9_000)
    }
}
