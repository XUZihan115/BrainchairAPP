package com.yourteam.brainchair.control

/** Shared by the receiver, motion permit, UI snapshot and unlock guard. Wire format unchanged. */
object SafetyTiming {
    const val FEEDBACK_MS=1000L
    const val FEEDBACK_NS=FEEDBACK_MS*1_000_000
    const val UNLOCK_FRESH_NS=500_000_000L
    const val DEADLINE_POLL_MS=20L
    const val HEARTBEAT_MS=200L
    const val SEND_BUDGET_MS=100L
    const val SEND_BUDGET_NS=SEND_BUDGET_MS*1_000_000
    const val ESTOP_BURST_NS=30_000_000L
    const val ESTOP_RETRY_NS=200_000_000L
}
