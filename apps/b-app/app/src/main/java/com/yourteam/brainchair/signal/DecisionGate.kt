package com.yourteam.brainchair.signal

/** Defaults are synthetic-debug starting values, NOT calibrated real EEG thresholds. */
data class RecognitionConfig(val absolute: Double=0.65, val difference: Double=0.15,
                             val votes: Int=3, val debounceMs: Long=300) {
    init { require(absolute.isFinite() && difference.isFinite() && absolute in 0.0..1.0 && difference in 0.0..1.0)
        require(votes in 1..10 && debounceMs in 0..2000) }
}
data class GateResult(val raw: Direction, val stable: Direction, val reason: String)

class DecisionGate(val config: RecognitionConfig) {
    private var candidate=Direction.STOP; private var count=0; private var since=0L
    private var stable=Direction.STOP
    fun reset() { candidate=Direction.STOP; count=0; since=0; stable=Direction.STOP }
    fun evaluate(scores: DoubleArray, now: Long): GateResult {
        if(scores.size!=4 || scores.any { !it.isFinite() || it !in 0.0..1.0 }) {
            reset(); return GateResult(Direction.STOP,Direction.STOP,"CCA数值无效")
        }
        val order=scores.indices.sortedByDescending { scores[it] }
        val top=scores[order[0]]; val gap=top-scores[order[1]]
        if(top<=config.absolute || gap<=config.difference) {
            reset(); return GateResult(Direction.STOP,Direction.STOP,if(top<=config.absolute) "未过绝对阈值" else "候选差值不足")
        }
        val raw=Direction.values()[order[0]]
        if(raw!=candidate){ candidate=raw; count=1; since=now; stable=Direction.STOP } else count++
        if(count>=config.votes && now-since>=config.debounceMs) stable=raw
        return GateResult(raw,stable,if(stable==Direction.STOP) "等待连续投票/切换去抖" else "门控通过（阈值未校准）")
    }
}
