package com.example.sitedraft

import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.sitedraft.navigation.SiteDraftNavKey
import com.example.sitedraft.ui.MainScreen
import com.example.sitedraft.ui.canvas.StylusButton
import com.example.sitedraft.ui.theme.SiteDraftTheme

class MainActivity : ComponentActivity() {

    // S Pen side button, as traced on a Galaxy Tab S7 via logcat:
    // While the button is HELD, Samsung does not deliver the stroke as ACTION_DOWN/MOVE/UP at
    // all. It arrives as the legacy S Pen actions ACTION_PEN_DOWN (211), ACTION_PEN_MOVE (213,
    // ~120 Hz) and ACTION_PEN_UP (212), with buttonState = BUTTON_STYLUS_PRIMARY. Compose does
    // not recognise those actions, so the entire stroke was invisible to the canvas - which is
    // exactly the "holding the button just does nothing" symptom. A stroke made after the
    // button is released arrives as a normal DOWN/MOVE/UP.
    // The Activity sees every touch first, so the three legacy actions are translated to their
    // standard equivalents (with the button bits cleared) and the stroke is flagged as an
    // eraser stroke for the canvas. No timing heuristics: held = legacy actions, not held =
    // standard actions.
    private var buttonStrokeActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SiteDraftTheme {
                val backStack = rememberNavBackStack(SiteDraftNavKey.Main)
                NavDisplay(backStack = backStack) { key ->
                    NavEntry(key) {
                        when (key) {
                            SiteDraftNavKey.Main -> MainScreen()
                        }
                    }
                }
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val action = ev.actionMasked
        val translated = when (action) {
            SAMSUNG_ACTION_PEN_DOWN -> MotionEvent.ACTION_DOWN
            SAMSUNG_ACTION_PEN_MOVE -> MotionEvent.ACTION_MOVE
            SAMSUNG_ACTION_PEN_UP -> MotionEvent.ACTION_UP
            else -> null
        }
        // Standard DOWN that still carries the button bits (other firmware): same treatment.
        val standardButtonDown = action == MotionEvent.ACTION_DOWN && isStylus(ev) &&
            (ev.buttonState and STYLUS_BUTTONS) != 0

        if (translated == MotionEvent.ACTION_DOWN || standardButtonDown) {
            buttonStrokeActive = true
            StylusButton.held = true
            Log.d(TAG, "button stroke start (action=$action buttons=${ev.buttonState})")
        }

        val forward = if (buttonStrokeActive) rebuild(ev, translated ?: action) else ev
        val handled = try {
            super.dispatchTouchEvent(forward)
        } finally {
            if (forward !== ev) forward.recycle()
        }

        val endAction = translated ?: action
        if (buttonStrokeActive && (endAction == MotionEvent.ACTION_UP || endAction == MotionEvent.ACTION_CANCEL)) {
            Log.d(TAG, "button stroke end (action=$action)")
            buttonStrokeActive = false
            StylusButton.held = false
        }
        return handled
    }

    private fun isStylus(ev: MotionEvent): Boolean {
        val tool = ev.getToolType(0)
        return tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
    }

    // Same pointer data with a standard action and buttonState = 0. Historical samples are not
    // carried over; that only affects eraser strokes, which do not need them.
    private fun rebuild(ev: MotionEvent, newAction: Int): MotionEvent {
        val n = ev.pointerCount
        val props = Array(n) { i -> MotionEvent.PointerProperties().also { ev.getPointerProperties(i, it) } }
        val coords = Array(n) { i -> MotionEvent.PointerCoords().also { ev.getPointerCoords(i, it) } }
        return MotionEvent.obtain(
            ev.downTime, ev.eventTime, newAction, n, props, coords,
            ev.metaState, 0, ev.xPrecision, ev.yPrecision, ev.deviceId, ev.edgeFlags, ev.source, ev.flags
        )
    }

    private companion object {
        const val TAG = "SiteDraftPen"
        const val STYLUS_BUTTONS = MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY
        // Samsung's legacy S Pen actions (from the old Galaxy Note SPen SDK), still emitted by
        // One UI for a stroke made with the side button held.
        const val SAMSUNG_ACTION_PEN_DOWN = 211
        const val SAMSUNG_ACTION_PEN_UP = 212
        const val SAMSUNG_ACTION_PEN_MOVE = 213
    }
}
