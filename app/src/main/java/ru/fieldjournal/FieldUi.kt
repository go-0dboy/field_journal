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
    val paper = 0xfff6f6f7.toInt()
    val white = 0xffffffff.toInt()
    val heading = 0xff1a1b20.toInt()
    val accent = 0xff245bbd.toInt()
    val pale = 0xffe9f0fe.toInt()
    val control = 0xffeaecef.toInt()
    val ink = 0xff202126.toInt()
    val muted = 0xff595c65.toInt()
    val border = 0xffdadce1.toInt()
    val controlBorder = 0xff9eafc7.toInt()
    val floatingSurface = 0xf2ffffff.toInt()
    val floatingAccent = 0xf5245bbd.toInt()
    val notice = 0xee182941.toInt()
    val rust = 0xff963c31.toInt()
    val ripple = ColorStateList.valueOf(0x25245bbd)
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
) = label(caption, 14f, if (primary) Color.WHITE else Palette.heading, true).apply {
    gravity = Gravity.CENTER
    minimumHeight = dp(48)
    setPadding(dp(12), dp(7), dp(12), dp(7))
    setTextColor(ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(Palette.muted, if (primary) Color.WHITE else Palette.heading)
    ))
    val fill = panel(if (primary) Palette.accent else Palette.control, 22).apply {
        setColor(ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(Palette.control, if (primary) Palette.accent else Palette.control)
        ))
    }
    background = RippleDrawable(Palette.ripple, fill, null)
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
    background = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), panel(Palette.white, 14, Palette.accent))
        addState(intArrayOf(), panel(Palette.white, 14, Palette.border))
    }
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

internal fun Context.searchField(hint: String, value: String) = edit(hint, value).apply {
    background = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), panel(Palette.control, 28, Palette.accent))
        addState(intArrayOf(), panel(Palette.control, 28))
    }
    setCompoundDrawablesRelativeWithIntrinsicBounds(fieldGlyph(FieldIcon.SEARCH, 20, Palette.muted), null, null, null)
    compoundDrawablePadding = dp(8)
    inputType = android.text.InputType.TYPE_CLASS_TEXT
    imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
}

internal fun Context.valueRow(title: String, value: String, pick: (TextView) -> Unit) = horizontal().apply {
    background = RippleDrawable(Palette.ripple, panel(Palette.white, 16), null)
    minimumHeight = dp(56)
    setPaddingRelative(dp(14), dp(8), dp(12), dp(8))
    addView(label(title, 16f, Palette.ink, true), LinearLayout.LayoutParams(0, -2, 1f))
    val current = label(value, 15f, Palette.muted).apply {
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        setPaddingRelative(dp(8), 0, 0, 0)
        compoundDrawablePadding = dp(5)
        setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, fieldGlyph(FieldIcon.NEXT, 16, Palette.muted), null)
    }
    addView(current, LinearLayout.LayoutParams(0, -2, 1f))
    isFocusable = true
    setOnClickListener { pick(current) }
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
        "complex" -> {
            line(-9f, 7f, -9f, -1f, -5f, -5f, -1f, -1f, -1f, 7f)
            line(1f, 7f, 1f, -2f, 5f, -7f, 9f, -2f, 9f, 7f, -9f, 7f)
        }
        "church" -> {
            line(-8f, 8f, -8f, 1f, 0f, -4f, 8f, 1f, 8f, 8f, -8f, 8f)
            line(0f, -9f, 0f, -4f)
            line(-3f, -7f, 3f, -7f)
        }
        "bell_tower" -> {
            line(-7f, -3f, 0f, -9f, 7f, -3f)
            line(-5f, -3f, -5f, 9f, 5f, 9f, 5f, -3f)
            line(-2f, 3f, -2f, 1f, 0f, -1f, 2f, 1f, 2f, 3f, -2f, 3f)
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

internal fun Context.secondaryAction(caption: String, icon: FieldIcon? = null, click: () -> Unit) =
    action(caption, click = click).apply {
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setTextColor(Palette.accent)
        background = RippleDrawable(Palette.ripple, null, panel(Color.WHITE, 14))
        if (icon != null) {
            setCompoundDrawablesRelativeWithIntrinsicBounds(fieldGlyph(icon, 20, Palette.accent), null, null, null)
            compoundDrawablePadding = dp(8)
        }
    }

internal fun Context.choiceAction(caption: String, pick: (TextView) -> Unit) =
    action(caption) { }.apply {
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        background = RippleDrawable(Palette.ripple, panel(Palette.white, 16), null)
        compoundDrawablePadding = dp(10)
        setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, fieldGlyph(FieldIcon.NEXT, 18, Palette.muted), null)
        setOnClickListener { pick(this) }
    }

internal fun Context.chooseOption(title: String, options: List<String>, selected: String, pick: (String) -> Unit) {
    android.app.AlertDialog.Builder(this).setTitle(title)
        .setSingleChoiceItems(options.toTypedArray(), options.indexOf(selected)) { dialog, index ->
            dialog.dismiss()
            pick(options[index])
        }.setPositiveButton("Отмена", null).show()
}

internal fun Context.diaryPageAction(title: String, selected: Boolean, click: () -> Unit) =
    action(title, click = click).apply {
        isSelected = selected
        setPadding(dp(4), dp(3), dp(4), dp(3))
        setTextColor(if (selected) Palette.accent else Palette.heading)
        background = RippleDrawable(Palette.ripple,
            panel(if (selected) Palette.pale else Palette.white, 13), panel(Color.WHITE, 13))
    }

internal fun Context.navigationAction(title: String, icon: FieldIcon, selected: Boolean, click: () -> Unit) =
    action(title, click = click).apply {
        textSize = 12f
        minHeight = dp(54)
        setPadding(dp(2), dp(5), dp(2), dp(5))
        isSelected = selected
        val ink = if (selected) Palette.accent else Palette.muted
        setTextColor(ink)
        background = RippleDrawable(Palette.ripple,
            panel(if (selected) Palette.pale else Color.TRANSPARENT, 14), panel(Color.WHITE, 14))
        val glyph = FieldIconDrawable(dp(22), icon, ink, adding = false)
        glyph.setBounds(0, 0, dp(22), dp(22))
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
            addView(context.label("›", 24f, Palette.accent).apply {
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            })
        }, -1, -2)
    }

    fun bind(site: CatalogEntry) {
        type = objectType(site.type).let { symbol -> site.color?.let { symbol.copy(color = it) } ?: symbol }
        symbol.invalidate()
        title.text = site.title
        detail.text = listOf(type.title, site.subtitle, site.parentTitle.orEmpty()).filter(String::isNotBlank).joinToString(" · ")
    }

}

internal enum class FieldIcon(val description: String, val addsItem: Boolean = false) {
    MAP("Карта"),
    POINT("Добавить точку", true),
    AREA("Добавить область", true),
    COORDINATES("Координаты"),
    GPS_POINT("Отметить здесь по GPS", true),
    GPS_VERTEX("Добавить вершину по GPS", true),
    LAYERS("Слои"),
    VISIBLE_OBJECTS("Объекты в видимой области"),
    SEARCH("Поиск"),
    NEXT("Перейти"),
    CHEVRON_DOWN("Выбор из списка"),
    FILTER_TYPES("Фильтр по типу"),
    FILTER_LOCATION("Фильтр по привязке"),
    SET_POINT("Установить точку"),
    DONE_AREA("Готово"),
    CANCEL("Отмена"),
    CLOSE("Закрыть"),
    BACK("Назад"),
    ADD_VERTEX("Добавить вершину в центр карты", true),
    UNDO_VERTEX("Отменить последнее изменение контура"),
    ZOOM_IN("Приблизить"),
    ZOOM_OUT("Отдалить"),
    LOCATION("Моё место"),
    POSITION_ARROW("Моя позиция"),
    PARENT_AREA("Выбрать родительскую область"),
    CAMERA("Снять фото", true),
    IMAGE("Добавить фото", true),
    DOCUMENT("Добавить документ", true),
    LINK("Ссылка"),
    NOTE("Запись"),
    TRIP("Выезд"),
    DRAFT("Черновики"),
    DATA("Данные")
}

internal fun Context.iconAction(
    icon: FieldIcon, description: String = icon.description, primary: Boolean = false, click: () -> Unit
) = android.widget.ImageButton(this).apply {
    contentDescription = description
    tooltipText = description
    minimumWidth = dp(48)
    minimumHeight = dp(48)
    background = RippleDrawable(Palette.ripple,
        panel(if (primary) Palette.accent else Palette.control, 14,
            if (primary) 0 else Palette.controlBorder), null)
    scaleType = android.widget.ImageView.ScaleType.CENTER
    setPadding(0, 0, 0, 0)
    setOnClickListener { click() }
    isFocusable = true
    setImageDrawable(FieldIconDrawable(dp(24), icon, if (primary) Color.WHITE else Palette.heading))
}

internal fun Context.mapAction(icon: FieldIcon, primary: Boolean = false, click: () -> Unit) =
    iconAction(icon, primary = primary, click = click).apply {
        val fill = panel(if (primary) Palette.floatingAccent else Palette.floatingSurface,
            if (icon == FieldIcon.LOCATION) 24 else 15, if (primary) 0 else Palette.border)
        val states = arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf())
        fill.setColor(ColorStateList(states,
            intArrayOf(Palette.control, if (primary) Palette.floatingAccent else Palette.floatingSurface)))
        background = RippleDrawable(Palette.ripple, fill, null)
        elevation = dp(3).toFloat()
        imageTintList = ColorStateList(states, intArrayOf(Palette.muted, if (primary) Color.WHITE else Palette.heading))
    }

internal fun Context.addAction(caption: String, icon: FieldIcon, primary: Boolean = true, click: () -> Unit) =
    action(caption, primary, click = click).apply {
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setPaddingRelative(dp(14), 0, dp(14), 0)
        val size = dp(23)
        val glyph = FieldIconDrawable(size, icon, if (primary) Color.WHITE else Palette.heading, adding = true)
        glyph.setBounds(0, 0, size, size)
        compoundDrawablePadding = dp(9)
        setCompoundDrawablesRelative(glyph, null, null, null)
    }

internal fun Context.fieldGlyph(icon: FieldIcon, size: Int, color: Int, outline: Boolean = false) =
    FieldIconDrawable(dp(size), icon, color, adding = !outline && icon.addsItem, outline = outline)

internal class FieldIconDrawable(
    private val size: Int, private val icon: FieldIcon, color: Int,
    private val adding: Boolean = icon.addsItem,
    private val outline: Boolean = false
) : android.graphics.drawable.Drawable() {
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size

    override fun draw(canvas: Canvas) {
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        fun path(outline: Path.() -> Unit) = canvas.drawPath(Path().apply(outline), pen)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = canvas.drawLine(x1, y1, x2, y2, pen)
        fun circle(x: Float, y: Float, radius: Float) = canvas.drawCircle(x, y, radius, pen)

        canvas.save()
        if (adding) {
            canvas.translate(0f, 4f)
            canvas.scale(0.8f, 0.8f)
        }
        if (outline) {
            pen.setShadowLayer(1.5f, 0f, 0f, Color.WHITE)
        }
        when (icon) {
            FieldIcon.MAP -> path {
                moveTo(3f, 6f)
                lineTo(9f, 3f)
                lineTo(15f, 6f)
                lineTo(21f, 3f)
                lineTo(21f, 18f)
                lineTo(15f, 21f)
                lineTo(9f, 18f)
                lineTo(3f, 21f)
                close()
                moveTo(9f, 3f)
                lineTo(9f, 18f)
                moveTo(15f, 6f)
                lineTo(15f, 21f)
            }
            FieldIcon.NOTE -> {
                canvas.drawRoundRect(5f, 3f, 20f, 21f, 2f, 2f, pen)
                line(9f, 3f, 9f, 21f)
                line(12f, 9f, 17f, 9f)
                line(12f, 13f, 17f, 13f)
            }
            FieldIcon.TRIP -> {
                canvas.drawRoundRect(4f, 8f, 20f, 21f, 3f, 3f, pen)
                path {
                    moveTo(8f, 8f)
                    lineTo(8f, 6f)
                    quadTo(8f, 4f, 10f, 4f)
                    lineTo(14f, 4f)
                    quadTo(16f, 4f, 16f, 6f)
                    lineTo(16f, 8f)
                }
                line(4f, 14f, 20f, 14f)
            }
            FieldIcon.DRAFT, FieldIcon.DOCUMENT -> path {
                moveTo(5f, 3f)
                lineTo(14f, 3f)
                lineTo(19f, 8f)
                lineTo(19f, 21f)
                lineTo(5f, 21f)
                close()
                moveTo(14f, 3f)
                lineTo(14f, 8f)
                lineTo(19f, 8f)
                moveTo(8f, 12f)
                lineTo(16f, 12f)
                moveTo(8f, 16f)
                lineTo(13f, 16f)
            }
            FieldIcon.LINK -> path {
                moveTo(11f, 8f)
                lineTo(15f, 4f)
                cubicTo(19f, 0f, 24f, 5f, 20f, 9f)
                lineTo(16f, 13f)
                moveTo(8f, 11f)
                lineTo(4f, 15f)
                cubicTo(0f, 19f, 5f, 24f, 9f, 20f)
                lineTo(13f, 16f)
                moveTo(8f, 16f)
                lineTo(16f, 8f)
            }
            FieldIcon.DATA -> {
                canvas.drawRoundRect(3f, 5f, 21f, 20f, 2f, 2f, pen)
                line(3f, 10f, 21f, 10f)
                line(10f, 14f, 14f, 14f)
            }
            FieldIcon.BACK -> path {
                moveTo(15f, 5f)
                lineTo(8f, 12f)
                lineTo(15f, 19f)
            }
            FieldIcon.SEARCH -> {
                circle(10f, 10f, 6f)
                line(15f, 15f, 21f, 21f)
            }
            FieldIcon.NEXT -> path { moveTo(9f, 5f); lineTo(16f, 12f); lineTo(9f, 19f) }
            FieldIcon.CHEVRON_DOWN -> path {
                moveTo(5f, 9f)
                lineTo(12f, 16f)
                lineTo(19f, 9f)
            }
            FieldIcon.FILTER_TYPES -> {
                line(4f, 5f, 20f, 5f)
                line(7f, 12f, 17f, 12f)
                line(10f, 19f, 14f, 19f)
            }
            FieldIcon.FILTER_LOCATION -> {
                circle(12f, 9f, 2f)
                path {
                    moveTo(12f, 21f)
                    cubicTo(10f, 18f, 5f, 13f, 5f, 9f)
                    cubicTo(5f, 1f, 19f, 1f, 19f, 9f)
                    cubicTo(19f, 13f, 14f, 18f, 12f, 21f)
                    close()
                }
            }
            FieldIcon.VISIBLE_OBJECTS -> {
                for (y in listOf(6f, 12f, 18f)) {
                    circle(5f, y, 1f)
                    line(10f, y, 21f, y)
                }
            }
            FieldIcon.PARENT_AREA -> {
                canvas.drawRoundRect(3f, 4f, 21f, 20f, 3f, 3f, pen)
                path {
                    moveTo(8f, 13f)
                    lineTo(12f, 9f)
                    lineTo(16f, 13f)
                    lineTo(12f, 16f)
                    close()
                }
            }
            FieldIcon.CAMERA -> {
                path {
                    moveTo(4f, 7f)
                    lineTo(8f, 7f)
                    lineTo(10f, 4f)
                    lineTo(15f, 4f)
                    lineTo(17f, 7f)
                    lineTo(20f, 7f)
                    quadTo(22f, 7f, 22f, 9f)
                    lineTo(22f, 18f)
                    quadTo(22f, 20f, 20f, 20f)
                    lineTo(4f, 20f)
                    quadTo(2f, 20f, 2f, 18f)
                    lineTo(2f, 9f)
                    quadTo(2f, 7f, 4f, 7f)
                    close()
                }
                circle(12f, 13.5f, 3.5f)
            }
            FieldIcon.IMAGE -> {
                canvas.drawRoundRect(3f, 4f, 21f, 20f, 3f, 3f, pen)
                circle(8f, 9f, 1.5f)
                path {
                    moveTo(4f, 17f)
                    lineTo(9f, 13f)
                    lineTo(12f, 16f)
                    lineTo(16f, 11f)
                    lineTo(21f, 17f)
                }
            }
            FieldIcon.POINT -> {
                path {
                    moveTo(19f, 9f)
                    cubicTo(19f, 14f, 12f, 21f, 12f, 21f)
                    cubicTo(12f, 21f, 5f, 14f, 5f, 9f)
                    cubicTo(5f, 0f, 19f, 0f, 19f, 9f)
                    close()
                }
                circle(12f, 9f, 2.2f)
            }
            FieldIcon.AREA -> {
                path {
                    moveTo(5f, 7f)
                    lineTo(18f, 4f)
                    lineTo(20f, 16f)
                    lineTo(8f, 20f)
                    close()
                }
                pen.style = Paint.Style.FILL
                circle(5f, 7f, 1.8f)
                circle(18f, 4f, 1.8f)
                circle(20f, 16f, 1.8f)
                circle(8f, 20f, 1.8f)
                pen.style = Paint.Style.STROKE
            }
            FieldIcon.COORDINATES -> {
                path {
                    moveTo(9f, 4f)
                    lineTo(4f, 4f)
                    lineTo(4f, 9f)
                    moveTo(15f, 4f)
                    lineTo(20f, 4f)
                    lineTo(20f, 9f)
                    moveTo(4f, 15f)
                    lineTo(4f, 20f)
                    lineTo(9f, 20f)
                    moveTo(20f, 15f)
                    lineTo(20f, 20f)
                    lineTo(15f, 20f)
                }
                circle(12f, 12f, 2.5f)
            }
            FieldIcon.LAYERS -> path {
                moveTo(3f, 8f)
                lineTo(12f, 3f)
                lineTo(21f, 8f)
                lineTo(12f, 13f)
                close()
                moveTo(3f, 12f)
                lineTo(12f, 17f)
                lineTo(21f, 12f)
                moveTo(3f, 16f)
                lineTo(12f, 21f)
                lineTo(21f, 16f)
            }
            FieldIcon.SET_POINT, FieldIcon.DONE_AREA -> path {
                moveTo(5f, 12f)
                lineTo(10f, 17f)
                lineTo(19f, 7f)
            }
            FieldIcon.CANCEL, FieldIcon.CLOSE -> {
                line(6f, 6f, 18f, 18f)
                line(6f, 18f, 18f, 6f)
            }
            FieldIcon.ADD_VERTEX -> path {
                moveTo(12f, 5f)
                lineTo(19f, 12f)
                lineTo(12f, 19f)
                lineTo(5f, 12f)
                close()
            }
            FieldIcon.UNDO_VERTEX -> path {
                moveTo(9f, 5f)
                lineTo(4f, 10f)
                lineTo(9f, 15f)
                moveTo(4f, 10f)
                lineTo(14f, 10f)
                cubicTo(21f, 10f, 21f, 19f, 14f, 19f)
                lineTo(12f, 19f)
            }
            FieldIcon.ZOOM_IN, FieldIcon.ZOOM_OUT -> {
                line(5f, 12f, 19f, 12f)
                if (icon == FieldIcon.ZOOM_IN) line(12f, 5f, 12f, 19f)
            }
            FieldIcon.POSITION_ARROW -> path {
                moveTo(21f, 3f)
                lineTo(16f, 21f)
                lineTo(11f, 13f)
                lineTo(3f, 9f)
                close()
            }
            FieldIcon.LOCATION, FieldIcon.GPS_POINT, FieldIcon.GPS_VERTEX -> {
                circle(12f, 12f, 8f)
                line(12f, 1f, 12f, 4f)
                line(12f, 20f, 12f, 23f)
                line(1f, 12f, 4f, 12f)
                line(20f, 12f, 23f, 12f)
                pen.style = Paint.Style.FILL
                circle(12f, 12f, 3.4f)
                pen.style = Paint.Style.STROKE
            }
        }
        canvas.restore()
        if (adding) {
            line(17.5f, 5f, 22.5f, 5f)
            line(20f, 2.5f, 20f, 7.5f)
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { pen.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { pen.colorFilter = colorFilter }
    @Deprecated("Drawable opacity is no longer used")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

internal fun Context.editorAction(done: Boolean, click: () -> Unit) =
    iconAction(if (done) FieldIcon.DONE_AREA else FieldIcon.BACK,
        description = if (done) "Сохранить и закрыть" else "Назад", primary = done, click = click)

@android.annotation.SuppressLint("ViewConstructor")
internal class DiaryRow(context: Context) : LinearLayout(context) {
    private val title = context.label("", 16f, Palette.ink, true)
    private val detail = context.label("", 13f, Palette.muted)

    init {
        gravity = Gravity.CENTER_VERTICAL
        background = context.panel(Palette.white, 13, Palette.border)
        setPadding(context.dp(12), context.dp(11), context.dp(12), context.dp(11))
        minimumHeight = context.dp(64)
        addView(context.vertical().apply {
            addView(title)
            addView(detail)
        }, LayoutParams(0, -2, 1f))
        addView(context.label("›", 22f, Palette.accent).apply {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        })
    }

    fun bind(note: NoteSummary) {
        title.text = note.title
        detail.text = listOfNotNull(dateLabel(note.created), note.siteTitle, note.tripTitle).joinToString(" · ")
    }
}
