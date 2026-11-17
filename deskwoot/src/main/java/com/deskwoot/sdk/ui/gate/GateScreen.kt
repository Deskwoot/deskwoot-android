package com.deskwoot.sdk.ui.gate

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.deskwoot.sdk.R
import com.deskwoot.sdk.model.FieldProblem
import com.deskwoot.sdk.model.Gate
import com.deskwoot.sdk.model.PreChatField
import com.deskwoot.sdk.model.SessionState
import com.deskwoot.sdk.ui.InsetAware
import com.deskwoot.sdk.ui.Route
import com.deskwoot.sdk.ui.Screen
import com.deskwoot.sdk.ui.Ui
import com.deskwoot.sdk.ui.UiMemory
import com.deskwoot.sdk.ui.widgets.MATCH
import com.deskwoot.sdk.ui.widgets.PrimaryButton
import com.deskwoot.sdk.ui.widgets.SubHeader
import com.deskwoot.sdk.ui.widgets.WRAP
import com.deskwoot.sdk.ui.widgets.contentText
import com.deskwoot.sdk.ui.widgets.dp
import com.deskwoot.sdk.ui.widgets.icon
import com.deskwoot.sdk.ui.widgets.linear
import com.deskwoot.sdk.ui.widgets.ripple
import com.deskwoot.sdk.ui.widgets.role
import com.deskwoot.sdk.ui.widgets.rounded
import com.deskwoot.sdk.ui.widgets.textStyle

/**
 * "Before we start": the pre chat form with every field
 * type (text, email, phone, a long text, a choice), or the email step.
 * Errors show after the first try and then follow the typing; a field with
 * an error keeps its red border while focused. On submit
 * the answers go to the session in the background and the thread takes
 * this screen's place.
 *
 * The step comes from the session synchronously and is built again
 * whenever it changes. However the screen goes without an
 * answer (Cancel, the back arrow, system back, the back gesture, the ×),
 * the texts the visitor wrote meanwhile go back into the composer and the
 * step is cancelled, once; it shows again on the next send.
 */
internal class GateScreen(ui: Ui, route: Route) : Screen(ui, route), InsetAware {
    private val ctx = ui.context
    private val c = ui.colors
    private lateinit var header: SubHeader
    private lateinit var form: LinearLayout
    private val inputs = LinkedHashMap<String, EditText>()
    private val errors = HashMap<String, TextView>()
    private val selects = HashMap<String, String>()
    private var tried = false

    /** The step the form shows. */
    private var gate: Gate = Gate.None
    private var fields: List<PreChatField> = emptyList()
    private var emailStep = false
    private var topInset = 0

    /** Answers from before a rebuild or a recreate, by field name: the typed text, and a choice's option. */
    private val restored = HashMap<String, String>()
    private val restoredSelects = HashMap<String, String>()
    private var submitted = false

    /** The held texts went back and the step was cancelled. */
    private var left = false

    override fun createView(): View {
        // The step as the session has it now: it already reflects a startNewConversation called just
        // before, while the published state follows a main loop turn later.
        gate = ui.actions.gateBeforeChat()
        val root = LinearLayout(ctx).apply {
            id = R.id.dw_gate_screen
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(c.bg)
        }
        header = SubHeader(ctx, ui, withFace = false, style = ui.design.headerStyle(route))
        header.title.text = ui.t(R.string.dw_before_we_start)
        header.back.setOnClickListener { ui.nav.back() }
        header.close.setOnClickListener {
            leave()
            ui.nav.close()
        }
        header.setPadding(header.paddingLeft, topInset + ctx.dp(4), header.paddingRight, header.paddingBottom)
        root.addView(header, linear(MATCH, WRAP))
        val scroll = ScrollView(ctx).apply { isFillViewport = true }
        form = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(20), ctx.dp(20), ctx.dp(20), ctx.dp(16))
        }
        scroll.addView(form, FrameLayout.LayoutParams(MATCH, WRAP))
        root.addView(scroll, linear(MATCH, 0, 1f))
        buildForm()
        return root
    }

    override fun onTopInset(top: Int) {
        topInset = top
        if (isBuilt) header.setPadding(header.paddingLeft, top + ctx.dp(4), header.paddingRight, header.paddingBottom)
    }

    /** The intro, the fields of [gate], submit and Cancel; what was typed stays in the fields that remain. */
    private fun buildForm() {
        val typed = inputs.filterKeys { it !in selects }.mapValues { it.value.text?.toString().orEmpty() }
        form.removeAllViews()
        inputs.clear()
        errors.clear()
        emailStep = gate == Gate.EmailCollect
        fields = (gate as? Gate.PreChatForm)?.fields.orEmpty()
        val intro = TextView(ctx).apply {
            id = R.id.dw_gate_intro
            text = ui.t(if (emailStep) R.string.dw_email_prompt else R.string.dw_pre_chat_intro)
            textStyle(15f, 400, c.ink2, 1.5f)
        }
        form.addView(intro)
        if (emailStep) {
            // The email step's field has no label of its own: the prompt above it names it.
            form.addView(field(EMAIL_KEY, null, ui.t(R.string.dw_email_placeholder), "email", required = true, options = emptyList(), optionLabels = null, labelView = intro), linear(MATCH, WRAP).apply { topMargin = ctx.dp(14) })
        } else {
            for (f in fields) {
                val label = f.label ?: f.name
                form.addView(
                    field(f.name, label, f.placeholder ?: label, f.type, f.required, f.options, f.optionLabels, labelView = null),
                    linear(MATCH, WRAP).apply { topMargin = ctx.dp(14) },
                )
            }
        }
        val submit = PrimaryButton(ctx, ui, ui.t(if (emailStep) R.string.dw_continue_btn else R.string.dw_pre_chat_submit)).apply {
            id = R.id.dw_gate_submit
            setOnClickListener { submit() }
        }
        form.addView(submit, linear(MATCH, WRAP).apply { topMargin = ctx.dp(20) })
        form.addView(TextView(ctx).apply {
            id = R.id.dw_gate_cancel
            text = ui.t(R.string.dw_cancel)
            textStyle(14f, 400, c.ink2)
            gravity = Gravity.CENTER
            minHeight = ctx.dp(48)
            minWidth = ctx.dp(48)
            setPadding(ctx.dp(14), 0, ctx.dp(14), 0)
            background = ripple(c.withAlpha(c.ink, 0.08), null, ctx.rounded(c.bg2, 10f))
            role(button = true)
            // Back takes the held texts with it (handleBack).
            setOnClickListener { ui.nav.back() }
        }, linear(WRAP, WRAP).apply { topMargin = ctx.dp(4); gravity = Gravity.CENTER_HORIZONTAL })
        for ((name, text) in typed) inputs[name]?.setText(text)
        for (f in fields) {
            val index = selects[f.name]?.let(f.options::indexOf) ?: continue
            if (index >= 0) inputs[f.name]?.setText(labelsOf(f.options, f.optionLabels)[index])
        }
        applyRestored()
    }

    /** A label (13/600 ink-2, a red * when required), the input (48, radius 12, bg-2), the error line. */
    private fun field(name: String, label: String?, placeholder: String, type: String, required: Boolean, options: List<String>, optionLabels: List<String>?, labelView: View?): View {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        var namedBy = labelView
        if (label != null) {
            val text = SpannableStringBuilder(label)
            if (required) {
                text.append(" *")
                text.setSpan(ForegroundColorSpan(c.error), text.length - 1, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val labelText = TextView(ctx).apply {
                this.text = text
                textStyle(13f, 600, c.ink2)
            }
            box.addView(labelText, linear(MATCH, WRAP).apply { bottomMargin = ctx.dp(12) })
            namedBy = labelText
        }
        val select = type == "select"
        val input = EditText(ctx).apply {
            id = if (name == EMAIL_KEY) R.id.dw_gate_email else R.id.dw_gate_field
            tag = name
            // Every field shares its id: the screen keeps the answers itself, the view's own
            // restore would put the last field's text into all of them.
            isSaveEnabled = false
            contentText()
            textStyle(15f, 400, c.ink, 1.35f)
            setHintTextColor(c.ink3)
            hint = placeholder
            minHeight = ctx.dp(48)
            setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
            inputType = when (type) {
                "email" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                "tel", "phone" -> InputType.TYPE_CLASS_PHONE
                "textarea" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            }
            if (type == "textarea") {
                minLines = 3
                gravity = Gravity.TOP or Gravity.START
                minHeight = ctx.dp(80)
            } else {
                isSingleLine = true
                imeOptions = EditorInfo.IME_ACTION_NEXT
            }
            if (name == EMAIL_KEY) {
                imeOptions = EditorInfo.IME_ACTION_DONE
                setOnEditorActionListener { _, action, _ ->
                    if (action == EditorInfo.IME_ACTION_DONE) {
                        submit()
                        true
                    } else {
                        false
                    }
                }
            }
        }
        // Screen readers name the field by its label (the hint when empty), not by a description that hides its text.
        ViewCompat.setAccessibilityDelegate(input, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                namedBy?.let(info::setLabeledBy)
                if (select) info.className = "android.widget.Button"
            }
        })
        inputs[name] = input
        if (select) {
            // A choice: the field shows the chosen option and opens the list (a native list, no Spinner theming).
            input.isFocusable = false
            input.isCursorVisible = false
            input.isLongClickable = false
            val labels = labelsOf(options, optionLabels)
            val chevron = ctx.icon(R.drawable.dw_ic_chevron_down, c.ink3).apply { setBounds(0, 0, ctx.dp(16), ctx.dp(16)) }
            input.setCompoundDrawablesRelative(null, null, chevron, null)
            input.setOnClickListener {
                // The list takes the keyboard's place.
                (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                    ?.hideSoftInputFromWindow(input.windowToken, 0)
                input.rootView.findFocus()?.clearFocus()
                val popup = ListPopupWindow(ctx)
                popup.anchorView = input
                popup.setAdapter(android.widget.ArrayAdapter(ctx, android.R.layout.simple_list_item_1, labels))
                popup.setOnItemClickListener { _, _, position, _ ->
                    selects[name] = options[position]
                    input.setText(labels[position])
                    popup.dismiss()
                }
                popup.show()
            }
        }
        box.addView(input, linear(MATCH, WRAP))
        val error = TextView(ctx).apply {
            id = R.id.dw_field_error
            textStyle(12f, 400, c.error, 1.4f)
            visibility = View.GONE
            setPaddingRelative(ctx.dp(2), 0, 0, 0)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        errors[name] = error
        box.addView(error, linear(MATCH, WRAP).apply { topMargin = ctx.dp(4) })
        input.setOnFocusChangeListener { _, _ -> paint(name) }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (tried) validate(show = true)
            }
        })
        paint(name)
        return box
    }

    /** The owner's option labels when their count matches the options. */
    private fun labelsOf(options: List<String>, optionLabels: List<String>?): List<String> = optionLabels?.takeIf { it.size == options.size } ?: options

    private fun value(name: String): String = selects[name] ?: inputs[name]?.text?.toString().orEmpty()

    /** Focused: brand border on bg; with an error: red, also while focused; else bg-2. */
    private fun paint(name: String) {
        val input = inputs[name] ?: return
        val hasError = errors[name]?.visibility == View.VISIBLE
        input.background = when {
            hasError -> ctx.rounded(if (input.hasFocus()) c.bg else c.bg2, 12f, c.error)
            input.hasFocus() -> ctx.rounded(c.bg, 12f, c.brandText)
            else -> ctx.rounded(c.bg2, 12f, c.line)
        }
    }

    private fun validate(show: Boolean): Map<String, FieldProblem> {
        val problems = if (emailStep) {
            val p = com.deskwoot.sdk.engine.EmailCheck.check(value(EMAIL_KEY))
            if (p != null) mapOf(EMAIL_KEY to p) else emptyMap()
        } else {
            localProblems()
        }
        if (show) showProblems(problems)
        return problems
    }

    /** Each field's error line, from the screen's own check or from the session's answer. */
    private fun showProblems(problems: Map<String, FieldProblem>) {
        for ((name, view) in errors) {
            val problem = problems[name]
            view.visibility = if (problem == null) View.GONE else View.VISIBLE
            view.text = when (problem) {
                FieldProblem.REQUIRED -> ui.t(R.string.dw_field_required)
                FieldProblem.INVALID_EMAIL -> ui.t(R.string.dw_invalid_email)
                FieldProblem.BLOCKED_EMAIL -> ui.t(R.string.dw_real_email)
                null -> ""
            }
            paint(name)
        }
    }

    private fun localProblems(): Map<String, FieldProblem> {
        val out = LinkedHashMap<String, FieldProblem>()
        for (f in fields) {
            val v = value(f.name).trim()
            if (v.isEmpty()) {
                if (f.required) out[f.name] = FieldProblem.REQUIRED
            } else if (f.isEmail) {
                com.deskwoot.sdk.engine.EmailCheck.check(v)?.let { out[f.name] = it }
            }
        }
        return out
    }

    private fun submit() {
        tried = true
        val local = validate(show = true)
        if (local.isNotEmpty()) {
            inputs[local.keys.first()]?.requestFocus()
            return
        }
        // The session checks the answers against the step as it is now; its problems are shown like the screen's own.
        val problems = if (emailStep) {
            ui.actions.submitEmail(value(EMAIL_KEY))?.let { mapOf(EMAIL_KEY to it) }.orEmpty()
        } else {
            ui.actions.submitPreChat(fields.associate { it.name to value(it.name) })
        }
        if (problems.isNotEmpty()) {
            // The step asks more than the form showed: the form follows it first.
            val now = ui.actions.gateBeforeChat()
            if (now != gate && now != Gate.None) {
                gate = now
                buildForm()
            }
            showProblems(problems)
            problems.keys.firstNotNullOfOrNull { inputs[it] }?.requestFocus()
            return
        }
        submitted = true
        // The form never comes back: the thread takes its place, or it returns to the thread it came from.
        ui.nav.replace(Route.Thread(null))
    }

    /**
     * The visitor leaves the step without an answer: the texts written
     * meanwhile go back into the composer in front of its draft, joined by
     * line breaks, and the session drops them from the step.
     * Runs once, whichever way the screen goes.
     */
    private fun leave() {
        if (left || submitted) return
        left = true
        val thread = ui.session.state.value.thread
        val held = ui.actions.heldTexts(thread)
        if (held.isNotEmpty()) {
            val key = thread?.conversationId
            val now = UiMemory.draft(key)
            UiMemory.setDraft(key, (held + listOfNotNull(now.ifEmpty { null })).joinToString("\n"))
        }
        ui.actions.cancelGate()
    }

    /** Cancel, the back arrow and system back: the texts are back before the thread shows again. */
    override fun handleBack(): Boolean {
        leave()
        return false
    }

    /** The back gesture, or any other way off the stack. */
    override fun onRemoved() = leave()

    /** The messenger closes (the ×, a dismiss by the app): the texts wait in the composer for the next time. */
    override fun destroy() {
        if (ctx.activity()?.isFinishing == true) leave()
    }

    override fun render(state: SessionState) {
        if (!isBuilt || submitted || left) return
        val now = ui.actions.gateBeforeChat()
        when {
            // No step is due any more (the app's user was verified meanwhile): the texts go back and the thread shows.
            now == Gate.None -> {
                leave()
                ui.nav.replace(Route.Thread(null))
            }
            // Another step, or other fields (an email the server now knows): the form follows.
            now != gate -> {
                gate = now
                buildForm()
            }
        }
    }

    override fun saveState(out: Bundle) {
        for ((name, input) in inputs) {
            val chosen = selects[name]
            if (chosen != null) {
                out.putString(SELECT + name, chosen.take(MAX_SAVED_FIELD))
            } else {
                out.putString(FIELD + name, input.text?.toString().orEmpty().take(MAX_SAVED_FIELD))
            }
        }
    }

    override fun restoreState(saved: Bundle) {
        for (key in saved.keySet()) {
            when {
                key.startsWith(FIELD) -> saved.getString(key)?.let { restored[key.removePrefix(FIELD)] = it }
                key.startsWith(SELECT) -> saved.getString(key)?.let { restoredSelects[key.removePrefix(SELECT)] = it }
            }
        }
        if (isBuilt) applyRestored()
    }

    /** Saved answers into the fields: a choice shows its label and keeps its option. */
    private fun applyRestored() {
        for ((name, text) in restored) {
            if (name !in selects) inputs[name]?.setText(text)
        }
        for ((name, option) in restoredSelects) {
            val input = inputs[name] ?: continue
            val field = fields.firstOrNull { it.name == name } ?: continue
            val index = field.options.indexOf(option)
            if (index < 0) continue
            selects[name] = option
            input.setText(labelsOf(field.options, field.optionLabels)[index])
        }
        restored.clear()
        restoredSelects.clear()
    }

    private fun Context.activity(): Activity? {
        var c: Context? = this
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    private companion object {
        const val EMAIL_KEY = "\u0000email-step"
        const val FIELD = "field_"
        const val SELECT = "select_"

        /** What one answer may put into the saved state. */
        const val MAX_SAVED_FIELD = 2_000
    }
}
