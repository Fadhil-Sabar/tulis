package com.example.tulis.ui

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.tulis.storage.VaultSync
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun showVaultDialog(activity: Activity, onPick: () -> Unit, onRestored: () -> Unit) {
    val density = activity.resources.displayMetrics.density
    fun dp(v: Float): Int = (v * density).toInt()

    val maxDialogWidth = (420f * density).toInt()
    val sync = VaultSync.get(activity)
    val dateFmt = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale("id", "ID"))
    val matchWrap = ViewGroup.LayoutParams.MATCH_PARENT
    val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

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
        text = "Cadangan ke Vault"
        setTextColor(Palette.INK)
        textSize = 24f
        typeface = Fonts.hand(context, bold = true)
    }, block(14f))

    val infoBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    dialogView.addView(infoBox, block(14f))

    val actionsBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    dialogView.addView(actionsBox, block(14f))

    line(dialogView, "Vault harus ada di penyimpanan bersama (mis. Documents), bukan Android/data. Jika memakai Obsidian Sync, nyalakan 'Sync all other types' agar berkas .tulis ikut tersinkron.", Palette.INK_SOFT, 14f, 16f)

    var dialogRef: AlertDialog? = null

    fun render(s: VaultSync.Status) {
        infoBox.removeAllViews()
        actionsBox.removeAllViews()

        when (s.phase) {
            VaultSync.Phase.NOT_CONFIGURED -> line(infoBox, "Belum ada vault. Pilih folder vault Obsidian Anda.")
            VaultSync.Phase.NEED_REPICK -> line(infoBox, "Izin ke vault hilang. Pilih folder vault lagi.")
            else -> {
                line(infoBox, "Vault: ${s.vaultName ?: "-"}")
                val last = if (s.lastSuccessAt > 0) dateFmt.format(Date(s.lastSuccessAt)) else "belum"
                line(infoBox, "Terakhir berhasil: $last")
                if (s.phase == VaultSync.Phase.RUNNING || s.pending > 0) {
                    line(infoBox, "Menunggu: ${s.pending}")
                }
            }
        }
        s.lastError?.let { line(infoBox, "Galat: $it", Palette.RED) }
        for ((title, reason) in s.attention) {
            line(infoBox, "• $title — $reason", Palette.INK_SOFT, 15f)
        }

        val needsPick = s.phase == VaultSync.Phase.NOT_CONFIGURED || s.phase == VaultSync.Phase.NEED_REPICK
        actionsBox.addView(
            actionButton(if (s.phase == VaultSync.Phase.NOT_CONFIGURED) "Pilih folder vault" else "Ganti vault", needsPick) {
                dialogRef?.dismiss()
                onPick()
            },
            block(8f)
        )
        if (s.phase == VaultSync.Phase.IDLE || s.phase == VaultSync.Phase.RUNNING) {
            actionsBox.addView(
                actionButton("Cadangkan sekarang", s.phase == VaultSync.Phase.IDLE) {
                    sync.requestSync(null, now = true)
                },
                block(8f)
            )
            actionsBox.addView(
                actionButton("Pulihkan dari vault", false) { showRestoreDialog(activity, onRestored) },
                block(8f)
            )
        }
        if (s.phase != VaultSync.Phase.NOT_CONFIGURED) {
            actionsBox.addView(actionButton("Putuskan vault", false) { sync.clearVault() }, block(8f))
        }
    }

    render(sync.status())

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
        .setOnDismissListener { sync.setListener(null) }
        .show().apply {
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            window?.setLayout(
                Math.min(activity.resources.displayMetrics.widthPixels - dp(32f), maxDialogWidth),
                wrap
            )
        }

    sync.setListener { render(it) }
}
