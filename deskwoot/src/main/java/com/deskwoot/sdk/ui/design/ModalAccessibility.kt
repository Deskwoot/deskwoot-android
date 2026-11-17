package com.deskwoot.sdk.ui.design

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import java.lang.ref.WeakReference

/**
 * Something on top that TalkBack reads alone while it is open (a Direkt
 * sheet, the image viewer, the emoji panel, the attachment menu): the views
 * beneath leave TalkBack's order and come back as they were, TalkBack's
 * focus moves into it, and when it closes the focus goes back to the
 * control that opened it.
 */
internal class ModalAccessibility {
    private var hidden: List<Pair<View, Int>> = emptyList()
    private var opener: WeakReference<View>? = null

    /** Whether something is open now. */
    val isOpen: Boolean get() = hidden.isNotEmpty() || opener != null

    /**
     * [beneath] leave TalkBack's order; [focus] gets TalkBack's focus now
     * (none: the system moves it, as into a menu's own window); [opener]
     * gets it back on [close].
     */
    fun open(beneath: List<View>, opener: View?, focus: View?) {
        restore()
        hidden = beneath.distinct().map { it to it.importantForAccessibility }
        hidden.forEach { (view, _) -> view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        this.opener = opener?.let(::WeakReference)
        focus?.let(::moveFocus)
    }

    /** Everything beneath comes back; the opener gets the focus when it still shows and [returnFocus]. */
    fun close(returnFocus: Boolean = true) {
        restore()
        val back = opener?.get()
        opener = null
        if (returnFocus && back != null) moveFocus(back)
    }

    private fun restore() {
        hidden.forEach { (view, before) -> view.importantForAccessibility = before }
        hidden = emptyList()
    }

    internal companion object {
        /**
         * Moves TalkBack's focus to [view] once it is laid out; nothing when
         * TalkBack is off or the view no longer shows. Moving it is the point
         * here: otherwise TalkBack would stay on a control that is hidden now,
         * or start over at the top of the screen after a close.
         */
        @SuppressLint("AccessibilityFocus")
        fun moveFocus(view: View) {
            view.post {
                if (view.isAttachedToWindow && view.isShown) view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
            }
        }

        /** The view under [root] that has TalkBack's focus, if any. */
        fun focused(root: View): View? {
            if (root.isAccessibilityFocused) return root
            if (root is ViewGroup) {
                for (i in 0 until root.childCount) focused(root.getChildAt(i))?.let { return it }
            }
            return null
        }
    }
}
