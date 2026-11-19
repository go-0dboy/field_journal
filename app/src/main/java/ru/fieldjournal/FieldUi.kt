package ru.fieldjournal

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

internal object Palette {
    val paper = 0xfff6f3e9.toInt()
    val white = 0xfffffdf6.toInt()
    val pine = 0xff184536.toInt()
    val green = 0xff21604c.toInt()
    val pale = 0xffe4eddf.toInt()
    val ink = 0xff1d2e25.toInt()
    val muted = 0xff55685d.toInt()
    val border = 0xffd7dfd2.toInt()
    val rust = 0xff96462c.toInt()
}

internal fun Context.dp(value: Number) = (value.toFloat() * resources.displayMetrics.density + 0.5f).toInt()

internal fun Context.panel(color: Int, radius: Int = 16, border: Int = 0) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dp(radius).toFloat()
    if (border != 0) setStroke(dp(1), border)
}

internal fun Context.vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
internal fun Context.horizontal() = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
internal fun Context.label(text: CharSequence, size: Float = 15f, color: Int = Palette.ink, bold: Boolean = false) =
    TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
    }

internal fun Context.action(
    caption: String, primary: Boolean = false, description: String? = null, click: () -> Unit
) = label(caption, 14f, if (primary) Color.WHITE else Palette.pine, true).apply {
    gravity = Gravity.CENTER
    minimumHeight = dp(48)
    setPadding(dp(12), 0, dp(12), 0)
    background = RippleDrawable(ColorStateList.valueOf(0x254b816d),
        panel(if (primary) Palette.green else Palette.white, 12, if (primary) 0 else Palette.border), null)
    if (description != null) contentDescription = description
    isFocusable = true
    accessibilityDelegate = object : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = "android.widget.Button"
        }
    }
    setOnClickListener { click() }
}

internal fun Context.edit(hint: String, value: String, lines: Int = 1) = EditText(this).apply {
    background = panel(Palette.white, 12, Palette.border)
    textSize = 16f
    setTextColor(Palette.ink)
    setHintTextColor(Palette.muted)
    this.hint = hint
    setText(value)
    setPadding(dp(12), dp(10), dp(12), dp(10))
    minHeight = dp(48)
    inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    if (lines > 1) {
        inputType = inputType or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        minLines = lines
        gravity = Gravity.TOP or Gravity.START
    } else setSingleLine(true)
}

internal fun LinearLayout.addSpace(height: Int) = addView(View(context), 1, context.dp(height))

@android.annotation.SuppressLint("ViewConstructor")
internal class SymbolView(context: Context, private val type: ObjectType) : View(context) {
    override fun onDraw(canvas: Canvas) {
        drawSymbol(canvas, width / 2f, height / 2f, minOf(width, height) * 0.44f, type)
    }
}

internal fun drawSymbol(canvas: Canvas, x: Float, y: Float, radius: Float, type: ObjectType) {
    canvas.save()
    canvas.translate(x, y)
    canvas.scale(radius / 16f, radius / 16f)
    val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    canvas.drawCircle(0f, 0f, 15.5f, ink)
    ink.color = type.color
    canvas.drawCircle(0f, 0f, 13.6f, ink)
    ink.color = Color.WHITE

    fun line(vararg points: Float) {
        ink.style = Paint.Style.STROKE
        val path = Path().apply {
            moveTo(points[0], points[1])
            for (index in 2 until points.size step 2) lineTo(points[index], points[index + 1])
        }
        canvas.drawPath(path, ink)
        ink.style = Paint.Style.FILL
    }

    fun ring(cx: Float, cy: Float, r: Float) {
        ink.style = Paint.Style.STROKE
        canvas.drawCircle(cx, cy, r, ink)
        ink.style = Paint.Style.FILL
    }

    fun mound(cx: Float, cy: Float, radius: Float) {
        val width = ink.strokeWidth
        ink.strokeWidth = if (radius < 4f) 1f else 1.55f
        ring(cx, cy, radius * 0.38f)
        for (index in 0 until 8) {
            val angle = index * Math.PI / 4
            val dx = kotlin.math.cos(angle).toFloat() * radius
            val dy = kotlin.math.sin(angle).toFloat() * radius
            line(cx + dx * 0.39f, cy + dy * 0.39f, cx + dx, cy + dy)
        }
        ink.strokeWidth = width
    }

    when (type.id) {
        "camp" -> {
            line(-8f, 7f, 0f, -8f, 8f, 7f, -8f, 7f)
            line(-3f, 7f, 0f, 1f, 3f, 7f)
        }
        "settlement" -> {
            line(-8f, -1f, 0f, -7f, 8f, -1f)
            line(-6f, -2f, -6f, 7f, 6f, 7f, 6f, -2f)
        }
        "fort" -> {
            line(-8f, 7f, -8f, -6f, -4f, -6f, -4f, -2f, 4f, -2f, 4f, -6f, 8f, -6f, 8f, 7f, -8f, 7f)
            line(-2f, 7f, -2f, 3f, 2f, 3f, 2f, 7f)
        }
        "mound" -> mound(0f, 0f, 8f)
        "mounds" -> {
            mound(-5f, -3f, 3.6f)
            mound(5f, -3f, 3.6f)
            mound(0f, 5f, 3.6f)
        }
        "cemetery" -> {
            line(0f, -8f, 0f, 8f)
            line(-5f, -3f, 5f, -3f)
        }
        "find" -> line(0f, -8f, 7f, 0f, 0f, 8f, -7f, 0f, 0f, -8f)
        "excavation" -> {
            line(-7f, -7f, 7f, -7f, 7f, 7f, -7f, 7f, -7f, -7f)
            line(-7f, 2f, 2f, -7f)
            line(-3f, 7f, 7f, -3f)
        }
        "tract" -> {
            line(-9f, 6f, -3f, -4f, 1f, 1f, 5f, -6f, 9f, 6f)
            line(-9f, 6f, 9f, 6f)
        }
        "building" -> {
            line(-8f, -3f, 0f, -8f, 8f, -3f, -8f, -3f)
            line(-6f, 0f, -6f, 7f)
            line(0f, 0f, 0f, 7f)
            line(6f, 0f, 6f, 7f)
            line(-8f, 7f, 8f, 7f)
        }
        "road" -> {
            line(-7f, 7f, 2f, 1f, 3f, -6f)
            line(-2f, 9f, 7f, 3f, 8f, -4f)
        }
        else -> canvas.drawCircle(0f, 0f, 3.5f, ink)
    }
    canvas.restore()
}
