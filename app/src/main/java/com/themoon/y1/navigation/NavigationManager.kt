package com.themoon.y1.navigation

import android.view.View
import android.view.ViewGroup
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.views.ListRows
import java.util.ArrayDeque

/**
 * Coordinates transitions between screen states, manages backstack history,
 * and notifies active ScreenController instances.
 *
 * The back stack is the one record of the way back, as on the iPod: each list that opens going down (a screen,
 * a Music level, a Settings group or row screen) is a [ScreenState]. The list that it leaves is kept as an entry
 * with its selected row; Back takes the top entry, the controller builds that list again from the state, and the
 * row is selected again at the same height. The main menu is the bottom entry.
 */
class NavigationManager private constructor() {

    /** A list to come back to (its route key), and its selected row when the deeper list opened. */
    data class BackEntry(val state: ScreenState, val row: RowKey?)

    private val backStack = ArrayDeque<BackEntry>()
    private val controllerMap = HashMap<Class<out ScreenState>, ScreenController>()

    var currentState: ScreenState? = null
        private set

    var activeController: ScreenController? = null

    interface StateChangeListener {
        fun onScreenTransition(from: ScreenState, to: ScreenState)
    }

    private var transitionListener: StateChangeListener? = null

    fun setTransitionListener(listener: StateChangeListener?) {
        this.transitionListener = listener
    }

    fun registerController(stateClass: Class<out ScreenState>, controller: ScreenController) {
        controllerMap[stateClass] = controller
    }

    fun navigateTo(newState: ScreenState, addToBackStack: Boolean = true) {
        navigate(newState, addToBackStack, null)
    }

    /**
     * Back rule (as on the iPod): a list that opens going down selects its first row; Back selects again the
     * row that opened the deeper list. [returnRow] is that row when Back comes to [newState].
     */
    private fun navigate(newState: ScreenState, addToBackStack: Boolean, returnRow: RowKey?) {
        if (newState == currentState && activeController != null) {
            activeController?.onRefreshUI()
            return
        }

        ListRows.cancelPending()
        val previous = currentState
        var back = returnRow
        if (newState is ScreenState.MainMenu) {
            // The bottom entry: going to the main menu (with Back or directly) takes all entries off
            if (back == null) back = backStack.lastOrNull { it.state is ScreenState.MainMenu }?.row
            backStack.clear()
        } else if (previous != null && addToBackStack) {
            backStack.push(BackEntry(previous, activeController?.selectedRow()))
        }

        com.themoon.y1.eventlog.EventLog.screen(previous, newState, backStack.size)
        activeController?.onExit()

        currentState = newState
        val controller = findControllerForState(newState)
        activeController = controller

        val targetRoot = controller?.rootView
        val handledRoots = HashSet<View>()
        if (targetRoot != null) {
            targetRoot.visibility = View.VISIBLE
            handledRoots.add(targetRoot)
        }
        controllerMap.values.forEach { ctrl ->
            if (ctrl.rootView !in handledRoots && ctrl.rootView.parent != null) {
                ctrl.rootView.visibility = View.GONE
                handledRoots.add(ctrl.rootView)
            }
        }

        controller?.onEnter(newState)
        if (back != null) controller?.selectRow(back)
        if (previous != null) {
            transitionListener?.onScreenTransition(previous, newState)
        }
    }

    fun popBackStack(): Boolean {
        if (backStack.isEmpty()) {
            if (currentState != ScreenState.MainMenu) {
                navigateTo(ScreenState.MainMenu, addToBackStack = false)
                return true
            }
            return false
        }

        val entry = backStack.pop()
        navigate(entry.state, addToBackStack = false, returnRow = entry.row)
        return true
    }

    /** The state of the top back-stack entry (where Back goes), or null. */
    fun previousState(): ScreenState? = backStack.peek()?.state

    fun navigateBack(): Boolean {
        if (activeController?.onBackPressed() == true) {
            return true
        }

        return popBackStack()
    }

    fun clearBackStack() {
        backStack.clear()
    }

    fun reset() {
        backStack.clear()
        currentState = null
        activeController = null
        controllerMap.clear()
        transitionListener = null
    }

    private fun findControllerForState(state: ScreenState): ScreenController? {
        return controllerMap[state.javaClass]
            ?: controllerMap.entries.firstOrNull { it.key.isAssignableFrom(state.javaClass) }?.value
    }

    companion object {
        @Volatile
        private var instance: NavigationManager? = null

        fun getInstance(): NavigationManager {
            return instance ?: synchronized(this) {
                instance ?: NavigationManager().also { instance = it }
            }
        }
    }
}
