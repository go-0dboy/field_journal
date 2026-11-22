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

@SuppressLint("ClickableViewAccessibility")
internal fun EditText.enableWebLinkTaps(open: (String) -> Unit) {
    setLinkTextColor(Palette.accent)
    text.showWebLinks(open)
    addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) {
            s?.showWebLinks(open)
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
