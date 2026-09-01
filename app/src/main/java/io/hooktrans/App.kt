package io.hooktrans

import android.app.Application
import com.google.android.material.color.DynamicColors
import io.hooktrans.core.Logs
import io.hooktrans.core.Prefs

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Material 3 dynamic colour: on Android 12+ the whole scheme is re-derived from the
        // user's wallpaper. The baseline palette in colors.xml is the fallback below that.
        DynamicColors.applyToActivitiesIfAvailable(this)

        val cfg = Prefs.load(this)
        Logs.verbose = cfg.logVerbose
    }
}
