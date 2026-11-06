package com.example.tulis.ui

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
import com.example.tulis.storage.VaultSync
import com.example.tulis.storage.VaultSync.RestoreItem
import com.example.tulis.storage.VaultSync.RestoreMode
import com.example.tulis.storage.VaultSync.RestoreState

fun showRestoreDialog(activity: Activity, onRestored: () -> Unit) {
    val density = activity.resources.displayMetrics.density
    fun dp(v: Float): Int = (v * density).toInt()

    val maxDialogWidth = (420f * density).toInt()
    val sync = VaultSync.get(activity)
    val matchWrap = ViewGroup.LayoutParams.MATCH_PARENT
    val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    var dismissed = false
    var busy = false
    var items: List<RestoreItem> = emptyList()
    var dialogRef: AlertDialog? = null

    val scrollView = ScrollView(activity).apply {
        isFillViewport = true
    }

    val dialogView = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK, variant = 0)
        val pad = dp(20f)
        setPadding(pad, pad, pad, pad)
    }
    scrollView.addView(dialogView, ViewGroup.LayoutParams(matchWrap, wrap))

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

    dialogView.addView(TextView(activity).apply {
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
    dialogView.addView(statusView, block(10f))

    val listBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    dialogView.addView(listBox, block(14f))

    fun setStatus(text: String?, color: Int = Palette.INK_SOFT) {
        statusView.text = text ?: ""
        statusView.setTextColor(color)
        statusView.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    fun stateLabel(state: RestoreState): String = when (state) {
        RestoreState.NEW -> "Belum ada di perangkat"
        RestoreState.DIFFERENT -> "Berbeda dari perangkat"
        RestoreState.SAME -> "Sudah sama"
    }

    var onClickItem: (RestoreItem) -> Unit = {}

    fun row(item: RestoreItem): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK_SOFT, variant = 2)
        setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
        isClickable = true
        isFocusable = true
        isEnabled = !busy
        alpha = if (busy) 0.5f else 1f
        setOnClickListener { onClickItem(item) }
        addView(TextView(activity).apply {
            text = item.title
            setTextColor(Palette.INK)
            textSize = 17f
            typeface = Typeface.create(Fonts.ui(context), Typeface.BOLD)
        })
        val sub = listOfNotNull(
            "${item.pageCount} halaman",
            item.folderPath.takeIf { it.isNotEmpty() },
            stateLabel(item.state)
        ).joinToString(" · ")
        addView(TextView(activity).apply {
            text = sub
            setTextColor(Palette.INK_SOFT)
            textSize = 14f
            typeface = Fonts.ui(context)
        })
    }

    fun renderRows() {
        listBox.removeAllViews()
        for (item in items) listBox.addView(row(item), block(8f))
    }

    fun load() {
        items = emptyList()
        renderRows()
        setStatus("Memuat…")
        sync.listRestorable { list, err ->
            if (dismissed) return@listRestorable
            if (list == null) {
                setStatus("Galat: ${err ?: "gagal memuat daftar"}", Palette.RED)
                return@listRestorable
            }
            items = list
            renderRows()
            setStatus(if (list.isEmpty()) "Tidak ada cadangan di vault." else null)
        }
    }

    fun restore(item: RestoreItem, mode: RestoreMode) {
        if (busy) return
        busy = true
        setStatus("Memulihkan…")
        renderRows()
        sync.restore(item.id, mode) { ok, err ->
            busy = false
            if (ok) {
                Toast.makeText(activity, "Dipulihkan: ${item.title}", Toast.LENGTH_SHORT).show()
                onRestored()
                if (!dismissed) load()
            } else {
                Toast.makeText(activity, err ?: "Gagal memulihkan", Toast.LENGTH_SHORT).show()
                if (!dismissed) {
                    setStatus(null)
                    renderRows()
                }
            }
        }
    }

    fun showChoice(item: RestoreItem) {
        var choice: AlertDialog? = null
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK, variant = 0)
            val pad = dp(20f)
            setPadding(pad, pad, pad, pad)
        }
        line(box, "\"${item.title}\" di perangkat berbeda dari vault.", Palette.INK, 16f, 14f)
        box.addView(actionButton("Ganti yang di perangkat", true) {
            choice?.dismiss()
            restore(item, RestoreMode.REPLACE)
        }, block(4f))
        line(box, "Yang lama dipindah ke sampah.", Palette.INK_SOFT, 13f, 10f)
        box.addView(actionButton("Simpan sebagai salinan", false) {
            choice?.dismiss()
            restore(item, RestoreMode.COPY)
        }, block(8f))
        box.addView(actionButton("Batal", false) { choice?.dismiss() }, block(0f))
        choice = AlertDialog.Builder(activity)
            .setView(box)
            .show().apply {
                window?.setBackgroundDrawableResource(android.R.color.transparent)
                window?.setLayout(
                    Math.min(activity.resources.displayMetrics.widthPixels - dp(32f), maxDialogWidth),
                    wrap
                )
            }
    }

    fun onItemClick(item: RestoreItem) {
        when (item.state) {
            RestoreState.NEW -> restore(item, RestoreMode.REPLACE)
            RestoreState.DIFFERENT -> showChoice(item)
            RestoreState.SAME -> Toast.makeText(activity, "Sudah sama dengan vault", Toast.LENGTH_SHORT).show()
        }
    }

    onClickItem = { onItemClick(it) }

    val closeRow = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
    }
    closeRow.addView(
        actionButton("Tutup", false) { dialogRef?.dismiss() },
        LinearLayout.LayoutParams(wrap, dp(44f))
    )
    dialogView.addView(closeRow, block(0f))

    dialogRef = AlertDialog.Builder(activity)
        .setView(scrollView)
        .setOnDismissListener { dismissed = true }
        .show().apply {
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            window?.setLayout(
                Math.min(activity.resources.displayMetrics.widthPixels - dp(32f), maxDialogWidth),
                wrap
            )
        }

    load()
}
