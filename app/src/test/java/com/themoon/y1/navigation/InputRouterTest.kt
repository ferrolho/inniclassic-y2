package com.themoon.y1.navigation

import android.view.KeyEvent
import android.view.ViewGroup
import com.themoon.y1.controllers.ScreenController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InputRouterTest {

    companion object {
        fun createDummyViewGroup(): ViewGroup {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
            val unsafe = field.get(null)
            val allocateMethod = unsafeClass.getMethod("allocateInstance", Class::class.java)
            return allocateMethod.invoke(unsafe, android.widget.FrameLayout::class.java) as ViewGroup
        }
    }

    private open class TestController(
        override val rootView: ViewGroup = createDummyViewGroup()
    ) : ScreenController {
        var backPressed = false
        var handleBackResult = false

        override fun onBackPressed(): Boolean {
            backPressed = true
            return handleBackResult
        }
    }

    private val navManager = NavigationManager.getInstance()
    private val inputRouter = InputRouter.getInstance()

    private val mainMenuController = TestController()
    private val subController = TestController()

    @Before
    fun setUp() {
        navManager.reset()
        inputRouter.resetBackSuppression()
        navManager.registerController(ScreenState.MainMenu::class.java, mainMenuController)
        navManager.registerController(ScreenState.CoverFlow::class.java, subController)
    }

    @After
    fun tearDown() {
        navManager.reset()
        inputRouter.resetBackSuppression()
    }

    @Test
    fun testOnKeyDown_backKeyConsumedByActiveController() {
        navManager.navigateTo(ScreenState.CoverFlow)
        subController.handleBackResult = true

        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
        val handled = inputRouter.onKeyDown(KeyEvent.KEYCODE_BACK, event, subController)

        assertTrue("Back key should be consumed by activeController", handled)
        assertTrue(subController.backPressed)
        assertEquals(ScreenState.CoverFlow, navManager.currentState)
    }

    @Test
    fun testOnKeyDown_backKeyPopsBackStackWhenControllerDoesNotHandle() {
        navManager.navigateTo(ScreenState.MainMenu)
        navManager.navigateTo(ScreenState.CoverFlow)
        subController.handleBackResult = false

        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
        val handled = inputRouter.onKeyDown(KeyEvent.KEYCODE_BACK, event, subController)

        assertTrue("Back key should pop backstack and return true", handled)
        assertEquals(ScreenState.MainMenu, navManager.currentState)
    }

    @Test
    fun testOnKeyDown_backKeyOnMainMenuAlwaysConsumed() {
        navManager.navigateTo(ScreenState.MainMenu)
        mainMenuController.handleBackResult = false

        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
        val handled = inputRouter.onKeyDown(KeyEvent.KEYCODE_BACK, event, mainMenuController)

        assertTrue("Back key on main menu must always be consumed to prevent closing the launcher", handled)
        assertEquals(ScreenState.MainMenu, navManager.currentState)
    }

    @Test
    fun testOnKeyDown_key19OnMainMenuAlwaysConsumed() {
        navManager.navigateTo(ScreenState.MainMenu)
        mainMenuController.handleBackResult = false

        val event = KeyEvent(KeyEvent.ACTION_DOWN, 19)
        val handled = inputRouter.onKeyDown(19, event, mainMenuController)

        assertTrue("Key 19 on main menu must always be consumed", handled)
        assertEquals(ScreenState.MainMenu, navManager.currentState)
    }

    @Test
    fun testOnKeyUp_backKeysAlwaysConsumed() {
        val eventBack = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)
        assertTrue(inputRouter.onKeyUp(KeyEvent.KEYCODE_BACK, eventBack, mainMenuController))

        val event19 = KeyEvent(KeyEvent.ACTION_UP, 19)
        assertTrue(inputRouter.onKeyUp(19, event19, mainMenuController))
    }

    @Test
    fun testOnKeyDown_mediaKeysConsumedWhenNotHandledByController() {
        val playEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        assertTrue("Play/pause key should be handled globally when controller does not consume",
            inputRouter.onKeyDown(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, playEvent, mainMenuController))

        val playEvent85 = KeyEvent(KeyEvent.ACTION_DOWN, 85)
        assertTrue("Key 85 should be handled globally when controller does not consume",
            inputRouter.onKeyDown(85, playEvent85, mainMenuController))

        val nextEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT)
        assertTrue("Next track key should be handled globally when controller does not consume",
            inputRouter.onKeyDown(KeyEvent.KEYCODE_MEDIA_NEXT, nextEvent, mainMenuController))

        val prevEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        assertTrue("Prev track key should be handled globally when controller does not consume",
            inputRouter.onKeyDown(KeyEvent.KEYCODE_MEDIA_PREVIOUS, prevEvent, mainMenuController))
    }

    @Test
    fun testSkipKeys_shortPressIsNotAHold() {
        inputRouter.onSkipKeyDown(KeyEvent.KEYCODE_MEDIA_NEXT, 0, 0L)
        assertFalse("A first key down must not count as a hold", inputRouter.isSkipKeyHeld)

        val upEvent = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT)
        assertTrue("Next key release should be consumed",
            inputRouter.onKeyUp(KeyEvent.KEYCODE_MEDIA_NEXT, upEvent, mainMenuController))
    }

    @Test
    fun testSkipKeys_holdSeeksAndNextPressResets() {
        inputRouter.onSkipKeyDown(KeyEvent.KEYCODE_MEDIA_PREVIOUS, 0, 0L)
        inputRouter.onSkipKeyDown(KeyEvent.KEYCODE_MEDIA_PREVIOUS, 1, 500L)
        assertTrue("An auto-repeat must mark the key as held", inputRouter.isSkipKeyHeld)
        assertFalse("Releasing after a seek must not skip the track",
            inputRouter.onSkipKeyUp(KeyEvent.KEYCODE_MEDIA_PREVIOUS, 100L))

        inputRouter.onSkipKeyDown(KeyEvent.KEYCODE_MEDIA_PREVIOUS, 0, 0L)
        assertFalse("A new press must clear the held state", inputRouter.isSkipKeyHeld)
        assertTrue("A short press must skip the track on release",
            inputRouter.onSkipKeyUp(KeyEvent.KEYCODE_MEDIA_PREVIOUS, 100L))
    }

    @Test
    fun testSkipKeys_longPressDoesNotSkipWhenRepeatsWereConsumedElsewhere() {
        // e.g. the FM radio screen consumes the repeats for station seek, so only the first key down arrives
        inputRouter.onSkipKeyDown(KeyEvent.KEYCODE_MEDIA_NEXT, 0, 0L)
        assertFalse(inputRouter.isSkipKeyHeld)
        assertFalse("A long press must not skip the track on release",
            inputRouter.onSkipKeyUp(KeyEvent.KEYCODE_MEDIA_NEXT, 1500L))
    }

    @Test
    fun testSkipKeys_seekStepGrowsWithHoldTime() {
        assertEquals(2_000, InputRouter.seekStepMs(500L))
        assertEquals(2_000, InputRouter.seekStepMs(1_999L))
        assertEquals(5_000, InputRouter.seekStepMs(2_000L))
        assertEquals(5_000, InputRouter.seekStepMs(4_999L))
        assertEquals(15_000, InputRouter.seekStepMs(5_000L))
        assertEquals(15_000, InputRouter.seekStepMs(7_999L))
        assertEquals(30_000, InputRouter.seekStepMs(8_000L))
        assertEquals(30_000, InputRouter.seekStepMs(60_000L))
    }

    @Test
    fun testSkipKeys_holdSeeksEvery200msWithGrowingSteps() {
        inputRouter.onSkipKeyDown(KeyEvent.KEYCODE_MEDIA_PREVIOUS, 0, 0L)
        // Repeats arrive every 50 ms after 500 ms; only one in four seeks
        assertEquals(-2_000, inputRouter.onSkipKeyDown(KeyEvent.KEYCODE_MEDIA_PREVIOUS, 1, 500L))
        assertEquals(0, inputRouter.onSkipKeyDown(KeyEvent.KEYCODE_MEDIA_PREVIOUS, 2, 550L))
        assertEquals(0, inputRouter.onSkipKeyDown(KeyEvent.KEYCODE_MEDIA_PREVIOUS, 3, 600L))
        assertEquals(0, inputRouter.onSkipKeyDown(KeyEvent.KEYCODE_MEDIA_PREVIOUS, 4, 650L))
        assertEquals(-2_000, inputRouter.onSkipKeyDown(KeyEvent.KEYCODE_MEDIA_PREVIOUS, 5, 700L))
        assertEquals(-5_000, inputRouter.onSkipKeyDown(KeyEvent.KEYCODE_MEDIA_PREVIOUS, 32, 2_050L))
        assertEquals(-30_000, inputRouter.onSkipKeyDown(KeyEvent.KEYCODE_MEDIA_PREVIOUS, 200, 10_000L))

        // A new hold starts slow again
        inputRouter.onSkipKeyDown(KeyEvent.KEYCODE_MEDIA_PREVIOUS, 0, 0L)
        assertEquals(-2_000, inputRouter.onSkipKeyDown(KeyEvent.KEYCODE_MEDIA_PREVIOUS, 1, 500L))
    }

    @Test
    fun testSkipKeys_forwardSeekStopsBeforeTrackEnd() {
        assertEquals("Unknown duration seeks the full step", 30_000, InputRouter.forwardSeekMs(10_000, 0, 30_000))
        assertEquals(15_000, InputRouter.forwardSeekMs(10_000, 180_000, 15_000))
        assertEquals("Stops 1 s before the end", 9_000, InputRouter.forwardSeekMs(170_000, 180_000, 15_000))
        assertEquals("Never seeks backwards near the end", 0, InputRouter.forwardSeekMs(179_500, 180_000, 15_000))
    }

    @Test
    fun testSuppressBackUntilRelease_blocksBackEventsUntilUp() {
        navManager.navigateTo(ScreenState.MainMenu)
        navManager.navigateTo(ScreenState.CoverFlow)
        subController.handleBackResult = false

        inputRouter.suppressBackUntilRelease(500L)
        assertTrue(inputRouter.isBackSuppressed)

        // Down event while suppressed
        val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
        val handledDown = inputRouter.onKeyDown(KeyEvent.KEYCODE_BACK, downEvent, subController)
        assertTrue(handledDown)
        assertEquals(ScreenState.CoverFlow, navManager.currentState)
        assertFalse(subController.backPressed)

        // Up event clears suppression
        val upEvent = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)
        inputRouter.onKeyUp(KeyEvent.KEYCODE_BACK, upEvent, subController)
        assertFalse(inputRouter.isBackSuppressed)
    }
}
