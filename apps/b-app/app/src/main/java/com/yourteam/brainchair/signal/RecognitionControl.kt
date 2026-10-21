package com.yourteam.brainchair.signal

data class ControlSnapshot(val output:Direction=Direction.STOP,val reason:String="等待可信判决",
                           val changedMs:Long=0,val expiresMs:Long?=null,val revision:Long=0)

/** Local final output, NOT a hardware transport. Engine calls while holding its decision lock. */
class RecognitionControl {
    @Volatile var state=ControlSnapshot(); private set
    fun stop(reason:String,now:Long) {
        val changed=state.output!=Direction.STOP || state.reason!=reason
        state=ControlSnapshot(Direction.STOP,reason,if(changed)now else state.changedMs,null,state.revision+if(changed)1 else 0)
    }
    fun accept(raw:Direction,stable:Direction,inputMs:Long,now:Long) {
        when {
            now-inputMs>=1000 || now<inputMs -> stop("分析输入已过期",now)
            raw==Direction.STOP || stable==Direction.STOP || raw!=stable -> stop("无稳定可信方向",now)
            else -> state=ControlSnapshot(stable,"当前窗口门控与投票通过",if(state.output==stable)state.changedMs else now,inputMs+1000,state.revision+1)
        }
    }
    fun expire(now:Long) { if(state.expiresMs?.let{now>=it}==true) stop("控制输出超过有效期",now) }
}
