package io.github.fadhil_sabar.tulis.ui

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

private fun showSketchScrollDialog(
    activity: Activity,
    title: String,
    titleBottomMarginDp: Float,
    buildBody: (dialogView: LinearLayout, dismiss: () -> Unit) -> Unit
) {
    val density = activity.resources.displayMetrics.density
    fun dp(v: Float): Int = (v * density).toInt()

    val maxDialogWidth = (420f * density).toInt()

    val scrollView = ScrollView(activity).apply {
        isFillViewport = true
    }

    val dialogView = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK, variant = 0)
        val pad = dp(20f)
        setPadding(pad, pad, pad, pad)
    }
    scrollView.addView(
        dialogView,
        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    )

    val titleV = TextView(activity).apply {
        text = title
        setTextColor(Palette.INK)
        textSize = 24f
        typeface = Fonts.hand(context, bold = true)
    }
    dialogView.addView(
        titleV,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(titleBottomMarginDp)
        }
    )

    var dialogRef: AlertDialog? = null
    buildBody(dialogView) { dialogRef?.dismiss() }

    dialogRef = AlertDialog.Builder(activity)
        .setView(scrollView)
        .show().apply {
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            window?.setLayout(
                Math.min(activity.resources.displayMetrics.widthPixels - dp(32f), maxDialogWidth),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
}

fun showGestureSettingsDialog(activity: Activity, onApplied: () -> Unit) {
    val density = activity.resources.displayMetrics.density
    fun dp(v: Float): Int = (v * density).toInt()

    showSketchScrollDialog(activity, "Gestur", 16f) { dialogView, dismiss ->
        fun addActionRow(
            labelText: String,
            bottomMarginDp: Float,
            readAction: () -> TapAction,
            writeAction: (TapAction) -> Unit
        ) {
            var currentAction = readAction()

            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48f)
                isClickable = true
                isFocusable = true
            }

            val rowLabel = TextView(activity).apply {
                text = labelText
                setTextColor(Palette.INK)
                textSize = 16f
                typeface = Fonts.ui(context)
                isClickable = false
                isFocusable = false
            }
            row.addView(
                rowLabel,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(12f)
                }
            )

            val valueChip = TextView(activity).apply {
                textSize = 14f
                typeface = Fonts.ui(context)
                setTextColor(Palette.INK)
                gravity = Gravity.CENTER
                background = SketchDrawable(fill = Palette.PAPER, stroke = Palette.INK_SOFT, cornerDp = 8f)
                minimumHeight = dp(36f)
                setPadding(dp(14f), dp(4f), dp(14f), dp(4f))
                isClickable = false
                isFocusable = false
            }

            fun updateRowUi() {
                val actionLabel = currentAction.label()
                valueChip.text = actionLabel
                row.contentDescription = "$labelText: $actionLabel"
            }

            updateRowUi()
            row.addView(
                valueChip,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )

            row.setOnClickListener {
                showTapActionPicker(activity, labelText, currentAction) { picked ->
                    currentAction = picked
                    writeAction(picked)
                    updateRowUi()
                    onApplied()
                }
            }

            dialogView.addView(
                row,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(bottomMarginDp)
                }
            )
        }

        addActionRow(
            "Ketuk 2 jari",
            12f,
            { UiPrefs.tapAction(activity, 2) },
            { UiPrefs.setTapAction(activity, 2, it) }
        )
        addActionRow(
            "Ketuk 3 jari",
            18f,
            { UiPrefs.tapAction(activity, 3) },
            { UiPrefs.setTapAction(activity, 3, it) }
        )

        val stylusHeader = TextView(activity).apply {
            text = "Tombol stylus"
            setTextColor(Palette.INK_SOFT)
            textSize = 15f
            typeface = Fonts.ui(context)
        }
        dialogView.addView(
            stylusHeader,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8f)
            }
        )

        addActionRow(
            "Tombol belakang",
            12f,
            { UiPrefs.stylusAction(activity, false) },
            { UiPrefs.setStylusAction(activity, false, it) }
        )
        addActionRow(
            "Tombol depan",
            10f,
            { UiPrefs.stylusAction(activity, true) },
            { UiPrefs.setStylusAction(activity, true, it) }
        )

        val stylusNote = TextView(activity).apply {
            text = "Tombol yang ditahan saat menulis tetap jadi penghapus sementara."
            setTextColor(Palette.INK_SOFT)
            textSize = 13f
            typeface = Fonts.ui(context)
        }
        dialogView.addView(
            stylusNote,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(20f)
            }
        )

        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        val closeBtn = TextView(activity).apply {
            text = "Tutup"
            setTextColor(Palette.PAPER)
            textSize = 17f
            typeface = Fonts.ui(context)
            gravity = Gravity.CENTER
            background = SketchDrawable(fill = Palette.INK, stroke = Palette.INK, variant = 3)
            minimumHeight = dp(44f)
            setPadding(dp(20f), 0, dp(20f), 0)
            isClickable = true
            isFocusable = true
            contentDescription = "Tutup pengaturan gestur"
            setOnClickListener { dismiss() }
        }
        btnRow.addView(closeBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialogView.addView(btnRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
}

private fun showTapActionPicker(
    activity: Activity,
    title: String,
    current: TapAction,
    onPick: (TapAction) -> Unit
) {
    val density = activity.resources.displayMetrics.density
    fun dp(v: Float): Int = (v * density).toInt()

    showSketchScrollDialog(activity, title, 12f) { dialogView, dismiss ->
        val actions = TapAction.values()
        for ((index, action) in actions.withIndex()) {
            val active = action == current
            val itemRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(44f)
                setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
                background = if (active) {
                    SketchDrawable(fill = Palette.YELLOW, stroke = Palette.INK, cornerDp = 8f)
                } else {
                    SketchDrawable(fill = Palette.CARD, stroke = Palette.INK_SOFT, cornerDp = 8f)
                }
                isClickable = true
                isFocusable = true
                val labelText = action.label()
                contentDescription = if (active) "$labelText, aktif" else labelText
                setOnClickListener {
                    dismiss()
                    onPick(action)
                }
            }

            val labelV = TextView(activity).apply {
                text = action.label()
                setTextColor(Palette.INK)
                textSize = 16f
                typeface = Fonts.ui(context)
                isClickable = false
                isFocusable = false
            }
            itemRow.addView(
                labelV,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )

            if (active) {
                val checkV = TextView(activity).apply {
                    text = "✓"
                    setTextColor(Palette.INK)
                    textSize = 16f
                    typeface = Fonts.hand(context, bold = true)
                    isClickable = false
                    isFocusable = false
                }
                itemRow.addView(
                    checkV,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginStart = dp(8f)
                    }
                )
            }

            dialogView.addView(
                itemRow,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = if (index == actions.lastIndex) dp(16f) else dp(8f)
                }
            )
        }

        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        val cancelBtn = TextView(activity).apply {
            text = "Batal"
            setTextColor(Palette.INK)
            textSize = 17f
            typeface = Fonts.ui(context)
            gravity = Gravity.CENTER
            background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK_SOFT, variant = 2)
            minimumHeight = dp(44f)
            setPadding(dp(20f), 0, dp(20f), 0)
            isClickable = true
            isFocusable = true
            contentDescription = "Batal pilih aksi gestur"
            setOnClickListener { dismiss() }
        }
        btnRow.addView(cancelBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialogView.addView(btnRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
}
