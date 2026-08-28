package io.hooktrans.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.hooktrans.R
import io.hooktrans.core.Langs
import io.hooktrans.databinding.ActivityAppPickerBinding
import io.hooktrans.databinding.ItemAppBinding
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Picks which apps get translated, with an optional per-app target language.
 *
 * The list is loaded off the main thread because `loadLabel` and `loadIcon` both hit the
 * package manager and the APK, which is slow enough to jank the first frame on a device with
 * a few hundred apps.
 */
class AppPickerActivity : AppCompatActivity() {

    private lateinit var b: ActivityAppPickerBinding
    private lateinit var adapter: AppAdapter

    private val selected = LinkedHashSet<String>()
    private val perAppLang = HashMap<String, String>()
    private var defaultLang = "en"

    private var all: List<AppRow> = emptyList()
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ht-apps").apply { isDaemon = true }
    }

    /**
     * One row as the list renders it.
     *
     * [selected] and [langLabel] are part of the model rather than something the adapter looks
     * up, and that is the whole reason a tap now shows its tick. `ListAdapter` decides what to
     * rebind by diffing the submitted list; state the list does not contain is state the diff
     * cannot see, so when selection lived only in the activity's `selected` set every row
     * compared equal to its predecessor, no rebind was dispatched, and the checkbox changed
     * only when scrolling happened to recycle that row. Worse, the tap *had* registered, so a
     * second tap on an apparently-unticked row silently deselected the app — which is how apps
     * the user believed they had picked ended up out of scope entirely.
     */
    data class AppRow(
        val pkg: String,
        val label: String,
        val icon: Drawable?,
        val system: Boolean,
        val selected: Boolean = false,
        val langLabel: String = "",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityAppPickerBinding.inflate(layoutInflater)
        setContentView(b.root)

        selected += intent.getStringArrayListExtra(EXTRA_PACKAGES).orEmpty()
        perAppLang += decodeLangMap(intent.getStringExtra(EXTRA_PER_APP_LANG))
        defaultLang = intent.getStringExtra(EXTRA_DEFAULT_LANG) ?: "en"

        b.toolbar.setNavigationOnClickListener { finishWithResult() }
        b.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_select_all -> {
                    visibleRows().forEach { selected += it.pkg }; refresh(); true
                }
                R.id.action_clear_all -> {
                    visibleRows().forEach { selected -= it.pkg }; refresh(); true
                }
                else -> false
            }
        }
        onBackPressedDispatcher.addCallback(this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = finishWithResult()
            })

        adapter = AppAdapter(
            onToggle = { row ->
                if (!selected.remove(row.pkg)) selected += row.pkg
                refresh()
            },
            onLanguage = { row -> pickLanguage(row) },
        )
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter

        b.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            override fun afterTextChanged(s: Editable?) = refresh()
        })
        b.chipSelected.setOnCheckedChangeListener { _, _ -> refresh() }
        b.chipSystem.setOnCheckedChangeListener { _, _ -> refresh() }

        load()
    }

    private fun load() {
        b.progress.visibility = View.VISIBLE
        io.execute {
            val pm = packageManager
            val rows = runCatching {
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filter { it.packageName != packageName }
                    .map { ai ->
                        AppRow(
                            pkg = ai.packageName,
                            label = runCatching { pm.getApplicationLabel(ai).toString() }
                                .getOrDefault(ai.packageName),
                            icon = runCatching { pm.getApplicationIcon(ai) }.getOrNull(),
                            system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                                (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0,
                        )
                    }
                    .sortedWith(compareBy({ it.system }, { it.label.lowercase() }))
            }.getOrDefault(emptyList())

            runOnUiThread {
                all = rows
                b.progress.visibility = View.GONE
                refresh()
            }
        }
    }

    /**
     * The rows the current filters admit, each stamped with the state the adapter draws.
     *
     * Stamping happens here, on a list that is rebuilt from scratch on every [refresh], so the
     * diff always compares fresh state against what is on screen. The icon is carried by
     * reference from [all], so `AppRow` equality stays cheap and stays correct — two rows for
     * the same package hold the very same `Drawable`.
     */
    private fun visibleRows(): List<AppRow> {
        val q = b.etSearch.text?.toString()?.trim()?.lowercase().orEmpty()
        return all.mapNotNull { row ->
            val on = selected.contains(row.pkg)
            if (!b.chipSystem.isChecked && row.system && !on) return@mapNotNull null
            if (b.chipSelected.isChecked && !on) return@mapNotNull null
            if (q.isNotEmpty() &&
                !row.label.lowercase().contains(q) && !row.pkg.lowercase().contains(q)
            ) return@mapNotNull null
            row.copy(selected = on, langLabel = langLabelOf(row.pkg))
        }
    }

    private fun langLabelOf(pkg: String): String =
        perAppLang[pkg]?.let { Langs.nameOf(it) }
            ?: getString(R.string.lang_default, Langs.nameOf(defaultLang))

    private fun refresh() {
        val rows = visibleRows()
        adapter.submitList(rows)
        b.empty.visibility = if (rows.isEmpty() && b.progress.visibility != View.VISIBLE)
            View.VISIBLE else View.GONE
    }

    private fun pickLanguage(row: AppRow) {
        val names = listOf(getString(R.string.lang_default, Langs.nameOf(defaultLang))) +
            Langs.TARGETS.map { it.second }
        val codes = listOf<String?>(null) + Langs.TARGETS.map { it.first }
        val current = codes.indexOf(perAppLang[row.pkg]).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.per_app_language, row.label))
            .setSingleChoiceItems(names.toTypedArray(), current) { dialog, which ->
                val code = codes.getOrNull(which)
                if (code == null) perAppLang.remove(row.pkg) else perAppLang[row.pkg] = code
                // Choosing a language for an app implies wanting that app translated.
                if (code != null) selected += row.pkg
                dialog.dismiss()
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun finishWithResult() {
        setResult(
            RESULT_OK,
            Intent()
                .putStringArrayListExtra(EXTRA_PACKAGES, ArrayList(selected))
                .putExtra(EXTRA_PER_APP_LANG, encodeLangMap(perAppLang))
        )
        finish()
    }

    // ------------------------------------------------------------------ adapter

    private class AppAdapter(
        val onToggle: (AppRow) -> Unit,
        val onLanguage: (AppRow) -> Unit,
    ) : ListAdapter<AppRow, AppAdapter.VH>(DIFF) {

        class VH(val b: ItemAppBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = getItem(position)
            holder.b.label.text = row.label
            holder.b.pkg.text = row.pkg
            holder.b.icon.setImageDrawable(row.icon)
            holder.b.check.isChecked = row.selected
            holder.b.chipLang.visibility = if (row.selected) View.VISIBLE else View.GONE
            holder.b.chipLang.text = row.langLabel
            holder.b.root.setOnClickListener { onToggle(row) }
            holder.b.chipLang.setOnClickListener { onLanguage(row) }
        }

        companion object {
            val DIFF = object : DiffUtil.ItemCallback<AppRow>() {
                override fun areItemsTheSame(a: AppRow, b: AppRow) = a.pkg == b.pkg

                /**
                 * Compares only what is drawn. `Drawable` has no value equality, so leaving it
                 * in would make every row differ from itself and turn each keystroke in the
                 * search box into a full rebind — but it is the same instance for a given
                 * package, so excluding it loses nothing.
                 */
                override fun areContentsTheSame(a: AppRow, b: AppRow) =
                    a.label == b.label && a.selected == b.selected && a.langLabel == b.langLabel
            }
        }
    }

    companion object {
        const val EXTRA_PACKAGES = "packages"
        const val EXTRA_PER_APP_LANG = "per_app_lang"
        const val EXTRA_DEFAULT_LANG = "default_lang"

        fun encodeLangMap(map: Map<String, String>): String =
            JSONObject().also { o -> map.forEach { (k, v) -> o.put(k, v) } }.toString()

        fun decodeLangMap(json: String?): Map<String, String> {
            if (json.isNullOrBlank()) return emptyMap()
            return runCatching {
                val o = JSONObject(json)
                val out = HashMap<String, String>()
                o.keys().forEach { k -> out[k] = o.optString(k) }
                out
            }.getOrDefault(emptyMap())
        }
    }
}
