package com.ticos.multiidle

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Atualização automática a partir do repositório no GitHub.
 *
 * - Procura `apk/versao.json` ao abrir o app e a cada poucas horas.
 * - Achou versão nova: baixa o APK em segundo plano e confere o SHA-256.
 * - Instala só quando o app está ABERTO NA TELA: instalar mata o processo, e fazer isso com a
 *   tela apagada (no ônibus) derrubaria as contas sem ninguém para abrir o app de novo.
 * - A 1ª atualização pede um toque em "Atualizar" (o Android exige); a partir daí, no Android 12+,
 *   ela instala sozinha, porque o próprio app passa a ser quem o instalou.
 */
class Atualizador(private val activity: Activity) {

    private val ctx = activity.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var apkPronto: File? = null
    private var versaoPronta = ""
    private var verificando = false
    private var instalando = false
    private var ultimoErro = ""

    private val periodico = object : Runnable {
        override fun run() {
            verificar(manual = false)
            main.postDelayed(this, INTERVALO_MS)
        }
    }

    fun iniciar() {
        main.removeCallbacks(periodico)
        main.post(periodico)
    }

    fun parar() = main.removeCallbacks(periodico)

    /** Chamado no onResume: se já tem versão baixada, instala agora que o usuário está vendo. */
    fun aoVoltarParaTela() {
        if (apkPronto != null) instalar()
    }

    fun verificar(manual: Boolean) {
        if (verificando) return
        verificando = true
        Thread {
            val resultado = try {
                val t = System.currentTimeMillis()
                val info = JSONObject(baixarTexto(listOf("$URL_VERSAO?t=$t", "$API_VERSAO&t=$t")))
                val codigo = info.getLong("versionCode")
                if (codigo <= versaoAtual()) {
                    "atual"
                } else {
                    val nome = info.optString("versionName", codigo.toString())
                    val arquivo = File(ctx.cacheDir, "atualizacao-$codigo.apk")
                    if (!arquivo.exists() || sha256(arquivo) != info.getString("sha256")) {
                        baixarArquivo(listOf(info.getString("apk"), API_APK), arquivo)
                        if (sha256(arquivo) != info.getString("sha256")) {
                            arquivo.delete()
                            throw IllegalStateException("arquivo corrompido")
                        }
                    }
                    main.post {
                        apkPronto = arquivo
                        versaoPronta = nome
                        if (activity.hasWindowFocus() || manual) instalar()
                    }
                    "nova"
                }
            } catch (e: Exception) {
                ultimoErro = e.message ?: e.javaClass.simpleName
                "erro"
            } finally {
                verificando = false
            }
            if (manual) main.post {
                when (resultado) {
                    "atual" -> Toast.makeText(ctx, "Você já está na versão mais nova", Toast.LENGTH_SHORT).show()
                    "erro" -> Toast.makeText(ctx, "Não deu para procurar atualização: $ultimoErro", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun instalar() {
        val apk = apkPronto ?: return
        if (instalando) return
        val pm = ctx.packageManager
        if (!pm.canRequestPackageInstalls()) {
            // Permissão "Instalar apps desconhecidos" para o MultiIdle — uma vez só.
            android.app.AlertDialog.Builder(activity)
                .setTitle("Atualização $versaoPronta disponível")
                .setMessage(
                    "Para o MultiIdle se atualizar sozinho, permita que ele instale apps:\n" +
                        "na próxima tela, ligue \"Permitir desta fonte\" e volte para o app."
                )
                .setPositiveButton("Abrir configuração") { _, _ ->
                    activity.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    )
                }
                .setNegativeButton("Depois", null)
                .show()
            return
        }
        instalando = true
        Toast.makeText(ctx, "Atualizando para a versão $versaoPronta… as contas recarregam já já", Toast.LENGTH_LONG).show()
        Thread {
            try {
                val installer = pm.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(ctx.packageName)
                if (Build.VERSION.SDK_INT >= 31) {
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                val id = installer.createSession(params)
                installer.openSession(id).use { sessao ->
                    sessao.openWrite("MultiIdle.apk", 0, apk.length()).use { saida ->
                        apk.inputStream().use { it.copyTo(saida) }
                        sessao.fsync(saida)
                    }
                    val retorno = PendingIntent.getBroadcast(
                        ctx, id,
                        Intent(ctx, ResultadoInstalacao::class.java),
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                    sessao.commit(retorno.intentSender)
                }
            } catch (e: Exception) {
                main.post {
                    instalando = false
                    Toast.makeText(ctx, "A atualização falhou: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun versaoAtual(): Long {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    private fun abrir(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            useCaches = false
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MultiIdle")
            // Endereço reserva (api.github.com): pede o arquivo cru, não o JSON da API.
            if (url.startsWith("https://api.github.com/")) setRequestProperty("Accept", "application/vnd.github.raw")
        }

    /** Tenta cada endereço na ordem; se todos falharem, junta os motivos na mensagem. */
    private fun <T> primeiroQueFunciona(urls: List<String>, acao: (String) -> T): T {
        val erros = mutableListOf<String>()
        for (u in urls) {
            try {
                return acao(u)
            } catch (e: Exception) {
                erros += "${URL(u).host}: ${e.javaClass.simpleName}${e.message?.let { " ($it)" } ?: ""}"
            }
        }
        throw IllegalStateException(erros.joinToString(" / "))
    }

    private fun baixarTexto(urls: List<String>) = primeiroQueFunciona(urls) { baixarTexto(it) }

    private fun baixarArquivo(urls: List<String>, destino: File) =
        primeiroQueFunciona(urls) { baixarArquivo(it, destino) }

    private fun baixarTexto(url: String): String {
        val c = abrir(url)
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    private fun baixarArquivo(url: String, destino: File) {
        val temp = File(destino.path + ".parcial")
        val c = abrir(url)
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            c.inputStream.use { entrada -> temp.outputStream().use { entrada.copyTo(it) } }
        } finally {
            c.disconnect()
        }
        // Só sobra um APK baixado por vez.
        ctx.cacheDir.listFiles { f -> f.name.startsWith("atualizacao-") }?.forEach { it.delete() }
        if (!temp.renameTo(destino)) throw IllegalStateException("não deu para salvar")
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { entrada ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = entrada.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val URL_VERSAO =
            "https://raw.githubusercontent.com/Ticos1/note12/ccr-226ce79c-dpfygw/apk/versao.json"
        private const val API_BASE = "https://api.github.com/repos/Ticos1/note12/contents/apk"
        private const val API_VERSAO = "$API_BASE/versao.json?ref=ccr-226ce79c-dpfygw"
        private const val API_APK = "$API_BASE/MultiIdle.apk?ref=ccr-226ce79c-dpfygw"
        private const val INTERVALO_MS = 2 * 60 * 60 * 1000L // a cada 2 h
        const val CANAL = "atualizacoes"

        fun criarCanal(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CANAL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CANAL, "Atualizações", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
        }
    }
}

/** Resposta do instalador do Android à atualização. */
class ResultadoInstalacao : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // 1ª vez (ou Android antigo): o sistema mostra a tela "Atualizar este app?".
                @Suppress("DEPRECATION")
                val confirmar = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirmar?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let {
                    try { context.startActivity(it) } catch (_: Exception) {}
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "erro desconhecido"
                Toast.makeText(context, "A atualização falhou: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}

/** Depois de atualizar, o Android fecha o app: avisa para abrir de novo. */
class AppAtualizado : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Atualizador.criarCanal(context)
        val versao = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (_: Exception) { "" }
        val abrir = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(context, Atualizador.CANAL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("MultiIdle atualizado ($versao)")
            .setContentText("Toque para abrir e voltar a rodar as contas")
            .setContentIntent(abrir)
            .setAutoCancel(true)
            .build()
        try {
            context.getSystemService(NotificationManager::class.java).notify(2, n)
        } catch (_: SecurityException) {
        }
    }
}
