package com.yourteam.brainchair.receiver

import android.app.*
import android.content.Intent
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.yourteam.brainchair.R
import com.yourteam.brainchair.signal.SignalEngine
import com.yourteam.brainchair.signal.RecognitionConfig
import com.yourteam.brainchair.signal.RecognitionTrace
import com.yourteam.brainchair.signal.TraceHealth
import android.os.Debug
import com.yourteam.brainchair.control.ControlRuntime
import com.yourteam.brainchair.control.UnityUdpLink
import com.yourteam.brainchair.control.UnitySum8Codec
import com.yourteam.brainchair.control.ControlPolicy
import com.yourteam.brainchair.control.ControlMode
import com.yourteam.brainchair.control.EmergencyPersistence
import com.yourteam.brainchair.control.LatestWorker
import com.yourteam.brainchair.control.SimulationSafetySettings

/** Foreground lifetime is explicit; leaving the debug screen does not stop receiving. */
class ReceiverService : Service() {
    private var receiver: UdpReceiver? = null
    private var multicast: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var trace: RecognitionTrace? = null
    private var unityLink:UnityUdpLink?=null
    companion object {
        @Volatile var session = StreamSession(); private set
        @Volatile private var activeReceiver: UdpReceiver? = null
        val listening: Boolean get() = activeReceiver?.running == true
        @Volatile var error: String? = null; private set
        @Volatile var recognizer: SignalEngine? = null; private set
        @Volatile var config = RecognitionConfig(); private set
        @Volatile var controls:ControlRuntime?=null;private set
        @Volatile var diagnosticsEnabled=true;private set
        @Volatile var logHealth:TraceHealth?=null;private set
        @Volatile private var unityCache:Pair<String,Int>?=null
        @Volatile private var debugCache:Pair<ControlPolicy,Boolean>?=null
        @Volatile private var simulationSafetyCache:SimulationSafetySettings?=null
        @Volatile private var durable:EmergencyPersistence?=null
        val safetyStorage get()=durable?.snapshot()
        @Synchronized private fun durable(context:Context):EmergencyPersistence {
            return durable ?: EmergencyPersistence(AndroidEmergencyStore(context),clock={SystemClock.elapsedRealtime()}).also{durable=it}
        }
        private val modes=LatestWorker<Pair<Context,ControlMode>>("control-mode-storage",{(context,mode)->
            check(context.getSharedPreferences("control-safety",MODE_PRIVATE).edit().putString("mode",mode.name).commit()){"模式保存失败"}
        },{e->error="模式保存失败；下次启动保持手动：${e.message}";Log.w("BrainChairSafety",error?:"模式保存失败")})
        private data class UnitySettingsWrite(val context:Context,val ip:String,val speed:Int)
        private data class DebugSettingsWrite(val context:Context,val grace:Long,val diagnostic:Boolean)
        private val unitySettingsWriter=LatestWorker<UnitySettingsWrite>("unity-settings-storage",{w->
            check(w.context.getSharedPreferences("unity-link",MODE_PRIVATE).edit().putString("ip",w.ip).putInt("speed",w.speed).commit()){"Unity配置保存失败"}
        },{e->error="Unity设置保存失败，重启前请核对地址：${e.message}";Log.w("BrainChairSafety",error?:"设置保存失败")})
        private val debugSettingsWriter=LatestWorker<DebugSettingsWrite>("debug-settings-storage",{w->
            check(w.context.getSharedPreferences("control-debug",MODE_PRIVATE).edit().putLong("rejectionGraceMs",w.grace).putBoolean("diagnostics",w.diagnostic).commit()){"调试配置保存失败"}
        },{e->error="调试设置保存失败：${e.message}";Log.w("BrainChairSafety",error?:"设置保存失败")})
        private val simulationSafetyWriter=LatestWorker<Pair<Context,SimulationSafetySettings>>("simulation-safety-storage",{(context,value)->
            val editor=context.getSharedPreferences("control-debug",MODE_PRIVATE).edit()
            value.write{key,enabled->editor.putBoolean(key,enabled)}
            check(editor.commit()){"联调安全设置保存失败"}
        },{e->error="联调安全设置保存失败，重启后请核对：${e.message}";Log.w("BrainChairSafety",error?:"设置保存失败")})
        fun simulationSafetySettings(context:Context):SimulationSafetySettings {
            simulationSafetyCache?.let{return it}
            val p=context.getSharedPreferences("control-debug",MODE_PRIVATE)
            return SimulationSafetySettings.read(p::getBoolean).also{simulationSafetyCache=it}
        }
        fun configureSimulationSafety(context:Context,value:SimulationSafetySettings){
            // A only changes subsequent startups; B changes the live permit, stopping first.
            controls?.configureFeedbackProtection(value.feedbackTimeoutStop)
            simulationSafetyCache=value
            simulationSafetyWriter.offer(context.applicationContext to value)
        }
        fun configure(value: RecognitionConfig) { config=value; recognizer?.configure(value) }
        fun controlSettings(context:Context):Pair<ControlPolicy,Boolean> {
            debugCache?.let{return it} // during active control, all UI reads are in memory.
            val p=context.getSharedPreferences("control-debug",MODE_PRIVATE)
            return (ControlPolicy(p.getLong("rejectionGraceMs",750).coerceIn(0,1000)) to p.getBoolean("diagnostics",true)).also{debugCache=it}
        }
        fun configureControl(context:Context,graceMs:Long,diagnostics:Boolean) {
            val policy=ControlPolicy(graceMs)
            controls?.configurePolicy(policy);diagnosticsEnabled=diagnostics
            debugCache=policy to diagnostics
            debugSettingsWriter.offer(DebugSettingsWrite(context.applicationContext,graceMs,diagnostics))
        }
        fun unitySettings(context:Context):Pair<String,Int> {
            unityCache?.let{return it}
            val p=context.getSharedPreferences("unity-link",MODE_PRIVATE)
            return ((p.getString("ip","")?:"") to p.getInt("speed",30)).also{unityCache=it}
        }
        fun configureUnity(context:Context,ip:String,speed:Int) {
            require(speed in 0..100);if(ip.isNotBlank())UnitySum8Codec.ipv4(ip)
            controls?.configureUnity(ip.trim(),speed)
            unityCache=ip.trim() to speed
            unitySettingsWriter.offer(UnitySettingsWrite(context.applicationContext,ip.trim(),speed))
        }
        fun emergencyStop(context:Context) {
            val active=controls
            if(active!=null)active.emergencyStop()
            else durable(context).request(true,bootstrap=true)
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        if (receiver != null) return START_NOT_STICKY
        // Android elapsed time includes device suspend: waking must not resume an expired action.
        error = null; session = StreamSession({SystemClock.elapsedRealtime()})
        try {
            val channel = "eeg-receiver"
            if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(channel, "脑电接收", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 0, Intent(this, ReceiverActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(this, 1, Intent(this, ReceiverService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
            @Suppress("DEPRECATION")
            val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channel) else Notification.Builder(this)
            val notification = builder.setContentTitle("BrainChair B 控制服务运行中")
                .setContentText("EEG 9000 · Unity 指令9001/状态9002 · 点击查看")
                .setSmallIcon(R.drawable.ic_chair).setContentIntent(open).setOngoing(true)
                .addAction(Notification.Action.Builder(null, "停止监听", stop).build()).build()
            if (Build.VERSION.SDK_INT >= 29) startForeground(20, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(20, notification)
            val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            multicast = wifi.createMulticastLock("brainchair:discovery").apply { setReferenceCounted(false); acquire() }
            @Suppress("DEPRECATION")
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "brainchair:eeg").apply { setReferenceCounted(false); acquire() }
            val settings=unitySettings(this)
            val controlSettings=controlSettings(this);diagnosticsEnabled=controlSettings.second
            val simulationSafety=simulationSafetySettings(this)
            unityLink=UnityUdpLink(settings.first,nanos={SystemClock.elapsedRealtimeNanos()})
            controls=ControlRuntime(session,config,clock={SystemClock.elapsedRealtime()},nanos={SystemClock.elapsedRealtimeNanos()},cpuClock={Debug.threadCpuTimeNanos()},
                unityLink=unityLink,policy=controlSettings.first,initialMode=ControlMode.MANUAL,
                persistMode={mode->modes.offer(applicationContext to mode)},durableEmergency=durable(this),
                startupEmergencyLock=simulationSafety.startupEstop,feedbackTimeoutStop=simulationSafety.feedbackTimeoutStop)
            controls!!.authority.setSpeed(settings.second)
            recognizer=controls!!.engine
            controls!!.start()
            receiver = UdpReceiver(session).also { it.start() }
            activeReceiver = receiver
            trace = RecognitionTrace(filesDir,session,recognizer!!,controls!!,diagnosticsEnabled={diagnosticsEnabled})
                .also { logHealth=it.health;it.start() }
            session.event("控制修复：手动自动接管/最长4秒；EEG拒识宽限${controlSettings.first.rejectionGraceMs}ms；200ms持续发送")
            session.event("2.5秒CCA / 250ms滑动；门限/投票不变；STREAM_STOP、换流、坏包及超时不使用宽限")
            session.event("Unity租期已由负责人确认改为1秒；状态反馈是遥测，不是逐指令ACK")
            session.event("仅供Unity模拟联调：启动自动急停=${simulationSafety.startupEstop}；9002失联强制停车=${simulationSafety.feedbackTimeoutStop}；已有急停/故障仍保留")
        } catch(e: Exception) {
            error = "监听失败：${e.javaClass.simpleName} ${e.message}"
            session.event(error!!); Log.e("BrainChairReceiver", error, e); stopSelf()
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        receiver?.close(); receiver = null; activeReceiver = null
        controls?.close();unityLink=null; trace?.close(); trace=null;controls=null;recognizer=null // dispatcher closes sockets off the service thread.
        multicast?.let { if (it.isHeld) it.release() }; multicast = null
        wifiLock?.let { if (it.isHeld) it.release() }; wifiLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
