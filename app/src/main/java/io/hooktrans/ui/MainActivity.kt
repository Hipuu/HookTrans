package io.hooktrans.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.hooktrans.R
import io.hooktrans.core.Const
import io.hooktrans.core.Engines
import io.hooktrans.core.HookConfig
import io.hooktrans.core.Langs
import io.hooktrans.core.ModuleStatus
import io.hooktrans.core.Prefs
import io.hooktrans.databinding.ActivityMainBinding
import io.hooktrans.ipc.ITranslator
import io.hooktrans.service.EngineClient
import io.hooktrans.service.TranslatorService

/**
 * Settings. Everything the hooks read lives in one [HookConfig], so this screen is a plain
 * form over that object: read it into the views on start, collect it back on save.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var cfg = HookConfig()

    private val engineClient = EngineClient()

    private val pickApps = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode != RESULT_OK) return@registerForActivityResult
        val data = res.data ?: return@registerForActivityResult
        cfg = cfg.copy(
            packages = data.getStringArrayListExtra(AppPickerActivity.EXTRA_PACKAGES)?.toSet() ?: cfg.packages,
            perAppLang = AppPickerActivity.decodeLangMap(data.getStringExtra(AppPickerActivity.EXTRA_PER_APP_LANG)),
        )
        renderAppsSummary()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        applyInsets()

        cfg = Prefs.load(this)
        setupDropdowns()
        bind(cfg)
        wire()

        TranslatorService.start(this)
        engineClient.connect(this) { renderCacheInfo() }
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
        renderCacheInfo()
    }

    override fun onDestroy() {
        engineClient.disconnect(this)
        super.onDestroy()
    }

    /** Edge-to-edge: the FAB and the scroll content must clear the gesture bar. */
    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            b.scroll.updatePadding(left = bars.left, right = bars.right)
            b.content.updatePadding(bottom = bars.bottom + 120)
            (b.btnSave.layoutParams as? androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams)
                ?.let { lp ->
                    lp.bottomMargin = bars.bottom + 16
                    lp.rightMargin = bars.right + 16
                    b.btnSave.layoutParams = lp
                }
            insets
        }
    }

    // ------------------------------------------------------------------ setup

    private fun setupDropdowns() {
        b.ddSource.setSimpleItems(Langs.SOURCES.map { it.second }.toTypedArray())
        b.ddTarget.setSimpleItems(Langs.TARGETS.map { it.second }.toTypedArray())
        b.ddEngine.setAdapter(
            ArrayAdapter(this, R.layout.item_dropdown, Engines.ALL.map { Engines.label(it) })
        )
    }

    private fun wire() {
        b.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_self_test -> { selfTest(); true }
                R.id.action_clear_cache -> { clearCache(); true }
                else -> false
            }
        }

        b.ddEngine.setOnItemClickListener { _, _, pos, _ ->
            cfg = cfg.copy(engine = Engines.ALL.getOrElse(pos) { Engines.GOOGLE_FREE })
            renderEngineFields()
        }
        b.swEnabled.setOnCheckedChangeListener { _, _ -> }
        // The "keep the original visible" option only means anything while image translation
        // is on, so it follows its parent rather than sitting there as a switch that does
        // nothing.
        b.swImages.setOnCheckedChangeListener { _, on ->
            b.swOverlayShowOriginal.isEnabled = on
        }
        b.btnPickApps.setOnClickListener {
            pickApps.launch(
                Intent(this, AppPickerActivity::class.java)
                    .putStringArrayListExtra(AppPickerActivity.EXTRA_PACKAGES, ArrayList(collect().packages))
                    .putExtra(AppPickerActivity.EXTRA_PER_APP_LANG, AppPickerActivity.encodeLangMap(cfg.perAppLang))
                    .putExtra(AppPickerActivity.EXTRA_DEFAULT_LANG, collect().targetLang)
            )
        }
        b.btnSelfTest.setOnClickListener { selfTest() }
        b.btnDownloadModel.setOnClickListener { downloadModel() }
        b.btnClearCache.setOnClickListener { clearCache() }
        b.btnSave.setOnClickListener { save() }

        b.scroll.setOnScrollChangeListener { _, _, y, _, oldY ->
            if (y > oldY + 8) b.btnSave.shrink() else if (y < oldY - 8) b.btnSave.extend()
        }
    }

    // ------------------------------------------------------------------ bind

    private fun bind(c: HookConfig) {
        b.swEnabled.isChecked = c.enabled
        b.ddSource.setText(Langs.nameOf(c.sourceLang), false)
        b.ddTarget.setText(Langs.nameOf(c.targetLang), false)
        b.ddEngine.setText(Engines.label(c.engine), false)
        b.etEndpoint.setText(c.endpoint)
        b.etApiKey.setText(c.apiKey)
        b.swAllApps.isChecked = c.scopeAllApps
        b.swTextViews.isChecked = c.hookTextViews
        b.swHints.isChecked = c.hookHints
        b.swWebViews.isChecked = c.hookWebViews
        b.swCompose.isChecked = c.hookCompose
        b.swCanvas.isChecked = c.hookCanvas
        b.swImages.isChecked = c.hookImages
        b.swOverlayShowOriginal.isChecked = c.overlayShowOriginal
        b.swOverlayShowOriginal.isEnabled = c.hookImages
        b.swPrefetch.isChecked = c.prefetchParsed
        b.swResources.isChecked = c.hookResources
        b.swMaxCompat.isChecked = c.maxCompatibility
        b.swSkipLinked.isChecked = c.skipLinkedText
        b.swSelectable.isChecked = c.translateSelectable
        b.swVerbose.isChecked = c.logVerbose
        b.etMinChars.setText(c.minChars.toString())
        b.etMaxChars.setText(c.maxChars.toString())
        b.etExcludeIds.setText(c.excludeViewIds.joinToString(", "))
        b.etNeverTranslate.setText(c.neverTranslate.joinToString("\n"))
        renderEngineFields()
        renderAppsSummary()
    }

    /** Reads the form back into a config. The dropdowns are matched by label. */
    private fun collect(): HookConfig {
        val srcIdx = Langs.SOURCES.indexOfFirst { it.second == b.ddSource.text.toString() }
        val dstIdx = Langs.TARGETS.indexOfFirst { it.second == b.ddTarget.text.toString() }
        return cfg.copy(
            enabled = b.swEnabled.isChecked,
            sourceLang = Langs.SOURCES.getOrNull(srcIdx)?.first ?: cfg.sourceLang,
            targetLang = Langs.TARGETS.getOrNull(dstIdx)?.first ?: cfg.targetLang,
            endpoint = b.etEndpoint.text?.toString()?.trim().orEmpty(),
            apiKey = b.etApiKey.text?.toString()?.trim().orEmpty(),
            scopeAllApps = b.swAllApps.isChecked,
            hookTextViews = b.swTextViews.isChecked,
            hookHints = b.swHints.isChecked,
            hookWebViews = b.swWebViews.isChecked,
            hookCompose = b.swCompose.isChecked,
            hookCanvas = b.swCanvas.isChecked,
            hookImages = b.swImages.isChecked,
            overlayShowOriginal = b.swOverlayShowOriginal.isChecked,
            prefetchParsed = b.swPrefetch.isChecked,
            hookResources = b.swResources.isChecked,
            maxCompatibility = b.swMaxCompat.isChecked,
            skipLinkedText = b.swSkipLinked.isChecked,
            translateSelectable = b.swSelectable.isChecked,
            logVerbose = b.swVerbose.isChecked,
            minChars = b.etMinChars.text?.toString()?.toIntOrNull()?.coerceIn(1, 100) ?: cfg.minChars,
            maxChars = b.etMaxChars.text?.toString()?.toIntOrNull()?.coerceIn(10, 5000) ?: cfg.maxChars,
            excludeViewIds = b.etExcludeIds.text?.toString().orEmpty()
                .split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
            neverTranslate = b.etNeverTranslate.text?.toString().orEmpty()
                .split('\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        )
    }

    // ------------------------------------------------------------------ render

    private fun renderStatus() {
        val active = ModuleStatus.isActive()
        b.statusTitle.setText(if (active) R.string.status_active else R.string.status_inactive)
        b.statusSub.text =
            if (active) getString(R.string.status_active_sub, ModuleStatus.frameworkName())
            else getString(R.string.status_inactive_sub)

        val bg = if (active) com.google.android.material.R.attr.colorPrimaryContainer
        else com.google.android.material.R.attr.colorErrorContainer
        val fg = if (active) com.google.android.material.R.attr.colorOnPrimaryContainer
        else com.google.android.material.R.attr.colorOnErrorContainer
        b.statusCard.setCardBackgroundColor(themeColor(bg))
        b.statusIcon.setBackgroundColor(themeColor(bg))
        b.statusIcon.imageTintList = android.content.res.ColorStateList.valueOf(themeColor(fg))
        b.statusTitle.setTextColor(themeColor(fg))
        b.statusSub.setTextColor(themeColor(fg))
        b.cacheInfo.setTextColor(themeColor(fg))
    }

    private fun themeColor(attr: Int): Int =
        com.google.android.material.color.MaterialColors.getColor(b.statusCard, attr)

    private fun renderEngineFields() {
        b.tilEndpoint.visibility = if (Engines.needsEndpoint(cfg.engine)) View.VISIBLE else View.GONE
        b.tilApiKey.visibility = if (Engines.needsKey(cfg.engine)) View.VISIBLE else View.GONE
        b.btnDownloadModel.visibility = if (cfg.engine == Engines.MLKIT) View.VISIBLE else View.GONE
    }

    private fun renderAppsSummary() {
        val n = cfg.packages.size
        b.appsSummary.text = resources.getQuantityString(R.plurals.apps_selected, n, n)
        b.btnPickApps.isEnabled = !b.swAllApps.isChecked
    }

    private fun renderCacheInfo() {
        val n = engineClient.cacheCount()
        // getQuantityString takes an Int for selection, but the count itself is formatted from
        // the Long so a cache larger than Int.MAX_VALUE still displays the true number.
        val quantity = n.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        b.cacheInfo.text =
            if (n >= 0) resources.getQuantityString(R.plurals.cache_size, quantity, n) else ""
    }

    // ------------------------------------------------------------------ actions

    private fun save() {
        cfg = collect()
        Prefs.save(this, cfg)
        sendBroadcast(Intent(Const.ACTION_CONFIG_CHANGED).setPackage(packageName))
        TranslatorService.start(this)
        Snackbar.make(b.root, R.string.saved, Snackbar.LENGTH_LONG)
            .setAnchorView(b.btnSave)
            .show()
    }

    private fun selfTest() {
        save()
        b.testResultCard.visibility = View.VISIBLE
        b.testResult.setText(R.string.testing)
        engineClient.run(this) { svc: ITranslator? ->
            val out = try {
                svc?.selfTest(null, cfg.sourceLang, cfg.targetLang) ?: getString(R.string.engine_unreachable)
            } catch (t: Throwable) {
                "error: ${t.message}"
            }
            runOnUiThread { b.testResult.text = getString(R.string.test_result, out) }
        }
    }

    private fun downloadModel() {
        save()
        b.testResultCard.visibility = View.VISIBLE
        b.testResult.setText(R.string.testing)
        engineClient.run(this) { svc: ITranslator? ->
            val out = try {
                svc?.downloadModel(cfg.sourceLang, cfg.targetLang) ?: getString(R.string.engine_unreachable)
            } catch (t: Throwable) {
                "error: ${t.message}"
            }
            runOnUiThread { b.testResult.text = out }
        }
    }

    private fun clearCache() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_clear_cache)
            .setMessage(R.string.confirm_clear_cache)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                engineClient.run(this) { svc ->
                    runCatching { svc?.clearCache() }
                    runOnUiThread {
                        renderCacheInfo()
                        Snackbar.make(b.root, R.string.cache_cleared, Snackbar.LENGTH_SHORT)
                            .setAnchorView(b.btnSave).show()
                    }
                }
            }
            .show()
    }
}
