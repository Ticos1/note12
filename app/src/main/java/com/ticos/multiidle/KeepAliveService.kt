package com.ticos.multiidle

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Serviço em primeiro plano: segura o processo vivo (e a CPU/Wi-Fi acordados) enquanto o app
 * está em segundo plano ou com a tela apagada, para as contas não caírem.
 *
 * Ele não roda nada sozinho — quem joga são as WebViews da MainActivity. O serviço só impede
 * o Android de matar o processo e de pôr a CPU para dormir.
 */
class KeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val contas = intent?.getIntExtra(EXTRA_CONTAS, 0) ?: 0
        val notif = criarNotificacao(contas)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID_NOTIF, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID_NOTIF, notif)
        }
        adquirirLocks()
        return START_STICKY
    }

    private fun adquirirLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MultiIdle::contas").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val modo = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(modo, "MultiIdle::wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun criarNotificacao(contas: Int): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CANAL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CANAL, "Contas rodando", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Aviso fixo enquanto as contas estão abertas"
                    setShowBadge(false)
                }
            )
        }
        val abrir = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val sair = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_SAIR, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val texto = if (contas == 1) "1 conta rodando" else "$contas contas rodando"
        return Notification.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("MultiIdle")
            .setContentText(texto)
            .setContentIntent(abrir)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Encerrar tudo", sair).build())
            .build()
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock = null
        super.onDestroy()
    }

    companion object {
        private const val CANAL = "contas"
        private const val ID_NOTIF = 1
        const val EXTRA_CONTAS = "contas"

        fun iniciar(ctx: Context, contas: Int) {
            val i = Intent(ctx, KeepAliveService::class.java).putExtra(EXTRA_CONTAS, contas)
            ctx.startForegroundService(i)
        }

        fun parar(ctx: Context) {
            ctx.stopService(Intent(ctx, KeepAliveService::class.java))
        }
    }
}
