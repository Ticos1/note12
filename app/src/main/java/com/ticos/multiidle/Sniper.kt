package com.ticos.multiidle

import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * O sniper do RMT, do lado do app: as regras (o que, por quanto, em que moeda, quantos), os
 * limites de gasto por dia, o histórico e a ponte com o script `assets/sniper.js`, que roda só na
 * conta principal.
 *
 * Tudo mora nas SharedPreferences: `snConfig` (JSON com as regras), `snDia` (o gasto de hoje) e
 * `snHistorico` (as últimas linhas). O catálogo de nomes (itens e espécies) vem da própria página
 * do jogo e fica num arquivo.
 */
class Sniper(
    private val activity: Activity,
    private val prefs: SharedPreferences,
    private val nomeConta: (Int) -> String,
    private val quantidadeContas: () -> Int,
) {
    private val ctx: Context = activity.applicationContext
    private val dp = activity.resources.displayMetrics.density
    private val arquivoCatalogo = File(ctx.filesDir, "sniper-catalogo.json")
    private val trava = Any()
    private val ultimoSemSaldo = HashMap<String, Long>()

    // ------------------------------------------------------------------ dados

    private fun lerConfig(): JSONObject = try {
        JSONObject(prefs.getString(P_CONFIG, null) ?: "{}")
    } catch (_: Exception) {
        JSONObject()
    }

    private fun salvarConfig(c: JSONObject) = prefs.edit().putString(P_CONFIG, c.toString()).apply()

    private fun regras(c: JSONObject): JSONArray = c.optJSONArray("regras") ?: JSONArray().also { c.put("regras", it) }

    private fun hoje() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** O gasto de hoje: zera sozinho na virada do dia. */
    private fun gastoHoje(): JSONObject {
        val d = try { JSONObject(prefs.getString(P_DIA, null) ?: "{}") } catch (_: Exception) { JSONObject() }
        return if (d.optString("dia") == hoje()) d else JSONObject().put("dia", hoje()).put("gold", 0.0).put("orb", 0.0)
    }

    fun contaPrincipal(): Int = lerConfig().optInt("conta", 0)

    /** O que o script da conta [conta] recebe a cada segundo. Outras contas recebem "null". */
    fun configParaScript(conta: Int): String = synchronized(trava) {
        val c = lerConfig()
        if (!c.optBoolean("ligado", false) || c.optInt("conta", 0) != conta) return "null"
        val g = gastoHoje()
        val saida = JSONArray()
        val lista = regras(c)
        for (i in 0 until lista.length()) {
            val r = lista.getJSONObject(i)
            val restante = r.optInt("qtdMax", 0) - r.optInt("comprados", 0)
            saida.put(JSONObject(r.toString()).put("restante", restante.coerceAtLeast(0)))
        }
        JSONObject()
            .put("ligado", true)
            .put("restanteGoldHoje", (c.optDouble("limiteGold", 0.0) - g.optDouble("gold", 0.0)).coerceAtLeast(0.0))
            .put("restanteOrbHoje", (c.optDouble("limiteOrb", 0.0) - g.optDouble("orb", 0.0)).coerceAtLeast(0.0))
            .put("regras", saida)
            .toString()
    }

    /** Um acontecimento do script (viu, pediu, levou, perdeu, recusado, semSaldo). */
    fun evento(json: String) {
        val e = try { JSONObject(json) } catch (_: Exception) { return }
        val nome = e.optString("nome").take(40)
        val moeda = e.optString("moeda")
        val linha = when (e.optString("tipo")) {
            "viu" -> "👀 $nome: ${e.optInt("qtd")}× a ${valor(e.optDouble("preco"), moeda)} — sorteio em ${e.optInt("liberaEmS")} s"
            "pediu" -> "🎲 $nome: entrou no sorteio (${e.optInt("qtd")}× a ${valor(e.optDouble("preco"), moeda)})"
            "levou" -> {
                registrarCompra(e)
                notificar("Sniper levou: $nome", "${e.optInt("qtd")}× por ${valor(e.optDouble("custo"), moeda)}")
                "✅ $nome: LEVOU ${e.optInt("qtd")}× por ${valor(e.optDouble("custo"), moeda)}"
            }
            "perdeu" -> "❌ $nome: outro jogador levou no sorteio"
            "recusado" -> "⚠️ $nome: recusado (${e.optString("motivo").ifBlank { "sem motivo" }.take(40)})"
            "semSaldo" -> {
                val chave = e.optString("regra")
                val agora = System.currentTimeMillis()
                if (agora - (ultimoSemSaldo[chave] ?: 0) < 10 * 60_000) return
                ultimoSemSaldo[chave] = agora
                "💸 $nome: achou a ${valor(e.optDouble("preco"), moeda)}, mas sem saldo ou limite do dia"
            }
            else -> return
        }
        val hora = SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR")).format(Date())
        synchronized(trava) {
            val antes = prefs.getString(P_HISTORICO, "")!!.lines().filter { it.isNotBlank() }
            prefs.edit().putString(P_HISTORICO, (listOf("$hora $linha") + antes).take(40).joinToString("\n")).apply()
        }
    }

    private fun registrarCompra(e: JSONObject) = synchronized(trava) {
        val c = lerConfig()
        val lista = regras(c)
        for (i in 0 until lista.length()) {
            val r = lista.getJSONObject(i)
            if (r.optString("id") == e.optString("regra")) r.put("comprados", r.optInt("comprados") + e.optInt("qtd"))
        }
        salvarConfig(c)
        val g = gastoHoje()
        val chave = if (e.optString("moeda") == "orb") "orb" else "gold"
        g.put(chave, g.optDouble(chave, 0.0) + e.optDouble("custo", 0.0))
        prefs.edit().putString(P_DIA, g.toString()).apply()
    }

    fun salvarCatalogo(json: String) {
        try {
            JSONArray(json) // só guarda se for uma lista válida
            arquivoCatalogo.writeText(json)
        } catch (_: Exception) {
        }
    }

    private data class Entrada(val tipo: String, val id: Int, val nome: String) {
        override fun toString() = nome
    }

    private fun catalogo(tipo: String): List<Entrada> = try {
        val a = JSONArray(arquivoCatalogo.readText())
        (0 until a.length()).map { a.getJSONObject(it) }
            .filter { it.optString("t") == tipo }
            .map { Entrada(tipo, it.optInt("id"), it.optString("n")) }
            .distinctBy { it.nome.lowercase() }
            .sortedBy { it.nome.lowercase() }
    } catch (_: Exception) {
        emptyList()
    }

    // ------------------------------------------------------------------ tela principal

    fun abrir() {
        val c = lerConfig()
        val caixa = coluna()
        val ligado = Switch(activity).apply {
            text = "Sniper ligado"
            isChecked = c.optBoolean("ligado", false)
            caixa.addView(this)
        }
        texto(caixa, "Roda só na conta principal. Olha o mercado a cada 30 s e entra no sorteio de 3 s quando o anúncio libera (todo anúncio novo fica 2 min retido). Pode perder o sorteio para outro jogador.", 12f, CINZA)

        texto(caixa, "Conta principal:", 13f)
        val nomes = (0 until quantidadeContas()).map { nomeConta(it) }
        val conta = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, nomes)
            setSelection(c.optInt("conta", 0).coerceIn(0, (nomes.size - 1).coerceAtLeast(0)))
            caixa.addView(this)
        }

        val g = gastoHoje()
        texto(caixa, "Gastar no máximo por dia, em Coins (0 = não compra em Coins):", 13f)
        val limGold = numero(caixa, c.optDouble("limiteGold", 0.0))
        texto(caixa, "Hoje: ${valor(g.optDouble("gold"), "gold")}", 12f, CINZA)
        texto(caixa, "Gastar no máximo por dia, em Gemas (0 = não compra em Gemas). Gema é dinheiro de verdade e compra no RMT não volta:", 13f)
        val limOrb = numero(caixa, c.optDouble("limiteOrb", 0.0))
        texto(caixa, "Hoje: ${valor(g.optDouble("orb"), "orb")}", 12f, CINZA)

        texto(caixa, "Regras (toque para editar):", 14f).setPadding(0, (14 * dp).toInt(), 0, 0)
        val lista = regras(c)
        if (lista.length() == 0) texto(caixa, "Nenhuma regra ainda.", 12f, CINZA)
        for (i in 0 until lista.length()) {
            val r = lista.getJSONObject(i)
            texto(caixa, "• " + resumoRegra(r), 13f).apply {
                setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
                isClickable = true
                setOnClickListener { salvarTopo(); editarRegra(r.optString("id")) }
            }
        }

        val historico = prefs.getString(P_HISTORICO, "")!!
        texto(caixa, "Histórico:", 14f).setPadding(0, (14 * dp).toInt(), 0, 0)
        texto(caixa, historico.lines().take(15).joinToString("\n").ifBlank { "nada ainda" }, 11f, CINZA)

        var dialogo: AlertDialog? = null
        salvarTopoAtual = {
            synchronized(trava) {
                val atual = lerConfig()
                atual.put("ligado", ligado.isChecked)
                    .put("conta", conta.selectedItemPosition.coerceAtLeast(0))
                    .put("limiteGold", lerNumero(limGold))
                    .put("limiteOrb", lerNumero(limOrb))
                salvarConfig(atual)
            }
            dialogo?.dismiss()
        }
        dialogo = AlertDialog.Builder(activity)
            .setTitle("Sniper do RMT")
            .setView(ScrollView(activity).apply { addView(caixa) })
            .setPositiveButton("Salvar") { _, _ ->
                salvarTopo()
                Toast.makeText(activity, if (ligado.isChecked) "Sniper ligado" else "Sniper desligado", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("+ Regra") { _, _ -> salvarTopo(); editarRegra(null) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private var salvarTopoAtual: (() -> Unit)? = null
    private fun salvarTopo() { salvarTopoAtual?.invoke(); salvarTopoAtual = null }

    private fun resumoRegra(r: JSONObject): String {
        val tipo = if (r.optString("tipo") == "item") "Item" else "Pokémon"
        val filtros = buildList {
            when (r.optString("shiny")) { "sim" -> add("só shiny"); "nao" -> add("sem shiny") }
            if (r.optInt("potMin") > 0) add("P${r.optInt("potMin")}+")
            if (r.optInt("ivMin") > 0) add("IV ${r.optInt("ivMin")}+")
        }
        val pausa = if (r.optBoolean("ativo", true)) "" else " [pausada]"
        val filtroTxt = if (filtros.isEmpty()) "" else " (${filtros.joinToString(", ")})"
        return "$tipo: ${r.optString("nome")}$filtroTxt — até ${valor(r.optDouble("max"), r.optString("moeda"))}/un · " +
            "${r.optInt("comprados")}/${r.optInt("qtdMax")} comprados$pausa"
    }

    // ------------------------------------------------------------------ criar / editar regra

    private fun editarRegra(id: String?) {
        val c = lerConfig()
        val lista = regras(c)
        val existente = (0 until lista.length()).map { lista.getJSONObject(it) }.firstOrNull { it.optString("id") == id }
        val r = existente ?: JSONObject()
        val caixa = coluna()

        texto(caixa, "O quê:", 13f)
        val tipo = RadioGroup(activity).apply { orientation = RadioGroup.HORIZONTAL; caixa.addView(this) }
        val rbItem = RadioButton(activity).apply { text = "Item"; this.id = View.generateViewId(); tipo.addView(this) }
        val rbPoke = RadioButton(activity).apply { text = "Pokémon"; this.id = View.generateViewId(); tipo.addView(this) }

        val alvo = AutoCompleteTextView(activity).apply {
            hint = "digite o nome"
            threshold = 1
            setSingleLine()
            caixa.addView(this)
        }
        val avisoCatalogo = texto(caixa, "", 11f, CINZA)
        var escolhido: Entrada? = if (existente != null) Entrada(r.optString("tipo"), r.optInt("alvo"), r.optString("nome")) else null

        texto(caixa, "Pagar em:", 13f)
        val moeda = RadioGroup(activity).apply { orientation = RadioGroup.HORIZONTAL; caixa.addView(this) }
        val rbGold = RadioButton(activity).apply { text = "Coins"; this.id = View.generateViewId(); moeda.addView(this) }
        val rbOrb = RadioButton(activity).apply { text = "Gemas"; this.id = View.generateViewId(); moeda.addView(this) }

        texto(caixa, "Preço máximo POR UNIDADE:", 13f)
        val max = numero(caixa, r.optDouble("max", 0.0))
        texto(caixa, "Quantidade máxima a comprar (no total):", 13f)
        val qtdMax = numero(caixa, r.optInt("qtdMax", 1).toDouble())
        val comprados = r.optInt("comprados", 0)
        if (comprados > 0) texto(caixa, "Já comprados por esta regra: $comprados", 12f, CINZA)

        val blocoPoke = coluna().apply { setPadding(0, 0, 0, 0) }
        caixa.addView(blocoPoke)
        texto(blocoPoke, "Shiny:", 13f)
        val shiny = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, listOf("Tanto faz", "Só shiny", "Sem shiny"))
            setSelection(when (r.optString("shiny")) { "sim" -> 1; "nao" -> 2; else -> 0 })
            blocoPoke.addView(this)
        }
        texto(blocoPoke, "Potência mínima (1 a 5, 0 = qualquer):", 13f)
        val potMin = numero(blocoPoke, r.optInt("potMin", 0).toDouble())
        texto(blocoPoke, "IV mínimo (0 = qualquer):", 13f)
        val ivMin = numero(blocoPoke, r.optInt("ivMin", 0).toDouble())

        val ativo = CheckBox(activity).apply {
            text = "Regra ativa"; isChecked = r.optBoolean("ativo", true); caixa.addView(this)
        }

        fun aplicarTipo() {
            val t = if (rbPoke.isChecked) "p" else "i"
            val itens = catalogo(t)
            alvo.setAdapter(ArrayAdapter(activity, android.R.layout.simple_dropdown_item_1line, itens))
            avisoCatalogo.text = if (itens.isEmpty())
                "A lista de nomes ainda não chegou: deixe a conta principal aberta no app por alguns segundos e abra esta tela de novo."
            else "Escolha da lista."
            blocoPoke.visibility = if (rbPoke.isChecked) View.VISIBLE else View.GONE
            if (escolhido?.tipo != t) { escolhido = null; alvo.setText("") }
        }
        if (r.optString("tipo") == "pokemon") rbPoke.isChecked = true else rbItem.isChecked = true
        if (r.optString("moeda") == "orb") rbOrb.isChecked = true else rbGold.isChecked = true
        val inicial = escolhido
        aplicarTipo()
        if (inicial != null) {
            escolhido = Entrada(if (r.optString("tipo") == "pokemon") "p" else "i", inicial.id, inicial.nome)
            alvo.setText(inicial.nome, false)
        }
        tipo.setOnCheckedChangeListener { _, _ -> aplicarTipo() }
        alvo.setOnItemClickListener { parent, _, pos, _ -> escolhido = parent.getItemAtPosition(pos) as Entrada }

        val b = AlertDialog.Builder(activity)
            .setTitle(if (existente == null) "Nova regra" else "Editar regra")
            .setView(ScrollView(activity).apply { addView(caixa) })
            .setPositiveButton("Salvar", null)
            .setNegativeButton("Cancelar") { _, _ -> abrir() }
        if (existente != null) b.setNeutralButton("Apagar") { _, _ ->
            synchronized(trava) {
                val atual = lerConfig()
                val l = regras(atual)
                val nova = JSONArray()
                for (i in 0 until l.length()) if (l.getJSONObject(i).optString("id") != id) nova.put(l.getJSONObject(i))
                atual.put("regras", nova)
                salvarConfig(atual)
            }
            abrir()
        }
        val d = b.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val e = escolhido
            val preco = lerNumero(max)
            val qtd = lerNumero(qtdMax).toInt()
            when {
                e == null -> Toast.makeText(activity, "Escolha o item/pokémon da lista", Toast.LENGTH_SHORT).show()
                preco <= 0 -> Toast.makeText(activity, "Coloque o preço máximo", Toast.LENGTH_SHORT).show()
                qtd <= 0 -> Toast.makeText(activity, "Coloque a quantidade máxima", Toast.LENGTH_SHORT).show()
                else -> {
                    synchronized(trava) {
                        val atual = lerConfig()
                        val l = regras(atual)
                        val nova = JSONObject()
                            .put("id", id ?: System.currentTimeMillis().toString(36))
                            .put("tipo", if (e.tipo == "p") "pokemon" else "item")
                            .put("alvo", e.id)
                            .put("nome", e.nome)
                            .put("moeda", if (rbOrb.isChecked) "orb" else "gold")
                            .put("max", preco)
                            .put("qtdMax", qtd)
                            .put("comprados", comprados)
                            .put("ativo", ativo.isChecked)
                            .put("shiny", when (shiny.selectedItemPosition) { 1 -> "sim"; 2 -> "nao"; else -> "qualquer" })
                            .put("potMin", lerNumero(potMin).toInt().coerceIn(0, 5))
                            .put("ivMin", lerNumero(ivMin).toInt().coerceAtLeast(0))
                        var trocou = false
                        for (i in 0 until l.length()) if (l.getJSONObject(i).optString("id") == id) { l.put(i, nova); trocou = true }
                        if (!trocou) l.put(nova)
                        salvarConfig(atual)
                    }
                    d.dismiss()
                    abrir()
                }
            }
        }
    }

    // ------------------------------------------------------------------ pecinhas

    private fun coluna() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), (8 * dp).toInt())
    }

    private fun texto(pai: LinearLayout, t: String, tamanho: Float, cor: Int? = null) = TextView(activity).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, tamanho)
        cor?.let { setTextColor(it) }
        setPadding(0, (6 * dp).toInt(), 0, 0)
        pai.addView(this)
    }

    private fun numero(pai: LinearLayout, v: Double) = EditText(activity).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        setText(if (v == Math.floor(v)) v.toLong().toString() else v.toString())
        setSingleLine()
        pai.addView(this)
    }

    /** Aceita "1.500.000", "1500000" e "2,5" (vírgula decimal). */
    private fun lerNumero(e: EditText): Double {
        val s = e.text.toString().trim()
        val limpo = if (s.contains(',')) s.replace(".", "").replace(',', '.') else
            if (s.count { it == '.' } > 1 || Regex("""\.\d{3}$""").containsMatchIn(s)) s.replace(".", "") else s
        return limpo.toDoubleOrNull()?.coerceIn(0.0, 1e15) ?: 0.0
    }

    private fun valor(v: Double, moeda: String): String {
        val nf = NumberFormat.getNumberInstance(Locale("pt", "BR")).apply { maximumFractionDigits = 2 }
        return nf.format(v) + if (moeda == "orb") " Gemas" else " Coins"
    }

    private fun notificar(titulo: String, texto: String) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CANAL) == null) {
                nm.createNotificationChannel(NotificationChannel(CANAL, "Sniper do RMT", NotificationManager.IMPORTANCE_DEFAULT))
            }
            val abrir = PendingIntent.getActivity(
                ctx, 3, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = Notification.Builder(ctx, CANAL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(titulo)
                .setContentText(texto)
                .setContentIntent(abrir)
                .setAutoCancel(true)
                .build()
            nm.notify((System.currentTimeMillis() % 100000).toInt() + 10, n)
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val P_CONFIG = "snConfig"
        private const val P_DIA = "snDia"
        private const val P_HISTORICO = "snHistorico"
        private const val CANAL = "sniper"
        private const val CINZA = 0xFF9AA8B8.toInt()
    }
}
