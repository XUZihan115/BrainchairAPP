package com.yourteam.brainchair.model

import com.yourteam.brainchair.comm.*

/** Simulated lower controller, independent of Android so safety logic can be tested. */
class ChairEngine(private val output: (ByteArray) -> Unit) {
    var speed = 0; private set
    var direction = 0; private set
    var fault = 0; private set
    var limit = 30; private set
    var latched = false; private set
    var battery = 86; private set
    private var lastHeartbeat = Long.MIN_VALUE
    private var lastMotion = Long.MIN_VALUE
    private var lastReport = Long.MIN_VALUE
    private fun emit(cmd: Int, arg: Int) = output(Protocol.build(cmd, arg))
    fun stop() { speed = 0; direction = 0 }
    fun receive(f: Frame, now: Long) {
        when (f.cmd) {
            in 1..4 -> {
                if (latched || f.arg !in 0..100 || lastHeartbeat == Long.MIN_VALUE || now - lastHeartbeat >= 500) return
                direction = if (f.arg == 0) 0 else f.cmd
                speed = minOf(f.arg, limit)
                lastMotion = now
            }
            Protocol.STOP -> { if (f.arg != 0) return; stop() }
            Protocol.LIMIT -> { if (f.arg !in 0..100) return; limit = f.arg; speed = minOf(speed, limit) }
            Protocol.ESTOP -> { if (f.arg != 0) return; stop(); latched = true; fault = 1 }
            Protocol.HEARTBEAT -> { if (f.arg != 0) return; lastHeartbeat = now }
            Protocol.RESET -> {
                if (f.arg != 0 || speed != 0 || lastHeartbeat == Long.MIN_VALUE || now - lastHeartbeat >= 500) return
                latched = false; fault = 0; stop()
            }
            else -> return
        }
        emit(Protocol.ACK, f.cmd)
        report()
    }
    fun tick(now: Long) {
        // Heartbeats alone cannot keep a stale motion command alive.
        if (speed > 0 && (now - lastHeartbeat >= 500 || now - lastMotion >= 500)) {
            stop(); latched = true; fault = 2; report()
        }
        if (lastReport == Long.MIN_VALUE || now - lastReport >= 200) { lastReport = now; report() }
    }
    fun injectFault() { stop(); latched = true; fault = 3; report() }
    fun report() {
        emit(Protocol.BATTERY, battery); emit(Protocol.SPEED, speed)
        emit(Protocol.STATE, if (latched) 2 else if (speed > 0) 1 else 0)
        emit(Protocol.FAULT, fault)
    }
}
