package dev.securenotes.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.*
import android.text.style.LeadingMarginSpan
import android.text.style.MetricAffectingSpan
import android.text.style.StyleSpan
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.EditText
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.securenotes.document.*
import kotlinx.coroutines.launch

/** Heading size and weight, kept apart from bold [StyleSpan]s so it is never mistaken for bold text. */
class HeadingSpan(level: Int) : MetricAffectingSpan() {
    private val scale = if (level == 1) 1.45f else 1.17f
    override fun updateMeasureState(paint: TextPaint) = apply(paint)
    override fun updateDrawState(paint: TextPaint) = apply(paint)
    private fun apply(paint: TextPaint) { paint.textSize *= scale; paint.isFakeBoldText = true }
}

/** Draws a list line's bullet, number or checkbox in its leading margin. */
class LineMarkerSpan(private val line: Line, private val number: Int, private val margin: Int, private val color: Int) : LeadingMarginSpan {
    override fun getLeadingMargin(first: Boolean) = margin
    override fun drawLeadingMargin(canvas: Canvas, paint: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout?) {
        if (!first || (text as? Spanned)?.getSpanStart(this) != start) return
        val style = paint.style; val stroke = paint.strokeWidth; val previous = paint.color
        paint.color = color
        val size = paint.textSize
        val middle = baseline - size * 0.33f
        when (line.type) {
            LineType.BULLET -> { paint.style = Paint.Style.FILL; canvas.drawCircle(x + margin * 0.35f, middle, size * 0.14f, paint) }
            LineType.NUMBERED -> { paint.style = Paint.Style.FILL; canvas.drawText("$number.", x.toFloat(), baseline.toFloat(), paint) }
            LineType.CHECKLIST -> {
                val box = size * 0.85f; val left = x.toFloat(); val boxTop = middle - box / 2
                paint.strokeWidth = size * 0.09f
                paint.style = if (line.checked) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
                canvas.drawRoundRect(left, boxTop, left + box, boxTop + box, box * 0.15f, box * 0.15f, paint)
                if (line.checked) {
                    paint.color = 0xFFFFFFFF.toInt(); paint.style = Paint.Style.STROKE
                    canvas.drawLine(left + box * 0.22f, boxTop + box * 0.52f, left + box * 0.42f, boxTop + box * 0.72f, paint)
                    canvas.drawLine(left + box * 0.42f, boxTop + box * 0.72f, left + box * 0.80f, boxTop + box * 0.30f, paint)
                }
            }
            else -> Unit
        }
        paint.style = style; paint.strokeWidth = stroke; paint.color = previous
    }
}

/**
 * The whole note body in one native editor. Text changes are routed through [DocumentEdits] so list continuation,
 * list ending and line formats follow the same tested rules; line formats are re-applied as spans after each change.
 */
class BodyEditText(context: Context) : EditText(context) {
    var document: Document = Document()
        private set
    /** Called with the new document; `typing` is false for formatting, checkbox and paste changes (separate undo steps). */
    var changed: (Document, Boolean) -> Unit = { _, _ -> }
    var leave: () -> Unit = {}
    var caret: (android.graphics.Rect) -> Unit = {}
    var selectionChanged: () -> Unit = {}
    var markerColor: Int = 0xFF000000.toInt()
    private val margin = (28 * resources.displayMetrics.density).toInt()
    private var suppress = false
    private var pasting = false
    private var pending: Triple<Int, Int, Int>? = null
    private var touchedCheckbox: Int? = null
    /**
     * False while the EditText superclass constructor runs. It calls [onSelectionChanged] before this class's
     * properties (including the callbacks above) are initialized, so they are still null then.
     */
    private var ready = false

    init {
        background = null; setPadding(0, 6, 0, 6); gravity = android.view.Gravity.TOP or android.view.Gravity.START
        isSaveEnabled = false; importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { if (!suppress) pending = Triple(start, before, count) }
            override fun afterTextChanged(s: Editable?) {
                val change = pending ?: return
                pending = null
                if (!suppress && s != null) textChanged(s, change.first, change.second, change.third)
            }
        })
        setOnKeyListener { _, keyCode, event -> keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN && clearFirstLineList() }
        ready = true
    }

    private companion object {
        /**
         * Android's Layout gives an empty paragraph at the very end of the text no paragraph spans, so an empty last
         * list item would show no marker or indent. The editor then shows this invisible character after it.
         * It exists only in the editor, never in the [Document].
         */
        const val END = '​'
    }
    private fun displayed(value: Document) = if (value.text.endsWith('\n') && value.lines.last().type.isList) value.text + END else value.text
    /** The editor's text length without the end marker; the cursor never goes past it. */
    private fun contentLength(): Int = text?.let { if (it.isNotEmpty() && it[it.length - 1] == END) it.length - 1 else it.length } ?: 0

    private fun textChanged(s: Editable, start: Int, before: Int, count: Int) {
        val old = document
        if (start + before > displayed(old).length) {
            show(Document(text = s.toString().replace(END.toString(), "")).let { it.copy(lines = List(it.text.count { c -> c == '\n' } + 1) { Line() }) }, force = true)
            return
        }
        // An edit touching the end marker applies to the end of the stored text.
        val length = old.text.length
        val inserted = s.subSequence(start, start + count).toString().replace(END.toString(), "")
        val result = DocumentEdits.replace(old, minOf(start, length), minOf(start + before, length), inserted)
        val target = displayed(result.document)
        val current = s.toString()
        if (target != current) {
            // A rule declined the edit (Enter on an empty list item, Backspace into a list item), or the end marker
            // must appear or go: correct only the changed region so the keyboard's composing text survives.
            suppress = true
            var prefix = 0
            while (prefix < current.length && prefix < target.length && current[prefix] == target[prefix]) prefix++
            var suffix = 0
            while (suffix < current.length - prefix && suffix < target.length - prefix &&
                current[current.length - 1 - suffix] == target[target.length - 1 - suffix]) suffix++
            s.replace(prefix, current.length - suffix, target, prefix, target.length - suffix)
            setSelection(result.selection.coerceIn(0, result.document.text.length))
            suppress = false
        }
        // Bold follows the editor's own spans so composing text behaves natively.
        document = result.document.copy(bold = DocumentEdits.normalize(boldSpans(result.document.text.length)))
        applyLineSpans()
        changed(document, !pasting)
        reportCaret()
    }
    private fun boldSpans(limit: Int): List<BoldSpan> = text?.let { s -> s.getSpans(0, s.length, StyleSpan::class.java)
        .filter { it.style == Typeface.BOLD }.map { BoldSpan(s.getSpanStart(it), minOf(s.getSpanEnd(it), limit)) } } ?: emptyList()
    /** Adds or removes the end marker after a format change (e.g. a bullet toggled on an empty last line). */
    private fun syncEndMarker() {
        val s = text ?: return
        val wanted = displayed(document).length > document.text.length
        val present = s.length > document.text.length
        if (wanted == present) return
        suppress = true
        if (wanted) s.append(END) else s.delete(document.text.length, s.length)
        suppress = false
    }
    private fun applyBold() {
        val s = text ?: return
        s.getSpans(0, s.length, StyleSpan::class.java).filter { it.style == Typeface.BOLD }.forEach(s::removeSpan)
        document.bold.forEach { s.setSpan(StyleSpan(Typeface.BOLD), it.start, it.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }
    private fun applyLineSpans() {
        val s = text ?: return
        s.getSpans(0, s.length, LineMarkerSpan::class.java).forEach(s::removeSpan)
        s.getSpans(0, s.length, HeadingSpan::class.java).forEach(s::removeSpan)
        val starts = DocumentEdits.lineStarts(document.text)
        document.lines.forEachIndexed { i, line ->
            val start = starts[i]
            val end = if (i + 1 < starts.size) starts[i + 1] else s.length
            when (line.type) {
                LineType.HEADING1, LineType.HEADING2 -> if (end > start)
                    s.setSpan(HeadingSpan(if (line.type == LineType.HEADING1) 1 else 2), start, end, Spanned.SPAN_EXCLUSIVE_INCLUSIVE)
                LineType.PARAGRAPH -> Unit
                else -> s.setSpan(LineMarkerSpan(line, DocumentEdits.numberFor(document, i), margin, markerColor), start, end, Spanned.SPAN_EXCLUSIVE_INCLUSIVE)
            }
        }
    }
    /** Shows [value] unless the editor already holds it (the normal case after typing). */
    fun show(value: Document, force: Boolean = false) {
        if (!force && value == document) return
        val selection = selectionStart.coerceAtLeast(0)
        suppress = true
        val shown = displayed(value)
        if (text.toString() != shown) setText(shown)
        document = value
        applyBold(); applyLineSpans()
        setSelection(selection.coerceIn(0, value.text.length))
        suppress = false
    }
    private fun format(value: Document) {
        if (value == document) return
        val start = selectionStart; val end = selectionEnd
        document = value; syncEndMarker(); applyBold(); applyLineSpans(); invalidate()
        suppress = true; setSelection(start.coerceIn(0, value.text.length), end.coerceIn(0, value.text.length)); suppress = false
        changed(document, false); selectionChanged()
    }
    fun toggleBold() = format(DocumentEdits.toggleBold(document, selectionStart, selectionEnd))
    fun toggleLine(type: LineType) = format(DocumentEdits.setLineType(document, selectionStart, selectionEnd, type))
    fun activeType(): LineType? = DocumentEdits.activeType(document, selectionStart.coerceAtLeast(0), selectionEnd.coerceAtLeast(0))
    fun isBold(): Boolean = DocumentEdits.isBold(document, minOf(selectionStart, selectionEnd), maxOf(selectionStart, selectionEnd))
    fun focusAt(offset: Int) {
        requestFocus(); setSelection(offset.coerceIn(0, document.text.length))
        post { windowInsetsController?.show(android.view.WindowInsets.Type.ime()) }
    }
    /** Backspace at the very start of the body has no text to delete, so it clears a list format directly. */
    private fun clearFirstLineList(): Boolean {
        if (selectionStart != 0 || selectionEnd != 0 || !document.lines[0].type.isList) return false
        format(DocumentEdits.setLineType(document, 0, 0, document.lines[0].type)); return true
    }
    override fun onTextContextMenuItem(id: Int): Boolean {
        // Pasted text arrives without outside styling; its lines become paragraphs.
        val target = if (id == android.R.id.paste) android.R.id.pasteAsPlainText else id
        pasting = target == android.R.id.pasteAsPlainText
        return try { super.onTextContextMenuItem(target) } finally { pasting = false }
    }
    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (!ready || suppress) return
        // Keep the cursor and selection (including Select All) before the invisible end marker.
        val limit = contentLength()
        if (selStart > limit || selEnd > limit) { setSelection(minOf(selStart, limit), minOf(selEnd, limit)); return }
        selectionChanged(); post { reportCaret() }
    }
    private fun reportCaret() {
        val layout = layout ?: return
        val offset = selectionEnd.coerceIn(0, text?.length ?: 0)
        val line = layout.getLineForOffset(offset)
        val x = (layout.getPrimaryHorizontal(offset) + totalPaddingLeft).toInt()
        caret(android.graphics.Rect(x, layout.getLineTop(line) + totalPaddingTop, x + 1, layout.getLineBottom(line) + totalPaddingTop))
    }
    /** A tap in the margin of a checklist line's first row toggles it instead of moving the cursor. */
    private fun checkboxAt(x: Float, y: Float): Int? {
        val layout = layout ?: return null
        if (x - totalPaddingLeft > margin) return null
        val offset = layout.getLineStart(layout.getLineForVertical((y - totalPaddingTop + scrollY).toInt()))
        val line = DocumentEdits.lineAt(document.text, offset)
        return line.takeIf { document.lines[it].type == LineType.CHECKLIST && DocumentEdits.lineStart(document.text, it) == offset }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) touchedCheckbox = checkboxAt(event.x, event.y)
        val line = touchedCheckbox ?: return super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP && checkboxAt(event.x, event.y) == line) {
            format(DocumentEdits.setChecked(document, line, !document.lines[line].checked))
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) touchedCheckbox = null
        return true
    }
    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { leave(); return true }
        return super.onKeyPreIme(keyCode, event)
    }
    override fun onCreateInputConnection(outAttrs: android.view.inputmethod.EditorInfo): android.view.inputmethod.InputConnection? {
        val connection = super.onCreateInputConnection(outAttrs) ?: return null
        return object : android.view.inputmethod.InputConnectionWrapper(connection, false) {
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
                (beforeLength > 0 && clearFirstLineList()) || super.deleteSurroundingText(beforeLength, afterLength)
            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
                (beforeLength > 0 && clearFirstLineList()) || super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
        }
    }
}

@Composable
fun RichBodyEditor(document: Document, color: Color, markerColor: Color, modifier: Modifier, onReady: (BodyEditText?) -> Unit,
    onChange: (Document, Boolean) -> Unit, onSelection: () -> Unit, onLeave: () -> Unit) {
    val currentChange by rememberUpdatedState(onChange)
    val currentSelection by rememberUpdatedState(onSelection)
    val currentLeave by rememberUpdatedState(onLeave)
    val requester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    AndroidView(modifier = modifier.bringIntoViewRequester(requester), factory = { context ->
        BodyEditText(context).apply {
            hint = "Note"; textSize = 18f
            changed = { value, typing -> currentChange(value, typing) }
            selectionChanged = { currentSelection() }
            leave = { currentLeave() }
            // Keep the cursor (plus a little space) visible above the keyboard while the column scrolls.
            caret = { r -> scope.launch { requester.bringIntoView(androidx.compose.ui.geometry.Rect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom + with(density) { 48.dp.toPx() })) } }
            onReady(this)
        }
    }, onRelease = { onReady(null) }, update = { view ->
        view.setTextColor(color.toArgb()); view.setHintTextColor(color.copy(alpha = .45f).toArgb())
        view.markerColor = markerColor.toArgb()
        view.show(document)
    })
}
