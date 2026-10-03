package dev.securenotes.ui

import android.content.Context
import android.graphics.Typeface
import android.text.*
import android.text.style.StyleSpan
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import dev.securenotes.document.*

class BlockEditText(context: Context) : EditText(context) {
    var leave: () -> Unit = {}
    var join: () -> Unit = {}
    var changed: (String, List<BoldSpan>) -> Unit = { _, _ -> }
    var suppress = false
    init {
        background = null; setPadding(0, 6, 0, 6)
        isSaveEnabled = false; importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { if (!suppress && s != null) changed(s.toString(), spans()) }
        })
        setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN && selectionStart == 0 && selectionEnd == 0) { join(); true } else false
        }
    }
    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { leave(); return true }
        return super.onKeyPreIme(keyCode, event)
    }
    override fun onCreateInputConnection(outAttrs: android.view.inputmethod.EditorInfo): android.view.inputmethod.InputConnection? {
        val connection = super.onCreateInputConnection(outAttrs) ?: return null
        return object : android.view.inputmethod.InputConnectionWrapper(connection, false) {
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0 && selectionStart == 0 && selectionEnd == 0) { join(); return true }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }
            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0 && selectionStart == 0 && selectionEnd == 0) { join(); return true }
                return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
            }
        }
    }
    fun spans(): List<BoldSpan> = text?.let { s -> s.getSpans(0, s.length, StyleSpan::class.java)
        .filter { it.style == Typeface.BOLD }.map { BoldSpan(s.getSpanStart(it), s.getSpanEnd(it)) }
        .filter { it.start >= 0 && it.end > it.start } } ?: emptyList()
    fun bold() {
        val s = text ?: return
        val start = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
        val end = maxOf(selectionStart, selectionEnd).coerceAtMost(s.length)
        if (end <= start) return
        val overlaps = s.getSpans(start, end, StyleSpan::class.java).filter { it.style == Typeface.BOLD }
        val covered = overlaps.any { s.getSpanStart(it) <= start && s.getSpanEnd(it) >= end }
        overlaps.forEach { span ->
            val a = s.getSpanStart(span); val b = s.getSpanEnd(span); s.removeSpan(span)
            if (a < start) s.setSpan(StyleSpan(Typeface.BOLD), a, start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (b > end) s.setSpan(StyleSpan(Typeface.BOLD), end, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (!covered) s.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        changed(s.toString(), spans())
    }
}

@Composable
fun RichTextEditor(block: Block, color: Color, focused: Boolean, modifier: Modifier,
    onFocus: (BlockEditText) -> Unit, onChange: (String, List<BoldSpan>) -> Unit, onJoin: () -> Unit, onLeave: () -> Unit, focusOffset: Int? = null) {
    val currentChange by rememberUpdatedState(onChange)
    val currentFocus by rememberUpdatedState(onFocus)
    val currentJoin by rememberUpdatedState(onJoin)
    val currentLeave by rememberUpdatedState(onLeave)
    AndroidView(modifier = modifier, factory = { context ->
        BlockEditText(context).apply {
            changed = { text, spans -> currentChange(text, spans) }; join = { currentJoin() }; leave = { currentLeave() }
            setOnFocusChangeListener { _, has -> if (has) currentFocus(this) }
        }
    }, update = { view ->
        view.setTextColor(color.toArgb()); view.setHintTextColor(color.copy(alpha = .45f).toArgb()); view.hint = "Note"
        view.textSize = when (block.type) { BlockType.HEADING1 -> 26f; BlockType.HEADING2 -> 21f; else -> 18f }
        if (view.text.toString() != block.text || view.spans() != block.bold) {
            val selection = view.selectionStart.coerceIn(0, block.text.length)
            view.suppress = true
            view.setText(SpannableString(block.text).apply { block.bold.forEach { setSpan(StyleSpan(Typeface.BOLD), it.start, it.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) } })
            view.setSelection(selection); view.suppress = false
        }
        if (focused && !view.hasFocus()) {
            view.requestFocus(); view.setSelection((focusOffset ?: (view.text?.length ?: 0)).coerceIn(0, view.text?.length ?: 0))
            view.post { view.windowInsetsController?.show(android.view.WindowInsets.Type.ime()) }
        }
    })
}
