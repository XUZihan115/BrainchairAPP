package com.yourteam.brainchair.receiver

import android.app.*
import android.content.Intent
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.yourteam.brainchair.R
import com.yourteam.brainchair.signal.SignalEngine
import com.yourteam.brainchair.signal.RecognitionConfig
import com.yourteam.brainchair.signal.RecognitionTrace
import android.os.Debug
import com.yourteam.brainchair.control.ControlRuntime
import com.yourteam.brainchair.control.UnityUdpLink
import com.yourteam.brainchair.control.UnitySum8Codec

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
        fun configure(value: RecognitionConfig) { config=value; recognizer?.configure(value) }
        fun unitySettings(context:Context):Pair<String,Int> {
            val p=context.getSharedPreferences("unity-link",MODE_PRIVATE)
            return (p.getString("ip","")?:"") to p.getInt("speed",30)
        }
        fun configureUnity(context:Context,ip:String,speed:Int) {
            require(speed in 0..100);if(ip.isNotBlank())UnitySum8Codec.ipv4(ip)
            controls?.configureUnity(ip.trim(),speed)
            check(context.getSharedPreferences("unity-link",MODE_PRIVATE).edit().putString("ip",ip.trim()).putInt("speed",speed).commit()){"配置保存失败"}
        }
        fun emergencyStop(context:Context) {
            val active=controls
            if(active!=null)active.emergencyStop()
            else context.getSharedPreferences("control-safety",MODE_PRIVATE).edit().putBoolean("emergencyLocked",true).commit()
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        if (receiver != null) return START_NOT_STICKY
        error = null; session = StreamSession()
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
            val preferences=getSharedPreferences("control-safety",MODE_PRIVATE)
            val settings=unitySettings(this)
            unityLink=UnityUdpLink(settings.first)
            controls=ControlRuntime(session,config,cpuClock={Debug.threadCpuTimeNanos()},
                emergencyInitiallyLocked=preferences.getBoolean("emergencyLocked",false),
                persistEmergency={locked->preferences.edit().putBoolean("emergencyLocked",locked).commit()},unityLink=unityLink)
            controls!!.authority.setSpeed(settings.second)
            recognizer=controls!!.engine
            controls!!.start()
            receiver = UdpReceiver(session).also { it.start() }
            activeReceiver = receiver
            trace = RecognitionTrace(filesDir,session,recognizer!!,controls!!).also { it.start() }
            session.event("Stage 3：2.5秒CCA / 250ms滑动；Unity Sum8 UDP 9001发送 / 9002状态；200ms持续控制")
            session.event("Unity租期已由负责人确认改为1秒；状态反馈是遥测，不是逐指令ACK")
        } catch(e: Exception) {
            error = "监听失败：${e.javaClass.simpleName} ${e.message}"
            session.event(error!!); Log.e("BrainChairReceiver", error, e); stopSelf()
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        receiver?.close(); receiver = null; activeReceiver = null
        controls?.close();unityLink?.close();unityLink=null; trace?.close(); trace=null;controls=null;recognizer=null
        multicast?.let { if (it.isHeld) it.release() }; multicast = null
        wifiLock?.let { if (it.isHeld) it.release() }; wifiLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
