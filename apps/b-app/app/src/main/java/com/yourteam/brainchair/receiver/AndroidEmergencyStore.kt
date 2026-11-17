package com.yourteam.brainchair.receiver

import android.content.Context
import com.yourteam.brainchair.control.*

/** All methods are called only by emergency-storage, including the first preferences load. */
class AndroidEmergencyStore(context:Context):EmergencyStore {
    private val app=context.applicationContext
    override fun read():StoredEmergency? {
        val p=app.getSharedPreferences("control-safety",Context.MODE_PRIVATE)
        val schema=p.contains("safetySchema");val lock=p.contains("emergencyLocked")
        if(!schema && !lock)return null
        check(schema && lock){"安全记录不完整，不能按首次安装解锁"}
        return StoredEmergency(p.getBoolean("emergencyLocked",true),p.getInt("safetySchema",-1))
    }
    override fun write(locked:Boolean):Boolean = app.getSharedPreferences("control-safety",Context.MODE_PRIVATE)
        .edit().putInt("safetySchema",1).putBoolean("emergencyLocked",locked).commit()
}
