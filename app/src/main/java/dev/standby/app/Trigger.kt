package dev.standby.app

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs

/** Gravity on the phone's x/y axes (m/s²): true when it stands on its long side, as on a charging stand. Flat on a pad is not landscape. */
fun isLandscape(x: Float, y: Float) = abs(x) > 5f && abs(x) > abs(y) * 1.5f

/** Takes a new value only after it has held for [holdMs], so a hand bumping the phone doesn't flicker StandBy. */
class Debounce(private val holdMs: Long) {
    var stable = false
        private set
    private var last = false
    private var since = 0L

    fun update(value: Boolean, now: Long): Boolean {
        if (value != last) { last = value; since = now }
        if (now - since >= holdMs) stable = value
        return stable
    }

    fun reset() { stable = false; last = false; since = 0L }
}

/** Service <-> StandBy screen, same process. */
object Bus {
    val exit = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** The phone's notifications, newest first, and each new one as it arrives for the pop-up. */
    val notifs = MutableStateFlow(emptyList<Notif>())
    val incoming = MutableSharedFlow<Notif>(extraBufferCapacity = 8)
    @Volatile var shown = false
}
