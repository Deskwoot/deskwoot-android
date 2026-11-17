package com.deskwoot.sdk.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import com.deskwoot.sdk.model.SessionState
import com.deskwoot.sdk.ui.design.ModalAccessibility
import com.deskwoot.sdk.ui.widgets.dp

/**
 * The messenger's screens as one stack in one container: the [Navigator]
 * decides the routes, this keeps a [Screen] per route, moves between them
 * (a short slide and fade, none with reduced motion), feeds them the
 * session state and lets the predictive back gesture show the screen
 * behind the current one while the finger moves.
 */
internal class Messenger(
    private val container: FrameLayout,
    private val create: (Route, Ui) -> Screen,
    /** Back on the start page: the Activity closes. */
    private val onBackStateChanged: (enabled: Boolean) -> Unit,
    /** Another screen is on top (the status bar follows Empfang's band). */
    private val onTopChanged: (Screen) -> Unit = {},
) {
    private val navigator = Navigator()
    private val stack = ArrayList<Screen>()

    /** The screens of the stack, the one on screen last. */
    val screens: List<Screen> get() = stack
    private var state: SessionState? = null
    private var shown = false
    private var gesture: Gesture? = null
    lateinit var ui: Ui
        private set

    val routes: List<Route> get() = navigator.routes

    val top: Screen? get() = screens.lastOrNull()

    /** Starts with [routes] (the last one on screen). */
    fun start(ui: Ui, routes: List<Route>) {
        this.ui = ui
        followDesign()
        navigator.reset(routes)
        sync(Direction.NONE)
    }

    val started: Boolean get() = this::ui.isInitialized

    /**
     * Throws every screen away, with what it showed and kept (a draft in its
     * field, a scroll position, an open panel), and forgets the last state:
     * the visitor changed, or the session did. [start]
     * builds the screens again once the next state is there.
     */
    fun clear() {
        destroy()
        state = null
    }

    /** The screen of [route] in the stack, the newest one when there are more. */
    fun screenFor(route: Route): Screen? = screens.lastOrNull { it.route == route }

    fun goTo(route: Route) {
        cancelGesture()
        if (navigator.goTo(route)) sync(Direction.FORWARD)
    }

    fun replace(route: Route) {
        cancelGesture()
        if (navigator.current == route) return
        navigator.replace(route)
        sync(Direction.FORWARD)
    }

    fun reset(routes: List<Route>) {
        cancelGesture()
        navigator.reset(routes)
        sync(Direction.FORWARD)
    }

    /** One step back; false when there is nothing behind the current screen (the Activity closes). */
    fun back(): Boolean {
        if (top?.handleBack() == true) {
            updateBackState()
            return true
        }
        if (!navigator.back()) return false
        sync(Direction.BACK)
        return true
    }

    /**
     * The look changed (language, brand, light or dark, the design): every
     * screen is built again, for the same routes or for [routes], the same
     * trail in another design ([LookSwitch]). A screen takes the saved state
     * of the old screen that showed the same thing (the chat's reply target
     * and rating, a form's answers), never a replay of how the messenger was
     * opened.
     */
    fun rebuild(next: Ui, routes: List<Route> = navigator.routes) {
        cancelGesture()
        val before = ui.design
        val saved = screens.mapTo(ArrayList()) { s -> LookSwitch.sameScreen(s.route, before) to Bundle().also { s.saveState(it) } }
        if (shown) top?.onHidden()
        screens.forEach { it.destroy() }
        stack.clear()
        openers.clear()
        container.removeAllViews()
        ui = next
        followDesign()
        navigator.reset(routes)
        navigator.routes.forEach { route ->
            val screen = create(route, next)
            val same = LookSwitch.sameScreen(route, next.design)
            val index = saved.indexOfFirst { it.first == same }
            if (index >= 0) screen.restoreState(saved.removeAt(index).second)
            stack += screen
        }
        attachAll()
        state?.let(::render)
        if (shown) top?.onShown()
        top?.let(onTopChanged)
        updateBackState()
    }

    fun render(state: SessionState) {
        this.state = state
        top?.render(state)
        // The screen under a sheet stays in view and stays current.
        base()?.takeIf { it !== top }?.render(state)
        gesture?.below?.render(state)
    }

    /** A sheet is never a top level view: it opens over the chat. */
    private fun followDesign() {
        val design = ui.design
        navigator.topLevel = { it.isTopLevel && !design.isSheet(it) }
    }

    /** The screen that fills the window: the top one, or the one under the sheets on top. */
    private fun base(): Screen? = screens.lastOrNull { !it.isOverlay }

    fun setShown(value: Boolean) {
        if (shown == value) return
        shown = value
        if (value) top?.onShown() else top?.onHidden()
    }

    fun save(out: Bundle) {
        out.putStringArrayList(KEY_ROUTES, ArrayList(navigator.routes.map { it.encode() }))
        out.putString(KEY_DESIGN, ui.design.id)
        out.putStringArrayList(KEY_KINDS, ArrayList(screens.map { LookSwitch.sameScreen(it.route, ui.design) }))
        screens.forEachIndexed { index, screen ->
            val b = Bundle()
            screen.saveState(b)
            out.putBundle("$KEY_SCREEN$index", b)
        }
    }

    /** The saved screen states, applied once the screens exist again: each to the screen that shows the same thing. */
    fun restoreStates(saved: Bundle) {
        val kinds = saved.getStringArrayList(KEY_KINDS)
        if (kinds == null) {
            screens.forEachIndexed { index, screen -> saved.getBundle("$KEY_SCREEN$index")?.let(screen::restoreState) }
            return
        }
        val pool = kinds.mapIndexedNotNullTo(ArrayList()) { index, kind -> saved.getBundle("$KEY_SCREEN$index")?.let { kind to it } }
        for (screen in screens) {
            val same = LookSwitch.sameScreen(screen.route, ui.design)
            val index = pool.indexOfFirst { it.first == same }
            if (index >= 0) screen.restoreState(pool.removeAt(index).second)
        }
    }

    fun destroy() {
        if (!started) return
        cancelGesture()
        if (shown) top?.onHidden()
        screens.forEach { it.destroy() }
        stack.clear()
        openers.clear()
        container.removeAllViews()
    }

    /** Whether a back step stays inside the messenger. */
    fun updateBackState() {
        onBackStateChanged(navigator.previous() != null || top?.handleBackPending() == true)
    }

    // ── Moving between screens ───────────────────────────────────────────

    private enum class Direction { NONE, FORWARD, BACK }

    private fun sync(direction: Direction) {
        val routes = navigator.routes
        val pool = stack.toMutableList()
        val next = ArrayList<Screen>(routes.size)
        val oldTop = stack.lastOrNull()
        // Read before the screens beneath a sheet leave TalkBack's order, which takes the focus off them.
        val focusedBefore = oldTop?.takeIf { it.isBuilt }?.view?.let(ModalAccessibility::focused)
        for (route in routes) {
            val exact = pool.firstOrNull { it.route == route }
            if (exact != null) {
                pool.remove(exact)
                next += exact
                continue
            }
            val inPlace = pool.firstOrNull { it.canShow(route) }
            if (inPlace != null) {
                pool.remove(inPlace)
                inPlace.show(route)
                next += inPlace
                continue
            }
            next += create(route, ui)
        }
        val newTop = next.last()
        val before = stack.toList()
        stack.clear()
        stack += next
        if (oldTop !== newTop) {
            if (shown) oldTop?.onHidden()
            // A field of the screen that leaves takes its keyboard with it.
            if (oldTop != null) hideKeyboard()
        }
        // The views of the stack in order; leaving screens stay on top of them until they are gone.
        next.forEachIndexed { index, screen ->
            val v = screen.view
            if (v.parent == null) container.addView(v, index.coerceAtMost(container.childCount), FrameLayout.LayoutParams(MATCH, MATCH))
        }
        state?.let { newTop.render(it) }
        newTop.view.visibility = View.VISIBLE
        val leaving = pool
        val moving = oldTop?.takeIf { direction != Direction.NONE && it !== newTop && !ui.reducedMotion }
        val sheetIn = newTop.isOverlay && oldTop?.isOverlay != true
        val sheetOut = oldTop?.isOverlay == true && !newTop.isOverlay && oldTop in leaving
        if (moving != null && (newTop.isOverlay || moving.isOverlay)) {
            // Sheets: only the first one slides up; between sheets nothing moves; the last one slides down.
            when {
                sheetIn -> {
                    (newTop as? com.deskwoot.sdk.ui.design.SheetScreen)?.slideUp()
                    finishLeaving(leaving)
                }
                sheetOut -> {
                    moving.view.bringToFront()
                    newTop.view.visibility = View.VISIBLE
                    (moving as? com.deskwoot.sdk.ui.design.SheetScreen)?.slideDown { finishLeaving(leaving) } ?: finishLeaving(leaving)
                }
                else -> finishLeaving(leaving)
            }
        } else if (moving != null) {
            if (moving in leaving) {
                // The current screen leaves (back, or replaced): it slides out over the one behind it.
                slideOut(moving.view, direction == Direction.BACK) { finishLeaving(leaving) }
            } else {
                // A new screen comes on top of the current one.
                slideIn(newTop.view) { hideCovered() }
                finishLeaving(leaving)
            }
        } else {
            finishLeaving(leaving)
            hideCovered()
        }
        if (shown && oldTop !== newTop) newTop.onShown()
        if (oldTop !== newTop) {
            onTopChanged(newTop)
            followSheetFocus(focusedBefore, newTop, newTop !in before, leaving)
        }
        hideBeneathSheet()
        updateBackState()
    }

    // ── TalkBack and the sheets ──────────────────────────────────────────

    /** The control each sheet was opened from, which gets TalkBack's focus back when the sheet goes. */
    private val openers = HashMap<Screen, java.lang.ref.WeakReference<View>>()

    /**
     * While a sheet is on top, TalkBack reads it alone: every other screen
     * of the stack (the chat in view beneath it included) leaves its order,
     * and comes back once no sheet is on top.
     */
    private fun hideBeneathSheet() {
        val sheetOnTop = top?.isOverlay == true
        for (s in screens) {
            s.view.importantForAccessibility = if (sheetOnTop && s !== top) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        }
    }

    /**
     * A sheet that came on top takes TalkBack's focus (its title) and keeps
     * the control it was opened from; a sheet that went gives the focus back
     * to that control when it shows again.
     */
    private fun followSheetFocus(opener: View?, newTop: Screen, arrived: Boolean, leaving: List<Screen>) {
        if (newTop.isOverlay && arrived) {
            opener?.let { openers[newTop] = java.lang.ref.WeakReference(it) }
            (newTop as? com.deskwoot.sdk.ui.design.SheetScreen)?.focusTarget()?.let(ModalAccessibility::moveFocus)
            return
        }
        val gone = leaving.filter { it.isOverlay }
        if (gone.isEmpty()) return
        val back = gone.mapNotNull { openers.remove(it)?.get() }.firstOrNull { isInside(it, newTop.view) }
        back?.let(ModalAccessibility::moveFocus)
    }

    private fun isInside(view: View, root: View): Boolean {
        var v: View? = view
        while (v != null) {
            if (v === root) return true
            v = v.parent as? View
        }
        return false
    }

    private fun hideKeyboard() {
        val focused = container.findFocus()
        focused?.clearFocus()
        val imm = container.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(container.windowToken, 0)
    }

    private fun attachAll() {
        screens.forEach { s ->
            if (s.view.parent == null) container.addView(s.view, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        hideCovered()
    }

    private fun hideCovered() {
        val topView = top?.view
        // Under a sheet the chat stays in view.
        val baseView = if (top?.isOverlay == true) base()?.view else null
        for (s in screens) {
            if (s.view !== topView && s.view !== baseView && s.view !== gesture?.below?.view) s.view.visibility = View.GONE
        }
        baseView?.visibility = View.VISIBLE
        topView?.visibility = View.VISIBLE
        hideBeneathSheet()
    }

    private fun finishLeaving(leaving: List<Screen>) {
        for (s in leaving) {
            (s.view.parent as? ViewGroup)?.removeView(s.view)
            s.onRemoved()
            s.destroy()
        }
        hideCovered()
    }

    private fun endSign(): Float = if (ui.rtl) -1f else 1f

    private fun slideIn(view: View, done: () -> Unit) {
        view.alpha = 0f
        view.translationX = view.dp(32) * endSign()
        view.animate().alpha(1f).translationX(0f).setDuration(240).setInterpolator(DecelerateInterpolator(1.6f))
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.animate().setListener(null)
                    done()
                }
            }).start()
    }

    private fun slideOut(view: View, backwards: Boolean, done: () -> Unit) {
        // Keep the leaving view on top of the one it reveals.
        view.bringToFront()
        top?.view?.visibility = View.VISIBLE
        val distance = view.dp(if (backwards) 48 else 24) * endSign()
        view.animate().alpha(0f).translationX(distance).setDuration(200).setInterpolator(DecelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.animate().setListener(null)
                    view.alpha = 1f
                    view.translationX = 0f
                    done()
                }
            }).start()
    }

    // ── Predictive back ──────────────────────────────────────────────────

    /** A back gesture in progress: the screen it would reveal, made only for the gesture when it is not in the stack. */
    private class Gesture(val below: Screen, val temporary: Boolean)

    fun gestureStarted() {
        cancelGesture()
        if (top?.handleBackPending() == true) return
        val target = navigator.previous() ?: return
        val current = top ?: return
        val inStack = screens.getOrNull(screens.lastIndex - 1)?.takeIf { it.route == target }
        val below = inStack ?: create(target, ui)
        if (below.view.parent == null) container.addView(below.view, 0, FrameLayout.LayoutParams(MATCH, MATCH))
        below.view.visibility = View.VISIBLE
        state?.let(below::render)
        current.view.bringToFront()
        gesture = Gesture(below, temporary = inStack == null)
    }

    /** [progress] 0 to 1; [fromLeftEdge] whether the finger started at the left edge. */
    fun gestureProgressed(progress: Float, fromLeftEdge: Boolean) {
        gesture ?: return
        val v = top?.view ?: return
        if (ui.reducedMotion) return
        val shift = v.dp(24) * progress * (if (fromLeftEdge) 1f else -1f)
        v.translationX = shift
        val scale = 1f - 0.08f * progress
        v.scaleX = scale
        v.scaleY = scale
    }

    fun gestureCancelled() {
        val g = gesture ?: return
        val v = top?.view
        v?.animate()?.translationX(0f)?.scaleX(1f)?.scaleY(1f)?.setDuration(150)?.withEndAction {
            if (gesture === g) cancelGesture()
        }?.start() ?: cancelGesture()
    }

    /** The gesture ended with a back step. */
    fun gestureCommitted(): Boolean {
        val g = gesture
        if (g == null) return back()
        val v = top?.view
        gesture = null
        // A screen made only for the gesture becomes the real one.
        if (g.temporary) stack.add(0, g.below)
        if (v == null || ui.reducedMotion) {
            resetTransform(v)
            if (navigator.back()) sync(Direction.NONE)
            return true
        }
        v.animate().alpha(0f).translationX(v.translationX + v.dp(48) * (if (v.translationX >= 0) 1f else -1f)).setDuration(160)
            .withEndAction {
                resetTransform(v)
                if (navigator.back()) sync(Direction.NONE)
            }.start()
        return true
    }

    private fun dropTemporary(screen: Screen) {
        (screen.view.parent as? ViewGroup)?.removeView(screen.view)
        screen.destroy()
    }

    private fun cancelGesture() {
        val g = gesture ?: return
        gesture = null
        resetTransform(top?.view)
        if (g.temporary) dropTemporary(g.below) else hideCovered()
    }

    private fun resetTransform(v: View?) {
        v ?: return
        v.animate().cancel()
        v.translationX = 0f
        v.scaleX = 1f
        v.scaleY = 1f
        v.alpha = 1f
    }

    internal companion object {
        const val KEY_ROUTES: String = "dw_routes"
        const val KEY_SCREEN: String = "dw_screen_"

        /** The design the saved routes are routes of. */
        const val KEY_DESIGN: String = "dw_design"

        /** What each saved screen showed ([LookSwitch.sameScreen]), so a state finds its screen in another design. */
        const val KEY_KINDS: String = "dw_kinds"
        private const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
    }
}

/** A screen that takes the back step itself right now (an open panel, a field in a special mode). */
internal fun Screen.handleBackPending(): Boolean = (this as? BackAware)?.wantsBack == true

/** Implemented by screens with something inside that a back step closes first. */
internal interface BackAware {
    val wantsBack: Boolean
}
