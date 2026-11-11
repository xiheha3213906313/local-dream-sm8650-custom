package io.github.xororz.localdream.ui.design

import androidx.compose.foundation.gestures.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*

/** Tap or hold-and-scrub without turning the end of a drag into a second click. */
@Composable
internal fun Modifier.scrubGesture(key: Any, enabled: Boolean = true, onTap: () -> Unit, onStart: () -> Unit, onMove: (Float) -> Unit, onEnd: () -> Unit): Modifier {
    val tap by rememberUpdatedState(onTap)
    val start by rememberUpdatedState(onStart)
    val move by rememberUpdatedState(onMove)
    val end by rememberUpdatedState(onEnd)
    return if (!enabled) {
        this
    } else {
        pointerInput(key) {
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                val released = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { true to waitForUpOrCancellation() }
                if (released != null) {
                    val up = released.second
                    if (up != null && (up.position - down.position).getDistance() <= viewConfiguration.touchSlop) {
                        up.consume()
                        tap()
                    }
                } else {
                    start()
                    var distance = 0f
                    try {
                        horizontalDrag(down.id) { change ->
                            distance += change.positionChange().x
                            move(distance)
                            change.consume()
                        }
                    } finally {
                        end()
                    }
                }
            }
        }
    }
}
