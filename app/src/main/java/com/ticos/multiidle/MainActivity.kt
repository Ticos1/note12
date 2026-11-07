package com.ticos.multiidle

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.os.PowerManager
import android.provider.Settings
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import androidx.browser.customtabs.CustomTabsIntent
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.JavascriptInterface
import android.widget.CheckBox
import android.widget.Switch
import org.json.JSONObject
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlin.math.min
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.UUID

/**
 * Tela única do app: até 4 contas do pokeidle.io abertas AO MESMO TEMPO, cada uma num perfil
 * separado do WebView (cookies, localStorage e login próprios — como abas anônimas
 * diferentes, mas que guardam o login).
 */
class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var painel: Painel
    private lateinit var coluna: LinearLayout
    private lateinit var escuro: TextView
    private lateinit var btnGrade: TextView
    private lateinit var btnSom: TextView
    private val abas = arrayOfNulls<TextView>(Painel.MAX)

    /** O WebView do aparelho suporta perfis separados? Sem isso, todas as contas dividiriam o login. */
    private var multiPerfil = false
    private var ultimoToqueEscuro = 0L
    private lateinit var atualizador: Atualizador

    private val dp get() = resources.displayMetrics.density

    // ------------------------------------------------------------------ ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        multiPerfil = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

        montarTela()

        val n = if (multiPerfil) prefs.getInt(P_QTD, 3).coerceIn(1, Painel.MAX) else 1
        painel.miniatura = prefs.getBoolean(P_MINIATURA, true)
        painel.quantidade = n
        for (i in 0 until n) criarConta(i)
        painel.foco = prefs.getInt(P_FOCO, -1).let { if (it >= n || n == 1) (if (n == 1) 0 else -1) else it }

        aplicarTelaAcesa()
        atualizarBarra()
        vigiarRede()
        pedirPermissaoNotificacao()
        pedirSemOtimizacaoDeBateria(forcar = false)
        atualizador = Atualizador(this)
        atualizador.iniciar()
        atualizarServico()

        if (!multiPerfil) avisarSemPerfis()
        if (intent?.getBooleanExtra(EXTRA_SAIR, false) == true) encerrar()
        else intent?.let { receberVoltaDoLogin(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SAIR, false)) encerrar()
        else receberVoltaDoLogin(intent)
    }

    // De propósito NÃO chamamos webView.onPause()/pauseTimers() em onPause: o jogo tem que
    // continuar rodando com o app minimizado.

    override fun onResume() {
        super.onResume()
        if (::atualizador.isInitialized) atualizador.aoVoltarParaTela()
    }

    override fun onDestroy() {
        if (::atualizador.isInitialized) atualizador.parar()
        redeCallback?.let { cb ->
            try { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } catch (_: Exception) {}
        }
        for (i in 0 until Painel.MAX) destruirConta(i)
        KeepAliveService.parar(this)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            escuro.visibility == View.VISIBLE -> sairDoEscuro()
            painel.foco >= 0 && painel.quantidade > 1 -> focar(-1)
            // Voltar não fecha o app (fecharia as contas): só manda para o segundo plano.
            else -> moveTaskToBack(true)
        }
    }

    // ------------------------------------------------------------------ montagem da tela

    private fun montarTela() {
        val raiz = FrameLayout(this).apply { setBackgroundColor(COR_FUNDO) }
        coluna = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val barra = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(COR_BARRA)
            setPadding((4 * dp).toInt(), 0, 0, 0)
        }
        btnGrade = botaoBarra("▦ Todas").apply { setOnClickListener { focar(-1) } }
        barra.addView(btnGrade, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (34 * dp).toInt()))
        for (i in 0 until Painel.MAX) {
            val aba = botaoBarra(nomeConta(i)).apply {
                setOnClickListener { focar(i) }
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            abas[i] = aba
            barra.addView(aba, LinearLayout.LayoutParams(0, (34 * dp).toInt(), 1f))
        }
        btnSom = botaoBarra("").apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setOnClickListener {
                val novo = !prefs.getBoolean(P_MUDO, false)
                prefs.edit().putBoolean(P_MUDO, novo).apply()
                pintarBotaoSom()
                Toast.makeText(this@MainActivity, if (novo) "Som do jogo desligado" else "Som do jogo ligado", Toast.LENGTH_SHORT).show()
            }
        }
        pintarBotaoSom()
        barra.addView(btnSom, LinearLayout.LayoutParams((40 * dp).toInt(), (44 * dp).toInt()))
        val btnMenu = botaoBarra("⋮").apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setOnClickListener { abrirMenu(it) }
        }
        barra.addView(btnMenu, LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt()))

        painel = Painel(this)
        for (i in 0 until Painel.MAX) painel.definirZoom(i, prefs.getFloat("zoom$i", 1f))
        painel.aoMudarZoom = { i, z ->
            prefs.edit().putFloat("zoom$i", z).apply()
            Toast.makeText(this, "${nomeConta(i)}: zoom ${(z * 100).toInt()}%", Toast.LENGTH_SHORT).show()
        }
        coluna.addView(barra, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (44 * dp).toInt()))
        coluna.addView(painel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        raiz.addView(coluna, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Modo economia: tela preta com brilho mínimo, as contas seguem rodando por baixo.
        escuro = TextView(this).apply {
            setBackgroundColor(Color.BLACK)
            setTextColor(0xFF303030.toInt())
            gravity = Gravity.CENTER
            text = "Contas rodando\n\nToque duas vezes para voltar"
            visibility = View.GONE
            isClickable = true
            setOnClickListener {
                val agora = System.currentTimeMillis()
                if (agora - ultimoToqueEscuro < 450) sairDoEscuro()
                ultimoToqueEscuro = agora
            }
        }
        raiz.addView(escuro, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Android 15 desenha o app por baixo das barras do sistema: afasta o conteúdo delas
        // (e do teclado, para dar para digitar no chat/login).
        raiz.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
                )
                coluna.setPadding(b.left, b.top, b.right, b.bottom)
                WindowInsets.CONSUMED
            } else {
                @Suppress("DEPRECATION")
                coluna.setPadding(
                    insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom,
                )
                @Suppress("DEPRECATION")
                insets.consumeSystemWindowInsets()
            }
        }
        setContentView(raiz)
    }

    private fun botaoBarra(texto: String) = TextView(this).apply {
        text = texto
        gravity = Gravity.CENTER
        setTextColor(COR_TEXTO)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding((10 * dp).toInt(), 0, (10 * dp).toInt(), 0)
        isClickable = true
    }

    private fun fundoAba(selecionada: Boolean) = GradientDrawable().apply {
        cornerRadius = 8 * dp
        setColor(if (selecionada) COR_DESTAQUE else Color.TRANSPARENT)
    }

    private fun pintarBotaoSom() {
        val mudo = prefs.getBoolean(P_MUDO, false)
        btnSom.text = if (mudo) "🔇" else "🔊"
        btnSom.contentDescription = if (mudo) "Ligar o som do jogo" else "Silenciar o jogo"
    }

    private fun atualizarBarra() {
        val n = painel.quantidade
        btnGrade.visibility = if (n > 1) View.VISIBLE else View.GONE
        btnGrade.background = fundoAba(painel.foco < 0)
        btnGrade.setTextColor(if (painel.foco < 0) COR_FUNDO else COR_TEXTO)
        for (i in 0 until Painel.MAX) {
            val aba = abas[i] ?: continue
            aba.visibility = if (i < n) View.VISIBLE else View.GONE
            aba.text = nomeConta(i)
            val sel = painel.foco == i
            aba.background = fundoAba(sel)
            aba.setTextColor(if (sel) COR_FUNDO else COR_TEXTO)
            aba.setTypeface(null, if (sel) Typeface.BOLD else Typeface.NORMAL)
            (painel.rotulos[i] as? TextView)?.text = "  ${nomeConta(i)}   ⤢"
        }
    }

    private fun focar(i: Int) {
        if (i >= painel.quantidade) return
        painel.foco = if (painel.quantidade == 1) 0 else i
        prefs.edit().putInt(P_FOCO, painel.foco).apply()
        if (i >= 0) painel.webs[i]?.requestFocus()
        atualizarBarra()
    }

    // ------------------------------------------------------------------ contas (WebViews)

    private fun nomePerfil(i: Int) = "conta${i + 1}"
    private fun nomeConta(i: Int) = prefs.getString("nome$i", null) ?: "Conta ${i + 1}"

    @SuppressLint("SetJavaScriptEnabled")
    private fun novaWebView(i: Int): WebView {
        val web = ContaWebView(this).apply { manterAtiva = prefs.getBoolean(P_TELA_APAGADA, true) }
        // O perfil tem que ser definido ANTES de qualquer outro uso da WebView.
        if (multiPerfil) {
            ProfileStore.getInstance().getOrCreateProfile(nomePerfil(i))
            WebViewCompat.setProfile(web, nomePerfil(i))
        }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            offscreenPreRaster = true
            mediaPlaybackRequiresUserGesture = true
        }
        val cookies = if (multiPerfil) {
            ProfileStore.getInstance().getProfile(nomePerfil(i))?.cookieManager ?: CookieManager.getInstance()
        } else CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        // O captcha do login (Cloudflare Turnstile) roda num iframe de outro domínio.
        cookies.setAcceptThirdPartyCookies(web, true)
        // Mantém o processo do navegador desta conta com prioridade alta mesmo fora da tela.
        web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        web.setBackgroundColor(COR_FUNDO)
        return web
    }

    private fun criarConta(i: Int) {
        val web = novaWebView(i)
        compartilharIdDispositivo(web)
        ligarAutoCompra(web, i)

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (ehIdaAoGoogle(url)) { loginNoNavegador(i, url); return true }
                return if (url.scheme == "http" || url.scheme == "https") false
                else { abrirFora(url); true }
            }

            // Sem internet / DNS falhando: mostra um aviso e tenta de novo sozinho.
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame || painel.webs[i] !== view) return
                urlComErro[i] = request.url.toString()
                view.post {
                    view.loadDataWithBaseURL(null, paginaSemConexao(error.description), "text/html", "utf-8", null)
                }
                agendarNovaTentativa(i, view)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (urlComErro[i] == null && url?.startsWith("https://") == true) tentativas[i] = 0
            }

            // Se o processo do navegador de uma conta cair (falta de memória), recria só ela
            // em vez de deixar o app inteiro fechar.
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (painel.webs[i] === view) {
                    destruirConta(i)
                    criarConta(i)
                    painel.requestLayout()
                }
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            // Links que o jogo abre em "nova aba" (Discord, termos...) vão para o navegador do celular.
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                val temp = novaWebView(i)
                temp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                        abrirFora(request.url)
                        v.post { v.destroy() }
                        return true
                    }
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = temp
                resultMsg.sendToTarget()
                return true
            }
        }

        val rotulo = TextView(this).apply {
            text = "  ${nomeConta(i)}   ⤢"
            setTextColor(COR_TEXTO)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(COR_BARRA)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setOnClickListener { focar(i) }
        }

        painel.webs[i] = web
        painel.rotulos[i] = rotulo
        painel.addView(web)
        painel.addView(rotulo)
        web.loadUrl(URL_JOGO)
    }

    // ------------------------------------------------------------------ queda de conexão

    /** A página que falhou em cada conta (null = carregou bem). */
    private val urlComErro = arrayOfNulls<String>(Painel.MAX)
    private val tentativas = IntArray(Painel.MAX)
    private var redeCallback: ConnectivityManager.NetworkCallback? = null

    private fun agendarNovaTentativa(i: Int, view: WebView) {
        // 3 s, 6 s, 12 s, 24 s... até 30 s entre tentativas.
        val espera = min(30_000L, 3_000L shl min(tentativas[i], 4))
        view.postDelayed({ if (painel.webs[i] === view) tentarDeNovo(i) }, espera)
    }

    private fun tentarDeNovo(i: Int) {
        val url = urlComErro[i] ?: return
        val web = painel.webs[i] as? WebView ?: return
        urlComErro[i] = null
        tentativas[i]++
        web.loadUrl(url)
    }

    /** Quando a internet volta, recarrega na hora as contas que tinham caído. */
    private fun vigiarRede() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    for (i in 0 until Painel.MAX) if (urlComErro[i] != null) { tentativas[i] = 0; tentarDeNovo(i) }
                }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            redeCallback = cb
        } catch (_: Exception) {
        }
    }

    /**
     * Testa a rede DESTE app (não a do celular): o Android e apps de firewall/VPN podem cortar a
     * internet só de um app. O resultado sai numa janela para o usuário mandar print.
     */
    private fun mostrarDiagnostico() {
        val dlg = AlertDialog.Builder(this)
            .setTitle("Diagnóstico de conexão")
            .setMessage("Testando…")
            .setPositiveButton("Fechar", null)
            .show()
        val links = linksLiberados()
        val navegador = navegadorQueDevolve()
        Thread {
            val r = StringBuilder()
            val cm = getSystemService(ConnectivityManager::class.java)
            try {
                val rede = cm.activeNetwork
                if (rede == null) {
                    r.append("❌ O app não vê nenhuma rede ativa\n")
                } else {
                    val cap = cm.getNetworkCapabilities(rede)
                    val tipos = buildList {
                        if (cap?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("Wi-Fi")
                        if (cap?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("dados móveis")
                        if (cap?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("VPN")
                    }
                    r.append("Rede: ").append(tipos.joinToString(" + ").ifEmpty { "desconhecida" }).append('\n')
                    r.append(if (cap?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
                        "✅ Rede com internet\n" else "⚠️ Rede sem internet confirmada\n")
                    if (cap?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)
                        r.append("⚠️ Há uma VPN / bloqueador ativo — ele pode estar bloqueando o MultiIdle\n")
                    val lp = cm.getLinkProperties(rede)
                    if (Build.VERSION.SDK_INT >= 28 && lp != null) {
                        r.append("DNS privado: ").append(
                            when {
                                lp.privateDnsServerName != null -> lp.privateDnsServerName
                                lp.isPrivateDnsActive -> "automático (ativo)"
                                else -> "desligado/automático"
                            }
                        ).append('\n')
                    }
                }
                when (cm.restrictBackgroundStatus) {
                    ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED ->
                        r.append("⚠️ Economia de dados LIGADA e restringindo o MultiIdle\n")
                    ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED ->
                        r.append("Economia de dados ligada, MultiIdle liberado\n")
                    else -> {}
                }
            } catch (e: Exception) {
                r.append("Erro lendo a rede: ").append(e.javaClass.simpleName).append('\n')
            }
            for (host in listOf(HOST_JOGO, "google.com", "raw.githubusercontent.com", "api.github.com")) {
                r.append(
                    try {
                        val ip = java.net.InetAddress.getAllByName(host).first().hostAddress
                        "✅ Encontrou $host ($ip)\n"
                    } catch (e: Exception) {
                        "❌ Não encontrou $host\n"
                    }
                )
            }
            r.append(
                try {
                    val c = java.net.URL("https://$HOST_JOGO/").openConnection() as java.net.HttpURLConnection
                    c.connectTimeout = 8000; c.readTimeout = 8000
                    val code = c.responseCode
                    c.disconnect()
                    "✅ Abriu https://$HOST_JOGO (código $code)\n"
                } catch (e: Exception) {
                    "❌ Não abriu https://$HOST_JOGO: ${e.javaClass.simpleName}\n"
                }
            )
            r.append(if (links) "✅ Links do pokeidle.io liberados (login Google)\n" else "❌ Links do pokeidle.io NÃO liberados (login Google não volta)\n")
            r.append("Navegador do login: ").append(navegador ?: "padrão do celular").append('\n')
            r.append("\nWebView: ").append(WebView.getCurrentWebViewPackage()?.versionName ?: "?")
            r.append("\nAndroid ").append(Build.VERSION.RELEASE).append(" · ").append(Build.MANUFACTURER)
            runOnUiThread { if (dlg.isShowing) dlg.setMessage(r.toString()) }
        }.start()
    }

    private fun paginaSemConexao(motivo: CharSequence?) = """
        <html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
        <body style="background:#121A24;color:#E8EDF3;font-family:sans-serif;display:flex;
          align-items:center;justify-content:center;height:90vh;text-align:center;margin:0">
        <div><div style="font-size:42px">📡</div>
        <h2 style="margin:12px 0">Sem conexão com o pokeidle</h2>
        <p style="color:#9AA8B8">Tentando de novo sozinho…<br>
        Confira se o celular está com internet.</p>
        <p style="color:#F2C14E">Se não resolver: menu ⋮ → Diagnóstico de conexão</p>
        <p style="color:#5B6B80;font-size:12px">${TextUtils.htmlEncode(motivo?.toString() ?: "")}</p></div></body></html>
    """.trimIndent()

    private fun destruirConta(i: Int) {
        (painel.webs[i] as? WebView)?.let {
            painel.removeView(it)
            it.stopLoading()
            it.destroy()
        }
        painel.rotulos[i]?.let { painel.removeView(it) }
        painel.webs[i] = null
        painel.rotulos[i] = null
        urlComErro[i] = null
        tentativas[i] = 0
    }

    /**
     * O pokeidle guarda um "id deste navegador" (`id-dispositivo`) para saber quantas contas
     * entram do mesmo aparelho. Como cada conta aqui tem armazenamento separado, cada uma
     * criaria um id diferente — e o jogo acharia que são celulares diferentes. Para ficar
     * honesto com o limite de contas por pessoa, todas as contas usam o MESMO id: o do app.
     */
    // ------------------------------------------------------------------ compra automática
    //
    // O script (assets/autocompra.js) roda na página de cada conta e compra Ultra Ball / Ultra
    // Potion quando ficam abaixo do mínimo. A configuração é uma só para todas as contas e é
    // lida pelo script a cada 5 s, então mudar no menu vale na hora, sem recarregar.

    /** Núcleo (observa o WebSocket do jogo) + compra automática + sniper, numa ordem garantida. */
    private val scriptAutoCompra by lazy {
        listOf("nucleo.js", "autocompra.js", "sniper.js", "altura.js")
            .joinToString("\n;\n") { nome -> assets.open(nome).bufferedReader().use { it.readText() } }
    }
    private val sniper by lazy { Sniper(this, prefs, ::nomeConta) { painel.quantidade } }

    private val scriptSilenciar by lazy { assets.open("silenciar.js").bufferedReader().use { it.readText() } }

    private fun ligarAutoCompra(web: WebView, i: Int) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        web.addJavascriptInterface(PonteAutoCompra(i), "MultiIdleApp")
        WebViewCompat.addDocumentStartJavaScript(web, scriptSilenciar, setOf(ORIGEM_JOGO))
        WebViewCompat.addDocumentStartJavaScript(web, scriptAutoCompra, setOf(ORIGEM_JOGO))
    }

    private fun configAutoCompra(): String = JSONObject().apply {
        put("ligado", prefs.getBoolean(P_AC_LIGADO, false))
        put("reservaOuro", prefs.getLong(P_AC_RESERVA, 0))
        if (prefs.getBoolean(P_AC_BALL, true)) put("ball", JSONObject().apply {
            put("min", prefs.getInt(P_AC_BALL_MIN, 100)); put("lote", prefs.getInt(P_AC_BALL_LOTE, 300))
        })
        if (prefs.getBoolean(P_AC_POT, true)) put("pot", JSONObject().apply {
            put("min", prefs.getInt(P_AC_POT_MIN, 50)); put("lote", prefs.getInt(P_AC_POT_LOTE, 100))
        })
    }.toString()

    private inner class PonteAutoCompra(private val conta: Int) {
        @JavascriptInterface
        fun config(): String = configAutoCompra()

        @JavascriptInterface
        fun mudo(): Boolean = prefs.getBoolean(P_MUDO, false)

        @JavascriptInterface
        fun sniperConfig(): String = sniper.configParaScript(conta)

        @JavascriptInterface
        fun sniperEvento(json: String) = sniper.evento(json)

        @JavascriptInterface
        fun souPrincipal(): Boolean = conta == sniper.contaPrincipal()

        @JavascriptInterface
        fun sniperCatalogo(json: String) {
            if (conta == sniper.contaPrincipal()) sniper.salvarCatalogo(json)
        }

        @JavascriptInterface
        fun comprou(item: String, qtd: Int) {
            val hora = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale("pt", "BR")).format(java.util.Date())
            val linha = "$hora · ${nomeConta(conta)}: $qtd× ${item.take(20)}"
            synchronized(prefs) {
                val antes = prefs.getString(P_AC_HISTORICO, "")!!.lines().filter { it.isNotBlank() }
                prefs.edit().putString(P_AC_HISTORICO, (listOf(linha) + antes).take(8).joinToString("\n")).apply()
            }
        }
    }

    private fun abrirAutoCompra() {
        val caixa = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0)
        }
        fun texto(t: String, tamanho: Float = 14f, cor: Int? = null) = TextView(this).apply {
            text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, tamanho); cor?.let { setTextColor(it) }
            setPadding(0, (6 * dp).toInt(), 0, 0)
            caixa.addView(this)
        }
        fun numero(dica: String, valor: Long) = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = dica
            setText(valor.toString())
            caixa.addView(this)
        }
        val ligado = Switch(this).apply {
            text = "Compra automática ligada"
            isChecked = prefs.getBoolean(P_AC_LIGADO, false)
            caixa.addView(this)
        }
        texto("Vale para todas as contas. Só compra com ouro.", 12f, 0xFF9AA8B8.toInt())

        val ball = CheckBox(this).apply {
            text = "Ultra Ball"; isChecked = prefs.getBoolean(P_AC_BALL, true); caixa.addView(this)
        }
        texto("Comprar quando tiver menos de:", 12f)
        val ballMin = numero("mínimo", prefs.getInt(P_AC_BALL_MIN, 100).toLong())
        texto("Quantas comprar de cada vez:", 12f)
        val ballLote = numero("quantidade", prefs.getInt(P_AC_BALL_LOTE, 300).toLong())

        val pot = CheckBox(this).apply {
            text = "Ultra Potion"; isChecked = prefs.getBoolean(P_AC_POT, true); caixa.addView(this)
        }
        texto("Comprar quando tiver menos de:", 12f)
        val potMin = numero("mínimo", prefs.getInt(P_AC_POT_MIN, 50).toLong())
        texto("Quantas comprar de cada vez:", 12f)
        val potLote = numero("quantidade", prefs.getInt(P_AC_POT_LOTE, 100).toLong())

        texto("Guardar sempre pelo menos este ouro (0 = gastar o que precisar):", 12f)
        val reserva = numero("ouro", prefs.getLong(P_AC_RESERVA, 0))

        val historico = prefs.getString(P_AC_HISTORICO, "")!!
        texto("Últimas compras:", 12f)
        texto(historico.ifBlank { "nenhuma ainda" }, 12f, 0xFF9AA8B8.toInt())

        val rolagem = android.widget.ScrollView(this).apply { addView(caixa) }
        fun ler(c: EditText) = c.text.toString().trim().toLongOrNull()?.coerceIn(0, 1_000_000) ?: 0
        AlertDialog.Builder(this)
            .setTitle("Compra automática")
            .setView(rolagem)
            .setPositiveButton("Salvar") { _, _ ->
                prefs.edit()
                    .putBoolean(P_AC_LIGADO, ligado.isChecked)
                    .putBoolean(P_AC_BALL, ball.isChecked)
                    .putInt(P_AC_BALL_MIN, ler(ballMin).toInt())
                    .putInt(P_AC_BALL_LOTE, ler(ballLote).toInt())
                    .putBoolean(P_AC_POT, pot.isChecked)
                    .putInt(P_AC_POT_MIN, ler(potMin).toInt())
                    .putInt(P_AC_POT_LOTE, ler(potLote).toInt())
                    .putLong(P_AC_RESERVA, ler(reserva))
                    .apply()
                Toast.makeText(this, if (ligado.isChecked) "Compra automática ligada" else "Compra automática desligada", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun compartilharIdDispositivo(web: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        var id = prefs.getString(P_ID_DISPOSITIVO, null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString(P_ID_DISPOSITIVO, id).apply()
        }
        val js = "try{if(localStorage.getItem('id-dispositivo')!=='$id')" +
            "localStorage.setItem('id-dispositivo','$id')}catch(e){}"
        WebViewCompat.addDocumentStartJavaScript(web, js, setOf(ORIGEM_JOGO))
    }

    // ------------------------------------------------------------------ login com Google
    //
    // O Google não aceita login dentro de WebView. Então o login vai para o NAVEGADOR do
    // celular (uma "Custom Tab", o jeito que o próprio Google recomenda para apps), e a volta
    // dele (https://pokeidle.io/app?sessao=…&ida=…) é entregue de volta a este app, que a
    // carrega na MESMA conta que começou o login. O `ida` foi sorteado e guardado pela página
    // daquela conta antes de sair, então o jogo aceita a sessão normalmente.
    //
    // Para o Android entregar essa volta ao app, o usuário precisa permitir uma vez:
    // Configurações > Apps > MultiIdle > Abrir por padrão > pokeidle.io.

    private fun ehIdaAoGoogle(url: Uri) =
        url.host == HOST_JOGO && (url.path ?: "").let { it == "/auth/google" || it == "/auth/google/" }

    private fun loginNoNavegador(i: Int, url: Uri) {
        prefs.edit().putInt(P_LOGIN_SLOT, i).putLong(P_LOGIN_EM, System.currentTimeMillis()).apply()
        if (linksLiberados()) { abrirCustomTab(url); return }
        AlertDialog.Builder(this)
            .setTitle("Falta uma permissão (só 1 vez)")
            .setMessage(
                "O login com Google acontece no navegador e depois volta para o MultiIdle.\n\n" +
                    "Para a volta funcionar, permita que o MultiIdle abra links do pokeidle.io:\n" +
                    "toque em \"Abrir configurações\" → \"Adicionar link\" (ou \"Links compatíveis\") → marque pokeidle.io.\n\n" +
                    "Depois volte aqui e toque no botão do Google de novo."
            )
            .setPositiveButton("Abrir configurações") { _, _ -> abrirConfigLinks() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun linksLiberados(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true // antes do Android 12 o sistema pergunta na hora
        return try {
            val dvm = getSystemService(DomainVerificationManager::class.java)
            val estado = dvm.getDomainVerificationUserState(packageName)?.hostToStateMap?.get(HOST_JOGO)
            estado == DomainVerificationUserState.DOMAIN_STATE_SELECTED ||
                estado == DomainVerificationUserState.DOMAIN_STATE_VERIFIED
        } catch (_: Exception) {
            false
        }
    }

    private fun abrirConfigLinks() {
        val pacote = Uri.parse("package:$packageName")
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                startActivity(Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, pacote))
                return
            }
        } catch (_: Exception) {
        }
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pacote))
    }

    private fun navegadorQueDevolve(): String? =
        listOf("com.android.chrome", "com.chrome.beta", "com.sec.android.app.sbrowser").firstOrNull { pacote ->
            try {
                packageManager.getApplicationInfo(pacote, 0).enabled
            } catch (_: Exception) {
                false
            }
        }

    private fun abrirCustomTab(url: Uri) {
        try {
            val aba = CustomTabsIntent.Builder().setShowTitle(true).build()
            // O Chrome devolve a volta do login para o app que tem o link liberado; o Brave
            // (visto no celular) não devolve e abre o jogo dentro da própria aba. Então: Chrome, se tiver.
            navegadorQueDevolve()?.let { aba.intent.setPackage(it) }
            aba.launchUrl(this, url)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Nenhum navegador encontrado", Toast.LENGTH_LONG).show()
        }
    }

    /** A volta do login (pokeidle.io/app?sessao=…) chegou: carrega na conta que pediu. */
    private fun receberVoltaDoLogin(intent: Intent) {
        val url = intent.data ?: return
        if (intent.action != Intent.ACTION_VIEW || url.host != HOST_JOGO) return
        if (url.getQueryParameter("sessao") == null) return
        val pedido = prefs.getInt(P_LOGIN_SLOT, -1)
        val recente = System.currentTimeMillis() - prefs.getLong(P_LOGIN_EM, 0) < 15 * 60_000
        val slot = when {
            recente && pedido in 0 until painel.quantidade -> pedido
            painel.foco >= 0 -> painel.foco
            else -> 0
        }
        prefs.edit().remove(P_LOGIN_SLOT).remove(P_LOGIN_EM).apply()
        (painel.webs[slot] as? WebView)?.loadUrl(url.toString())
        if (painel.quantidade > 1) focar(slot)
        // Não deixa a sessão parada no intent (ela é o login da conta).
        setIntent(Intent(this, MainActivity::class.java))
    }

    private fun abrirFora(url: Uri) {
        try {
            val intent = if (url.scheme == "intent") Intent.parseUri(url.toString(), Intent.URI_INTENT_SCHEME)
            else Intent(Intent.ACTION_VIEW, url)
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
        } catch (_: Exception) {
        }
    }

    private fun mudarQuantidade(n: Int) {
        val antes = painel.quantidade
        if (n == antes) return
        for (i in n until antes) destruirConta(i)
        for (i in antes until n) criarConta(i)
        painel.quantidade = n
        if (painel.foco >= n || n == 1) painel.foco = if (n == 1) 0 else -1
        prefs.edit().putInt(P_QTD, n).putInt(P_FOCO, painel.foco).apply()
        atualizarBarra()
        atualizarServico()
    }

    // ------------------------------------------------------------------ menu e opções

    private fun abrirMenu(ancora: View) {
        val menu = PopupMenu(this, ancora)
        val m = menu.menu
        if (painel.foco >= 0) m.add(0, 1, 0, "Recarregar ${nomeConta(painel.foco)}")
        if (painel.foco >= 0 && painel.zoomDe(painel.foco) != 1f) {
            m.add(0, 15, 0, "Zoom de ${nomeConta(painel.foco)}: ${(painel.zoomDe(painel.foco) * 100).toInt()}% → voltar a 100%")
        }
        m.add(0, 2, 1, "Recarregar todas")
        m.add(0, 3, 2, "Quantidade de contas…")
        m.add(0, 4, 3, "Renomear contas…")
        m.add(0, 5, 4, "Grade em miniatura").apply { isCheckable = true; isChecked = painel.miniatura }
        m.add(0, 6, 5, "Manter tela acesa").apply { isCheckable = true; isChecked = prefs.getBoolean(P_TELA_ACESA, true) }
        m.add(0, 11, 6, "Rodar com a tela apagada").apply { isCheckable = true; isChecked = prefs.getBoolean(P_TELA_APAGADA, true) }
        m.add(0, 7, 6, "Rodar em segundo plano").apply { isCheckable = true; isChecked = prefs.getBoolean(P_SEGUNDO_PLANO, true) }
        m.add(0, 8, 7, "Escurecer tela (economia)")
        m.add(0, 14, 7, "Sniper do RMT…")
        m.add(0, 13, 7, "Compra automática…" + if (prefs.getBoolean(P_AC_LIGADO, false)) " (ligada)" else "")
        m.add(0, 10, 8, "Diagnóstico de conexão")
        m.add(0, 12, 9, "Procurar atualização (v${packageManager.getPackageInfo(packageName, 0).versionName})")
        m.add(0, 9, 10, "Encerrar tudo")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> (painel.webs[painel.foco] as? WebView)?.reload()
                2 -> painel.webs.forEach { (it as? WebView)?.reload() }
                3 -> escolherQuantidade()
                4 -> renomear()
                5 -> {
                    painel.miniatura = !painel.miniatura
                    prefs.edit().putBoolean(P_MINIATURA, painel.miniatura).apply()
                }
                6 -> {
                    prefs.edit().putBoolean(P_TELA_ACESA, !prefs.getBoolean(P_TELA_ACESA, true)).apply()
                    aplicarTelaAcesa()
                }
                11 -> {
                    val novo = !prefs.getBoolean(P_TELA_APAGADA, true)
                    prefs.edit().putBoolean(P_TELA_APAGADA, novo).apply()
                    painel.webs.forEach { (it as? ContaWebView)?.manterAtiva = novo }
                    if (novo) pedirSemOtimizacaoDeBateria(forcar = true)
                }
                7 -> {
                    prefs.edit().putBoolean(P_SEGUNDO_PLANO, !prefs.getBoolean(P_SEGUNDO_PLANO, true)).apply()
                    atualizarServico()
                }
                8 -> entrarNoEscuro()
                9 -> encerrar()
                10 -> mostrarDiagnostico()
                13 -> abrirAutoCompra()
                15 -> {
                    painel.definirZoom(painel.foco, 1f)
                    prefs.edit().putFloat("zoom${painel.foco}", 1f).apply()
                }
                14 -> sniper.abrir()
                12 -> {
                    Toast.makeText(this, "Procurando atualização…", Toast.LENGTH_SHORT).show()
                    atualizador.verificar(manual = true)
                }
            }
            true
        }
        menu.show()
    }

    private fun escolherQuantidade() {
        if (!multiPerfil) { avisarSemPerfis(); return }
        val opcoes = arrayOf("1 conta", "2 contas", "3 contas", "4 contas")
        AlertDialog.Builder(this)
            .setTitle("Quantas contas abrir?")
            .setSingleChoiceItems(opcoes, painel.quantidade - 1) { d, qual ->
                d.dismiss()
                mudarQuantidade(qual + 1)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun renomear() {
        val caixa = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0)
        }
        val campos = (0 until painel.quantidade).map { i ->
            EditText(this).apply {
                setText(nomeConta(i))
                hint = "Conta ${i + 1}"
                setSingleLine()
                caixa.addView(this)
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Nome das contas")
            .setView(caixa)
            .setPositiveButton("Salvar") { _, _ ->
                val e = prefs.edit()
                campos.forEachIndexed { i, c ->
                    val t = c.text.toString().trim()
                    if (t.isEmpty()) e.remove("nome$i") else e.putString("nome$i", t)
                }
                e.apply()
                atualizarBarra()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun aplicarTelaAcesa() {
        if (prefs.getBoolean(P_TELA_ACESA, true)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun entrarNoEscuro() {
        escuro.visibility = View.VISIBLE
        window.attributes = window.attributes.apply { screenBrightness = 0.01f }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Toast.makeText(this, "Toque duas vezes para voltar", Toast.LENGTH_SHORT).show()
    }

    private fun sairDoEscuro() {
        escuro.visibility = View.GONE
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        aplicarTelaAcesa()
    }

    private fun atualizarServico() {
        if (prefs.getBoolean(P_SEGUNDO_PLANO, true)) KeepAliveService.iniciar(this, painel.quantidade)
        else KeepAliveService.parar(this)
    }

    /**
     * Com a tela apagada o Android (e mais ainda a Samsung) corta CPU e rede de apps "otimizados".
     * Pede para o MultiIdle ficar fora da otimização de bateria — o pedido oficial do sistema.
     */
    private fun pedirSemOtimizacaoDeBateria(forcar: Boolean) {
        if (!prefs.getBoolean(P_TELA_APAGADA, true)) return
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        if (!forcar && prefs.getBoolean(P_BATERIA_PERGUNTADA, false)) return
        prefs.edit().putBoolean(P_BATERIA_PERGUNTADA, true).apply()
        AlertDialog.Builder(this)
            .setTitle("Rodar com a tela apagada")
            .setMessage(
                "Para as contas continuarem rodando com a tela apagada, o MultiIdle precisa ficar " +
                    "fora da economia de bateria.\n\nNa próxima tela, toque em \"Permitir\"."
            )
            .setPositiveButton("Continuar") { _, _ ->
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                    )
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
            .setNegativeButton("Agora não", null)
            .show()
    }

    private fun pedirPermissaoNotificacao() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun avisarSemPerfis() {
        AlertDialog.Builder(this)
            .setTitle("Atualize o WebView")
            .setMessage(
                "O \"Android System WebView\" deste celular é antigo e não consegue separar o login " +
                    "de cada conta. Por enquanto só 1 conta funciona.\n\n" +
                    "Atualize \"Android System WebView\" (ou o Google Chrome) na Play Store e abra o app de novo."
            )
            .setPositiveButton("Abrir Play Store") { _, _ ->
                abrirFora(Uri.parse("market://details?id=com.google.android.webview"))
            }
            .setNegativeButton("Agora não", null)
            .show()
    }

    private fun encerrar() {
        KeepAliveService.parar(this)
        finishAndRemoveTask()
    }

    companion object {
        const val EXTRA_SAIR = "sair"
        private const val URL_JOGO = "https://pokeidle.io/app"
        private const val ORIGEM_JOGO = "https://pokeidle.io"

        private const val P_QTD = "quantidade"
        private const val P_FOCO = "foco"
        private const val P_MINIATURA = "miniatura"
        private const val P_TELA_ACESA = "telaAcesa"
        private const val P_SEGUNDO_PLANO = "segundoPlano"
        private const val P_ID_DISPOSITIVO = "idDispositivo"
        private const val P_LOGIN_SLOT = "loginSlot"
        private const val P_TELA_APAGADA = "telaApagada"
        private const val P_BATERIA_PERGUNTADA = "bateriaPerguntada"
        private const val P_MUDO = "mudo"
        private const val P_AC_LIGADO = "acLigado"
        private const val P_AC_RESERVA = "acReserva"
        private const val P_AC_BALL = "acBall"
        private const val P_AC_BALL_MIN = "acBallMin"
        private const val P_AC_BALL_LOTE = "acBallLote"
        private const val P_AC_POT = "acPot"
        private const val P_AC_POT_MIN = "acPotMin"
        private const val P_AC_POT_LOTE = "acPotLote"
        private const val P_AC_HISTORICO = "acHistorico"
        private const val P_LOGIN_EM = "loginEm"
        private const val HOST_JOGO = "pokeidle.io"

        private const val COR_FUNDO = 0xFF121A24.toInt()
        private const val COR_BARRA = 0xFF1E2A3A.toInt()
        private const val COR_TEXTO = 0xFFE8EDF3.toInt()
        private const val COR_DESTAQUE = 0xFFF2C14E.toInt()
    }
}
