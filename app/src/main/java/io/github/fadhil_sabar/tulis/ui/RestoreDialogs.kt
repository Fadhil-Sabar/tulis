package io.github.fadhil_sabar.tulis.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.fadhil_sabar.tulis.storage.VaultSync
import io.github.fadhil_sabar.tulis.storage.VaultSync.RestoreItem
import io.github.fadhil_sabar.tulis.storage.VaultSync.RestoreMode
import io.github.fadhil_sabar.tulis.storage.VaultSync.RestoreState

fun showRestoreDialog(activity: Activity, onRestored: () -> Unit) {
    val density = activity.resources.displayMetrics.density
    fun dp(v: Float): Int = (v * density).toInt()

    val metrics = activity.resources.displayMetrics
    val dialogWidth = Math.min(metrics.widthPixels - dp(32f), (420f * density).toInt())
    val contentWidth = dialogWidth - dp(40f)
    val maxListHeight = (metrics.heightPixels * 0.55f).toInt()
    val sync = VaultSync.get(activity)
    val matchWrap = ViewGroup.LayoutParams.MATCH_PARENT
    val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    var dismissed = false
    var busy = false
    var items: List<RestoreItem> = emptyList()
    val selected = LinkedHashSet<String>()
    var dialogRef: AlertDialog? = null
    var onRowTap: (RestoreItem) -> Unit = {}
    lateinit var selectAllBtn: TextView
    lateinit var closeBtn: TextView
    lateinit var restoreBtn: TextView

    fun activityAlive(): Boolean = !activity.isFinishing && !activity.isDestroyed

    val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK, variant = 0)
        val pad = dp(20f)
        setPadding(pad, pad, pad, pad)
    }

    fun block(bottom: Float) = LinearLayout.LayoutParams(matchWrap, wrap).apply {
        bottomMargin = dp(bottom)
    }

    fun line(parent: LinearLayout, text: String, color: Int = Palette.INK, size: Float = 16f, bottom: Float = 6f) {
        parent.addView(TextView(activity).apply {
            this.text = text
            setTextColor(color)
            textSize = size
            typeface = Fonts.ui(context)
        }, block(bottom))
    }

    fun actionButton(label: String, primary: Boolean, onClick: () -> Unit): TextView =
        TextView(activity).apply {
            text = label
            setTextColor(if (primary) Palette.PAPER else Palette.INK)
            textSize = 17f
            typeface = Fonts.ui(context)
            gravity = Gravity.CENTER
            background = if (primary) {
                SketchDrawable(fill = Palette.INK, stroke = Palette.INK, variant = 3)
            } else {
                SketchDrawable(fill = Palette.CARD, stroke = Palette.INK_SOFT, variant = 2)
            }
            minimumHeight = dp(44f)
            setPadding(dp(18f), 0, dp(18f), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    root.addView(TextView(activity).apply {
        text = "Pulihkan dari Vault"
        setTextColor(Palette.INK)
        textSize = 24f
        typeface = Fonts.hand(context, bold = true)
    }, block(14f))

    val statusView = TextView(activity).apply {
        textSize = 16f
        typeface = Fonts.ui(context)
        visibility = View.GONE
    }
    root.addView(statusView, block(10f))

    val listBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    val listScroll = ScrollView(activity).apply {
        addView(listBox, ViewGroup.LayoutParams(matchWrap, wrap))
    }
    root.addView(listScroll, block(14f))

    fun setStatus(text: String?, color: Int = Palette.INK_SOFT) {
        statusView.text = text ?: ""
        statusView.setTextColor(color)
        statusView.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    fun setEnabledLook(view: View, on: Boolean) {
        view.isEnabled = on
        view.alpha = if (on) 1f else 0.5f
    }

    fun stateLabel(state: RestoreState): String = when (state) {
        RestoreState.NEW -> "Belum ada di perangkat"
        RestoreState.DIFFERENT -> "Berbeda dari perangkat"
        RestoreState.SAME -> "Sudah sama"
    }

    fun updateFooter() {
        val selectableCount = items.count { it.state != RestoreState.SAME }
        val allSelected = selectableCount > 0 && selected.size == selectableCount
        selectAllBtn.text = if (allSelected) "Kosongkan" else "Pilih semua"
        setEnabledLook(selectAllBtn, !busy && selectableCount > 0)
        restoreBtn.text = "Pulihkan (${selected.size})"
        setEnabledLook(restoreBtn, !busy && selected.isNotEmpty())
        setEnabledLook(closeBtn, !busy)
    }

    fun row(item: RestoreItem): LinearLayout {
        val selectable = item.state != RestoreState.SAME
        val checked = item.id in selected
        val sub = listOfNotNull(
            "${item.pageCount} halaman",
            item.folderPath.takeIf { it.isNotEmpty() },
            stateLabel(item.state)
        ).joinToString(" · ") +
            if (item.state == RestoreState.DIFFERENT) " · akan disimpan sebagai salinan" else ""
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = SketchDrawable(
                fill = Palette.CARD,
                stroke = if (checked) Palette.INK else Palette.INK_SOFT,
                variant = 2
            )
            setPadding(dp(12f), dp(10f), dp(14f), dp(10f))
            isClickable = true
            isFocusable = true
            isEnabled = !busy
            alpha = if (busy || !selectable) 0.5f else 1f
            setOnClickListener { onRowTap(item) }
            addView(TextView(activity).apply {
                text = if (checked) "✓" else ""
                setTextColor(Palette.INK)
                textSize = 16f
                typeface = Fonts.hand(context, bold = true)
                gravity = Gravity.CENTER
                background = SketchDrawable(
                    fill = if (checked) Palette.YELLOW else Palette.CARD,
                    stroke = Palette.INK,
                    variant = 2
                )
                isClickable = false
            }, LinearLayout.LayoutParams(dp(24f), dp(24f)).apply { rightMargin = dp(12f) })
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(activity).apply {
                    text = item.title
                    setTextColor(Palette.INK)
                    textSize = 17f
                    typeface = Typeface.create(Fonts.ui(context), Typeface.BOLD)
                })
                addView(TextView(activity).apply {
                    text = sub
                    setTextColor(Palette.INK_SOFT)
                    textSize = 14f
                    typeface = Fonts.ui(context)
                })
            }, LinearLayout.LayoutParams(0, wrap, 1f))
        }
    }

    fun renderRows() {
        listBox.removeAllViews()
        for (item in items) listBox.addView(row(item), block(8f))
        listBox.measure(
            View.MeasureSpec.makeMeasureSpec(contentWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.UNSPECIFIED
        )
        listScroll.layoutParams = LinearLayout.LayoutParams(
            matchWrap,
            Math.min(listBox.measuredHeight, maxListHeight)
        ).apply { bottomMargin = dp(14f) }
        updateFooter()
    }

    fun load() {
        items = emptyList()
        renderRows()
        setStatus("Memuat…")
        sync.listRestorable { list, err ->
            if (dismissed || !activityAlive()) return@listRestorable
            if (list == null) {
                setStatus("Galat: ${err ?: "gagal memuat daftar"}", Palette.RED)
                return@listRestorable
            }
            items = list
            selected.retainAll(list.filter { it.state != RestoreState.SAME }.map { it.id }.toSet())
            renderRows()
            setStatus(if (list.isEmpty()) "Tidak ada cadangan di vault." else null)
        }
    }

    fun toggleAll() {
        if (busy) return
        val ids = items.filter { it.state != RestoreState.SAME }.map { it.id }
        val all = ids.isNotEmpty() && selected.size == ids.size
        selected.clear()
        if (!all) selected.addAll(ids)
        renderRows()
    }

    onRowTap = { item ->
        if (!busy) {
            if (item.state == RestoreState.SAME) {
                Toast.makeText(activity, "Sudah sama dengan vault", Toast.LENGTH_SHORT).show()
            } else {
                if (!selected.remove(item.id)) selected.add(item.id)
                renderRows()
            }
        }
    }

    fun showSummary(restored: List<RestoreItem>, failed: List<Pair<RestoreItem, String>>) {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK, variant = 0)
            val pad = dp(20f)
            setPadding(pad, pad, pad, pad)
        }
        line(box, "${restored.size} notebook dipulihkan.", Palette.INK, 17f, 12f)
        val copies = restored.filter { it.state == RestoreState.DIFFERENT }
        if (copies.isNotEmpty()) {
            line(box, "Disimpan sebagai salinan karena versi di perangkat berbeda:", Palette.INK_SOFT, 14f, 4f)
            for (item in copies) line(box, "• ${item.title} (dari vault)", Palette.INK, 14f, 2f)
        }
        if (failed.isNotEmpty()) {
            line(box, "Gagal:", Palette.RED, 14f, 4f)
            for ((item, err) in failed) line(box, "• ${item.title}: $err", Palette.RED, 14f, 2f)
        }
        var summary: AlertDialog? = null
        box.addView(actionButton("Selesai", true) { summary?.dismiss() }, block(0f))
        summary = AlertDialog.Builder(activity)
            .setView(box)
            .show().apply {
                window?.setBackgroundDrawableResource(android.R.color.transparent)
                window?.setLayout(dialogWidth, wrap)
            }
    }

    fun finishRestore(restored: List<RestoreItem>, failed: List<Pair<RestoreItem, String>>) {
        busy = false
        selected.clear()
        if (!activityAlive()) return
        showSummary(restored, failed)
        if (restored.isNotEmpty()) onRestored()
        if (dismissed) return
        load()
    }

    fun runRestore() {
        if (busy) return
        val queue = items.filter { it.id in selected && it.state != RestoreState.SAME }
        if (queue.isEmpty()) return
        busy = true
        renderRows()
        val restored = mutableListOf<RestoreItem>()
        val failed = mutableListOf<Pair<RestoreItem, String>>()
        fun step(index: Int) {
            if (index == queue.size) {
                finishRestore(restored, failed)
                return
            }
            val item = queue[index]
            if (!dismissed) {
                setStatus("Memulihkan ${index + 1} dari ${queue.size}…")
                renderRows()
            }
            val mode = if (item.state == RestoreState.DIFFERENT) RestoreMode.COPY else RestoreMode.REPLACE
            sync.restore(item.id, mode) { ok, err ->
                if (ok) {
                    restored.add(item)
                } else {
                    failed.add(item to (err ?: "Gagal memulihkan"))
                }
                step(index + 1)
            }
        }
        step(0)
    }

    selectAllBtn = actionButton("Pilih semua", false) { toggleAll() }
    closeBtn = actionButton("Tutup", false) { dialogRef?.dismiss() }
    restoreBtn = actionButton("Pulihkan (0)", true) { runRestore() }
    val footer = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
    for (btn in listOf(selectAllBtn, closeBtn, restoreBtn)) {
        btn.textSize = 15f
        btn.setPadding(dp(6f), 0, dp(6f), 0)
        footer.addView(btn, LinearLayout.LayoutParams(0, dp(44f), 1f).apply {
            setMargins(dp(4f), 0, dp(4f), 0)
        })
    }
    root.addView(footer, block(0f))

    dialogRef = AlertDialog.Builder(activity)
        .setView(root)
        .setOnDismissListener { dismissed = true }
        .show().apply {
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            window?.setLayout(dialogWidth, wrap)
        }

    load()
}
