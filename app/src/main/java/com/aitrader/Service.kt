package com.aitrader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

class AgentApp : Application() {
    override fun onCreate() { super.onCreate(); Cfg.init(this); Core.init(this) }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        try { ContextCompat.startForegroundService(c, Intent(c, AgentService::class.java)) } catch (e: Exception) { }
    }
}

class AgentService : Service() {
    private var job: Job? = null
    private var wl: PowerManager.WakeLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    private fun notif(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("agent", "AI Trader", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, "agent").setContentTitle("ایجنت ترید فعال است").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_info_details).setOngoing(true).build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = notif("در حال اجرا")
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, n)
        if (job?.isActive == true) return START_STICKY
        wl = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aitrader:loop").apply { acquire() }
        Core.running = true; Core.log("▶️ ایجنت شروع شد")
        job = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try { Core.bootstrapIfNeeded() } catch (e: Exception) { Core.log("خطای آموزش اولیه: ${e.message}") }
            while (isActive) {
                try {
                    Core.manageOnce()
                    val now = System.currentTimeMillis(); val tfm = Data.tfMs(Cfg.s("scan.tf")); val boundary = (now / tfm) * tfm
                    if (boundary + 8000 <= now && Core.lastScanBoundary < boundary) { Core.lastScanBoundary = boundary; Core.scanOnce() }
                    Core.periodic()
                    getSystemService(NotificationManager::class.java).notify(1, notif("موجودی ${"%.2f".format(Core.balance)}$ | باز: ${Core.trades.count { it.status == "OPEN" && it.mode == "virtual" }}"))
                } catch (e: CancellationException) { throw e } catch (e: Exception) { Core.log("خطا: ${e.message}") }
                delay(Cfg.d("manage.sec").toLong().coerceAtLeast(5) * 1000)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel(); Core.running = false; Core.log("⏹ ایجنت متوقف شد")
        try { wl?.release() } catch (e: Exception) { }
        super.onDestroy()
    }
}
