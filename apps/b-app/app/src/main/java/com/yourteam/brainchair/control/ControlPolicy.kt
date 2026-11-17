package com.yourteam.brainchair.control

/** Output policy only; these settings never change the CCA classifier or its gates. */
data class ControlPolicy(val rejectionGraceMs:Long=750) {
    init {require(rejectionGraceMs in 0..1000){"拒识宽限应为0–1000ms"}}
    companion object {
        const val MANUAL_LIMIT_MS=4000L
        const val FRESHNESS_MS=1000L
    }
}
