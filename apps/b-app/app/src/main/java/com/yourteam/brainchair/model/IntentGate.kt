package com.yourteam.brainchair.model

/** Accepts decoded intentions only. No EEG signal processing. */
class IntentGate {
    private var lastSequence = -1L
    fun accept(direction: Int, confidence: Double, ageMs: Long, sequence: Long, armed: Boolean): Boolean {
        if (!armed || direction !in 1..5 || !confidence.isFinite() || confidence < 0.8 || confidence > 1.0 || ageMs !in 0..300 || sequence <= lastSequence) return false
        lastSequence = sequence
        return true
    }
}
