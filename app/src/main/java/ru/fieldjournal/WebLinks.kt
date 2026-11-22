package ru.fieldjournal

import android.annotation.SuppressLint
import android.net.Uri
import android.text.Editable
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.URLSpan
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale

internal data class WebLink(val url: String, val title: String = "") {
    fun json() = JSONObject().put("url", url).put("title", title)
}

internal fun JSONObject.webLinks(): List<WebLink> = array("links").let { links ->
    (0 until links.length()).mapNotNull { index ->
        val link = links.optJSONObject(index) ?: return@mapNotNull null
        val url = webUri(link.optString("url")) ?: return@mapNotNull null
        WebLink(url.toString(), link.optString("title").trim())
    }.distinctBy(WebLink::url)
}

internal fun List<WebLink>.linksJson() = JSONArray(map(WebLink::json))

internal fun webUri(value: String): Uri? {
    val url = value.trim()
    val parsed = runCatching { URI(url) }.getOrNull() ?: return null
    val scheme = parsed.scheme?.lowercase(Locale.ROOT)
    if (scheme != "http" && scheme != "https") return null
    val uri = Uri.parse(url).normalizeScheme()
    return uri.takeIf { !it.host.isNullOrBlank() }
}

private val webAddress = Regex("""\b(?:https?://|www\.)[^\s<>"«»“”‘’\p{Z}]+""", RegexOption.IGNORE_CASE)

// Strip punctuation surrounding a URL in prose while retaining balanced URL parentheses.
internal fun webAddresses(value: CharSequence): List<Pair<IntRange, Uri>> = webAddress.findAll(value).mapNotNull { match ->
    val candidate = match.value
    var end = candidate.length
    var excessParens = candidate.count { it == ')' } - candidate.count { it == '(' }
    var excessBrackets = candidate.count { it == ']' } - candidate.count { it == '[' }
    while (end > 0) {
        when (candidate[end - 1]) {
            '.', ',', ';', ':', '!', '?', '\'', '…' -> Unit
            ')' -> if (excessParens-- <= 0) break
            ']' -> if (excessBrackets-- <= 0) break
            else -> break
        }
        end--
    }
    val address = candidate.take(end)
    val url = if (address.startsWith("www.", ignoreCase = true)) "https://$address" else address
    webUri(url)?.let { (match.range.first until match.range.first + end) to it }
}.toList()

private class JournalLinkSpan(url: String, private val open: (String) -> Unit) : URLSpan(url) {
    override fun onClick(widget: View) = open(url)
}

internal fun Editable.showWebLinks(open: (String) -> Unit) {
    getSpans(0, length, JournalLinkSpan::class.java).forEach(::removeSpan)
    for ((range, uri) in webAddresses(this)) {
        setSpan(JournalLinkSpan(uri.toString(), open), range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

private class ShortAddressSpan(private val title: String, private val color: Int) : android.text.style.ReplacementSpan() {
    override fun getSize(paint: android.graphics.Paint, text: CharSequence, start: Int, end: Int,
        fm: android.graphics.Paint.FontMetricsInt?): Int = kotlin.math.ceil(paint.measureText(title)).toInt()

    override fun draw(canvas: android.graphics.Canvas, text: CharSequence, start: Int, end: Int,
        x: Float, top: Int, y: Int, bottom: Int, paint: android.graphics.Paint) {
        val styled = android.graphics.Paint(paint).apply { color = this@ShortAddressSpan.color; isUnderlineText = true }
        canvas.drawText(title, x, y.toFloat(), styled)
    }
}

@SuppressLint("ClickableViewAccessibility")
internal fun EditText.enableWebLinkTaps(open: (String) -> Unit) {
    setLinkTextColor(Palette.accent)
    fun compact(value: Editable) {
        value.getSpans(0, value.length, ShortAddressSpan::class.java).forEach(value::removeSpan)
        if (isFocused) return
        for ((range, uri) in webAddresses(value)) {
            if (range.last - range.first + 1 < 55) continue
            val title = when {
                uri.host == "creativecommons.org" && uri.path?.contains("/by-sa/4.0") == true -> "Лицензия CC BY-SA 4.0"
                uri.host == "ru.wikivoyage.org" -> uri.lastPathSegment?.replace('_', ' ')?.takeIf { it.length <= 21 }
                    ?.let { "Викигид · $it" } ?: "Викигид"
                else -> uri.host.orEmpty().removePrefix("www.").take(26)
            }
            value.setSpan(ShortAddressSpan(title, Palette.accent), range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
    text.showWebLinks(open)
    compact(text)
    onFocusChangeListener = View.OnFocusChangeListener { _, _ -> compact(text) }
    addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) {
            s?.let { it.showWebLinks(open); compact(it) }
        }
    })
    fun linkAt(event: MotionEvent): JournalLinkSpan? {
        val textLayout = layout ?: return null
        val y = event.y - totalPaddingTop + scrollY
        if (y < 0 || y >= textLayout.height) return null
        val line = textLayout.getLineForVertical(y.toInt())
        val x = event.x - totalPaddingLeft + scrollX
        if (x < textLayout.getLineLeft(line) || x >= textLayout.getLineRight(line)) return null
        val offset = textLayout.getOffsetForHorizontal(line, x)
        for (span in text.getSpans(0, text.length, ShortAddressSpan::class.java)) {
            val start = text.getSpanStart(span)
            if (textLayout.getLineForOffset(start) != line) continue
            val left = textLayout.getPrimaryHorizontal(start)
            if (x >= left && x <= left + span.getSize(paint, text, start, text.getSpanEnd(span), null)) {
                return text.getSpans(start, start + 1, JournalLinkSpan::class.java).firstOrNull()
            }
        }
        return text.getSpans(offset, offset, JournalLinkSpan::class.java).firstOrNull {
            offset < text.getSpanEnd(it)
        }
    }

    val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        private var link: JournalLinkSpan? = null

        override fun onDown(e: MotionEvent): Boolean {
            link = linkAt(e)
            return false
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val tapped = link?.takeIf { it === linkAt(e) } ?: return false
            // Clear the editor's touch state without replacing its selection movement method.
            val cancel = MotionEvent.obtain(e)
            cancel.action = MotionEvent.ACTION_CANCEL
            onTouchEvent(cancel)
            cancel.recycle()
            tapped.onClick(this@enableWebLinkTaps)
            return true
        }
    })
    setOnTouchListener { _, event -> detector.onTouchEvent(event) }
}
