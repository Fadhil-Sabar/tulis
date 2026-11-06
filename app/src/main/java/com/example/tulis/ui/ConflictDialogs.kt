package com.example.tulis.ui

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.tulis.storage.VaultSync
import com.example.tulis.storage.VaultSync.AttentionKind
import com.example.tulis.storage.VaultSync.ConflictChoice

fun showConflictDialog(activity: Activity, item: VaultSync.AttentionItem, onDone: () -> Unit) {
    val density = activity.resources.displayMetrics.density
    fun dp(v: Float): Int = (v * density).toInt()

    val maxDialogWidth = (420f * density).toInt()
    val sync = VaultSync.get(activity)
    val matchWrap = ViewGroup.LayoutParams.MATCH_PARENT
    val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    val choiceKinds = item.kind == AttentionKind.CONFLICT || item.kind == AttentionKind.VAULT_NEWER

    var dismissed = false
    var busy = false
    var dialogRef: AlertDialog? = null
    val choiceViews = mutableListOf<TextView>()

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

    val statusView = TextView(activity).apply {
        textSize = 16f
        typeface = Fonts.ui(context)
        visibility = View.GONE
    }

    fun setStatus(text: String?, color: Int = Palette.INK_SOFT) {
        statusView.text = text ?: ""
        statusView.setTextColor(color)
        statusView.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    fun setChoicesEnabled(enabled: Boolean) {
        for (v in choiceViews) {
            v.isEnabled = enabled
            v.alpha = if (enabled) 1f else 0.5f
        }
    }

    fun resolve(choice: ConflictChoice) {
        if (busy) return
        busy = true
        setStatus("Memproses…")
        setChoicesEnabled(false)
        sync.resolveConflict(item.id, choice) { ok, err ->
            busy = false
            if (ok) {
                Toast.makeText(activity, "Selesai: ${item.title}", Toast.LENGTH_SHORT).show()
                dialogRef?.dismiss()
                onDone()
            } else {
                Toast.makeText(activity, err ?: "Gagal menyelesaikan konflik", Toast.LENGTH_SHORT).show()
                if (!dismissed) {
                    setStatus(null)
                    setChoicesEnabled(true)
                }
            }
        }
    }

    fun choiceButton(label: String, primary: Boolean, choice: ConflictChoice): TextView =
        actionButton(label, primary) { resolve(choice) }.also { choiceViews.add(it) }

    dialogView.addView(TextView(activity).apply {
        text = item.title
        setTextColor(Palette.INK)
        textSize = 24f
        typeface = Fonts.hand(context, bold = true)
    }, block(14f))

    line(dialogView, item.reason, Palette.INK_SOFT, 16f, 14f)

    dialogView.addView(statusView, block(10f))

    val actionsBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    dialogView.addView(actionsBox, block(14f))

    if (choiceKinds) {
        actionsBox.addView(choiceButton("Pakai versi perangkat", true, ConflictChoice.USE_LOCAL), block(4f))
        line(actionsBox, "Versi vault lama disimpan sebagai cadangan bertanda waktu.", Palette.INK_SOFT, 13f, 10f)
        actionsBox.addView(choiceButton("Pakai versi vault", false, ConflictChoice.USE_VAULT), block(4f))
        line(actionsBox, "Yang di perangkat dipindah ke sampah.", Palette.INK_SOFT, 13f, 10f)
        actionsBox.addView(choiceButton("Simpan keduanya", false, ConflictChoice.KEEP_BOTH), block(4f))
        line(actionsBox, "Versi vault jadi notebook baru, lalu versi perangkat menimpa vault.", Palette.INK_SOFT, 13f, 0f)
    }

    val closeRow = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
    }
    closeRow.addView(
        actionButton(if (choiceKinds) "Batal" else "Tutup", false) { dialogRef?.dismiss() },
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
}
