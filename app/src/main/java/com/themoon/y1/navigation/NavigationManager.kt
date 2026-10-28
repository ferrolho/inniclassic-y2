package com.themoon.y1.navigation

import android.view.View
import android.view.ViewGroup
import com.themoon.y1.controllers.ScreenController
import java.util.ArrayDeque

/**
 * Coordinates transitions between screen states, manages backstack history,
 * and notifies active ScreenController instances.
 */
class NavigationManager private constructor() {

    /** A screen to come back to, and its selected row when the deeper screen opened. */
    data class BackEntry(val state: ScreenState, val row: RowKey?)

    private val backStack = ArrayDeque<BackEntry>()

    /** The selected main menu row when the main menu last opened a screen (the main menu clears the back stack). */
    private var mainMenuRow: RowKey? = null
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
     * Back rule (as on the iPod): a screen that opens going down selects its first row; Back selects again the
     * row that opened the deeper screen. [returnRow] is that row when Back comes to [newState].
     */
    private fun navigate(newState: ScreenState, addToBackStack: Boolean, returnRow: RowKey?) {
        if (newState == currentState && activeController != null) {
            activeController?.onRefreshUI()
            return
        }

        val previous = currentState
        val leaving = activeController
        val row = if (previous != null && addToBackStack) leaving?.selectedRow() else null
        if (previous is ScreenState.MainMenu && addToBackStack) mainMenuRow = row
        if (newState is ScreenState.MainMenu) {
            backStack.clear()
        } else if (previous != null && addToBackStack) {
            backStack.push(BackEntry(leaving?.backState(previous) ?: previous, row))
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
        val back = if (newState is ScreenState.MainMenu) mainMenuRow else returnRow
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
        mainMenuRow = null
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
