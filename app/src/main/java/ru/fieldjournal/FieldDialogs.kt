package ru.fieldjournal

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView

internal data class PromptAction(val title: String, val run: () -> Unit)

internal fun Context.fieldPrompt(
    title: String,
    primary: PromptAction,
    secondary: PromptAction? = null,
    coordinates: String? = null,
    destructive: Boolean = false,
    choices: List<PromptAction> = emptyList(),
    detail: String? = null
) {
    val dialog = AlertDialog.Builder(this).create()
    val body = vertical().apply {
        setPadding(dp(18), dp(20), dp(18), dp(14))
        addView(label(title, 20f, Palette.pine, true).apply {
            gravity = Gravity.CENTER
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }, -1, -2)
        if (detail != null) {
            addSpace(8)
            addView(label(detail, 14f, Palette.muted), -1, -2)
        }
        if (coordinates != null) {
            addSpace(12)
            addView(vertical().apply {
                background = panel(Palette.pale, 16)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                addView(label(coordinates, 17f, Palette.pine, true).apply {
                    gravity = Gravity.CENTER
                    typeface = Typeface.MONOSPACE
                }, -1, -2)
                addSpace(3)
                addView(label("WGS 84 · широта, долгота", 13f, Palette.muted).apply {
                    gravity = Gravity.CENTER
                }, -1, -2)
            }, -1, -2)
        }
        if (choices.isNotEmpty()) {
            addSpace(12)
            addView(horizontal().apply {
                for (choice in choices) {
                    addView(action(choice.title) {
                        dialog.dismiss()
                        choice.run()
                    }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(3) })
                }
            }, -1, -2)
        }
        addSpace(16)
        val main = action(primary.title, primary = true) {
            dialog.dismiss()
            primary.run()
        }
        val left = if (secondary == null) main else {
            addView(main, -1, -2)
            addSpace(6)
            action(secondary.title) {
                dialog.dismiss()
                secondary.run()
            }.apply { if (destructive) setTextColor(Palette.rust) }
        }
        addView(horizontal().apply {
            addView(left, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
            addView(action("Отмена") { dialog.cancel() }, LinearLayout.LayoutParams(0, -2, 1f))
        }, -1, -2)
    }
    dialog.setView(ScrollView(this).apply { addView(body) }, 0, 0, 0, 0)
    dialog.show()
}

internal fun AlertDialog.showFieldSheet() {
    show()
    window?.apply {
        setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        setLayout(minOf(context.resources.displayMetrics.widthPixels, context.dp(520)),
            WindowManager.LayoutParams.WRAP_CONTENT)
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setDimAmount(0.30f)
    }
}
