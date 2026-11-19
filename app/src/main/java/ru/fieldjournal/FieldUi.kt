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
    val paper = 0xfff4f5f0.toInt()
    val white = 0xfffffefa.toInt()
    val pine = 0xff184536.toInt()
    val green = 0xff21604c.toInt()
    val pale = 0xffe5eee5.toInt()
    val ink = 0xff1d2e25.toInt()
    val muted = 0xff55685d.toInt()
    val border = 0xffdbe2d9.toInt()
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
        panel(if (primary) Palette.green else Palette.white, 16, if (primary) 0 else Palette.border), null)
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
    background = panel(Palette.white, 15, Palette.border)
    textSize = 16f
    setTextColor(Palette.ink)
    setHintTextColor(Palette.muted)
    this.hint = hint
    setText(value)
    setPadding(dp(12), dp(10), dp(12), dp(10))
    minHeight = dp(50)
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

internal enum class NavIcon { MAP, PIN, NOTE, DATA }

internal fun Context.navigationAction(title: String, icon: NavIcon, selected: Boolean, click: () -> Unit) =
    action(title, click = click).apply {
        textSize = 12f
        minHeight = dp(56)
        setPadding(dp(2), dp(5), dp(2), dp(5))
        isSelected = selected
        val ink = if (selected) Palette.pine else Palette.muted
        setTextColor(ink)
        background = RippleDrawable(ColorStateList.valueOf(0x254b816d),
            panel(if (selected) Palette.pale else Palette.white, 14), null)
        val glyph = object : android.graphics.drawable.Drawable() {
            private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ink
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                strokeWidth = 1.8f
            }

            override fun draw(canvas: Canvas) {
                canvas.save()
                canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
                canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
                fun path(points: Path.() -> Unit) = canvas.drawPath(Path().apply(points), pen)
                when (icon) {
                    NavIcon.MAP -> path {
                        moveTo(3f, 6f); lineTo(9f, 3f); lineTo(15f, 6f); lineTo(21f, 3f)
                        lineTo(21f, 18f); lineTo(15f, 21f); lineTo(9f, 18f); lineTo(3f, 21f); close()
                        moveTo(9f, 3f); lineTo(9f, 18f)
                        moveTo(15f, 6f); lineTo(15f, 21f)
                    }
                    NavIcon.PIN -> {
                        path {
                            moveTo(12f, 22f)
                            cubicTo(10f, 19f, 5f, 15f, 5f, 10f)
                            cubicTo(5f, 1f, 19f, 1f, 19f, 10f)
                            cubicTo(19f, 15f, 14f, 19f, 12f, 22f)
                        }
                        canvas.drawCircle(12f, 10f, 2.5f, pen)
                    }
                    NavIcon.NOTE -> {
                        canvas.drawRoundRect(5f, 3f, 20f, 21f, 2f, 2f, pen)
                        canvas.drawLine(9f, 3f, 9f, 21f, pen)
                        canvas.drawLine(12f, 9f, 17f, 9f, pen)
                        canvas.drawLine(12f, 13f, 17f, 13f, pen)
                    }
                    NavIcon.DATA -> {
                        canvas.drawRoundRect(3f, 5f, 21f, 20f, 2f, 2f, pen)
                        canvas.drawLine(3f, 10f, 21f, 10f, pen)
                        canvas.drawLine(10f, 14f, 14f, 14f, pen)
                    }
                }
                canvas.restore()
            }

            override fun setAlpha(alpha: Int) { pen.alpha = alpha }
            override fun setColorFilter(colorFilter: ColorFilter?) { pen.colorFilter = colorFilter }
            @Deprecated("Drawable opacity is no longer used")
            override fun getOpacity() = PixelFormat.TRANSLUCENT
        }
        glyph.setBounds(0, 0, dp(23), dp(23))
        compoundDrawablePadding = dp(3)
        setCompoundDrawables(null, glyph, null, null)
    }

@android.annotation.SuppressLint("ViewConstructor")
internal class CatalogRow(context: Context) : LinearLayout(context) {
    private var type = objectType("")
    private val symbol = object : View(context) {
        override fun onDraw(canvas: Canvas) {
            drawSymbol(canvas, width / 2f, height / 2f, minOf(width, height) * 0.45f, type)
        }
    }
    private val title = context.label("", 17f, Palette.ink, true)
    private val detail = context.label("", 13f, Palette.muted)
    init {
        orientation = VERTICAL
        setPadding(0, 0, 0, context.dp(7))
        addView(context.horizontal().apply {
            background = context.panel(Palette.white, 18, Palette.border)
            minimumHeight = context.dp(68)
            setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
            addView(symbol, context.dp(39), context.dp(39))
            addView(context.vertical().apply {
                setPadding(context.dp(10), 0, context.dp(8), 0)
                addView(title)
                addView(detail)
            }, LayoutParams(0, -2, 1f))
            addView(context.label("›", 24f, Palette.green).apply {
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            })
        }, -1, -2)
    }

    fun bind(site: Site) {
        type = objectType(site.type)
        symbol.invalidate()
        title.text = site.title
        detail.text = listOfNotNull(type.title, site.period.ifBlank { null },
            if (site.area) areaLabel(areaSquareMeters(site.vertices)) else null).joinToString(" · ")
    }
}

internal fun Context.locationAction(click: () -> Unit) =
    android.widget.ImageButton(this).apply {
        contentDescription = "Моё место"
        background = RippleDrawable(ColorStateList.valueOf(0x254b816d), panel(Palette.white, 24, Palette.border), null)
        scaleType = android.widget.ImageView.ScaleType.CENTER
        setPadding(0, 0, 0, 0)
        setOnClickListener { click() }
        val target = object : android.graphics.drawable.Drawable() {
            override fun getIntrinsicWidth() = dp(24)
            override fun getIntrinsicHeight() = dp(24)

            private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Palette.pine
                strokeWidth = 2f
                strokeCap = Paint.Cap.ROUND
            }

            override fun draw(canvas: Canvas) {
                canvas.save()
                canvas.translate(bounds.exactCenterX(), bounds.exactCenterY())
                canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
                pen.style = Paint.Style.STROKE
                canvas.drawCircle(0f, 0f, 8f, pen)
                canvas.drawLine(0f, -11f, 0f, -8f, pen)
                canvas.drawLine(0f, 8f, 0f, 11f, pen)
                canvas.drawLine(-11f, 0f, -8f, 0f, pen)
                canvas.drawLine(8f, 0f, 11f, 0f, pen)
                pen.style = Paint.Style.FILL
                canvas.drawCircle(0f, 0f, 3.5f, pen)
                canvas.restore()
            }

            override fun setAlpha(alpha: Int) { pen.alpha = alpha }
            override fun setColorFilter(colorFilter: ColorFilter?) { pen.colorFilter = colorFilter }
            @Deprecated("Drawable opacity is no longer used")
            override fun getOpacity() = PixelFormat.TRANSLUCENT
        }
        setImageDrawable(target)
    }

internal fun Context.editorAction(done: Boolean, click: () -> Unit) =
    android.widget.ImageButton(this).apply {
        contentDescription = if (done) "Сохранить и закрыть" else "Назад"
        minimumWidth = dp(48)
        minimumHeight = dp(48)
        background = RippleDrawable(ColorStateList.valueOf(0x254b816d),
            panel(if (done) Palette.green else Palette.pale, 24), null)
        scaleType = android.widget.ImageView.ScaleType.CENTER
        setPadding(0, 0, 0, 0)
        setOnClickListener { click() }
        setImageDrawable(object : android.graphics.drawable.Drawable() {
            override fun getIntrinsicWidth() = dp(19)
            override fun getIntrinsicHeight() = dp(19)

            private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (done) Color.WHITE else Palette.pine
                style = Paint.Style.STROKE
                strokeWidth = 2f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            private val mark = Path().apply {
                if (done) {
                    moveTo(3.5f, 9.5f)
                    lineTo(7.5f, 13.5f)
                    lineTo(15.5f, 5.5f)
                } else {
                    moveTo(11.5f, 4f)
                    lineTo(6f, 9.5f)
                    lineTo(11.5f, 15f)
                }
            }

            override fun draw(canvas: Canvas) {
                canvas.save()
                canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
                canvas.scale(bounds.width() / 19f, bounds.height() / 19f)
                canvas.drawPath(mark, pen)
                canvas.restore()
            }

            override fun setAlpha(alpha: Int) { pen.alpha = alpha }
            override fun setColorFilter(colorFilter: ColorFilter?) { pen.colorFilter = colorFilter }
            @Deprecated("Drawable opacity is no longer used")
            override fun getOpacity() = PixelFormat.TRANSLUCENT
        })
    }
