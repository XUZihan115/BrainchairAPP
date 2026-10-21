package com.yourteam.brainchair.signal

/** Wire-independent read boundary for a future sender. Always read again before sending/repeating.
 * STOP here is ordinary recognition stop; it does not define emergency-stop or unlock semantics.
 */
fun interface ControlOutputSource {
    fun currentControl():ControlSnapshot
}
