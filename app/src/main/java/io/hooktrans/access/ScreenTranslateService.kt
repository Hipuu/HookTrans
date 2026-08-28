package io.hooktrans.access

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import io.hooktrans.core.Logs

/**
 * Optional, disabled by default, and not part of the translation path.
 *
 * The Xposed hooks are what actually translate apps. This service exists only as a
 * diagnostic aid for the case where an app renders text in a way no hook can reach (a game
 * drawing glyphs onto a Canvas, for instance): it can *read* the screen so the user can see
 * that the text exists but is unreachable. It deliberately does not modify anything —
 * an accessibility service cannot rewrite another app's text in place, and pretending
 * otherwise would mean drawing an overlay on top of the app, which is exactly the kind of
 * intrusive behaviour this module avoids.
 */
class ScreenTranslateService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Logs.i("accessibility fallback connected (read-only diagnostics)")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally inert. See the class docstring.
    }

    override fun onInterrupt() {}
}
