package com.ticos.multiidle

import android.content.Context
import android.view.View
import android.view.ViewGroup
import kotlin.math.ceil
import kotlin.math.min

/**
 * Arruma as contas na tela.
 *
 * - Grade: todas lado a lado, cada uma com uma faixa de nome em cima (toque nela para abrir).
 * - Foco: uma conta ocupa a tela toda; as outras continuam ATRÁS dela, do mesmo tamanho e
 *   visíveis para o navegador — escondê-las (GONE) faria o Chromium tratá-las como aba
 *   oculta e desacelerar o jogo delas.
 *
 * No modo "miniatura" cada WebView tem sempre o tamanho da tela inteira e só é reduzida na
 * hora de desenhar. O jogo enxerga um celular normal (nada de layout espremido), e trocar
 * entre grade e foco não recarrega nem reorganiza a página.
 */
class Painel(ctx: Context) : ViewGroup(ctx) {

    val webs = arrayOfNulls<View>(MAX)
    val rotulos = arrayOfNulls<View>(MAX)

    /** Quantas contas estão abertas (1..4). */
    var quantidade = 1
        set(v) { field = v; requestLayout() }

    /** -1 = grade; 0..3 = conta em tela cheia. */
    var foco = -1
        set(v) { field = v; requestLayout() }

    /** true = miniatura da tela inteira; false = a página se ajusta ao tamanho da célula. */
    var miniatura = true
        set(v) { field = v; requestLayout() }

    private val alturaRotulo = (26 * resources.displayMetrics.density).toInt()
    private val espaco = (2 * resources.displayMetrics.density).toInt()

    init {
        clipChildren = true
    }

    /** Colunas x linhas da grade para [n] contas, conforme a orientação. */
    private fun grade(n: Int, w: Int, h: Int): Pair<Int, Int> {
        val retrato = h >= w
        val cols = when {
            n <= 1 -> 1
            n == 2 -> if (retrato) 1 else 2
            n == 3 -> if (retrato) 2 else 3
            else -> 2
        }
        return cols to ceil(n / cols.toFloat()).toInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val (cols, rows) = grade(quantidade, w, h)
        val cw = (w - espaco * (cols - 1)) / cols
        val ch = (h - espaco * (rows - 1)) / rows
        for (i in 0 until MAX) {
            val web = webs[i] ?: continue
            val rot = rotulos[i]
            val tamanhoCheio = foco >= 0 || miniatura
            val ww = if (tamanhoCheio) w else cw
            val wh = if (tamanhoCheio) h else (ch - alturaRotulo)
            web.measure(exato(ww), exato(wh.coerceAtLeast(1)))
            rot?.measure(exato(cw), exato(alturaRotulo))
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        val (cols, rows) = grade(quantidade, w, h)
        val cw = (w - espaco * (cols - 1)) / cols
        val ch = (h - espaco * (rows - 1)) / rows

        for (i in 0 until MAX) {
            val web = webs[i] ?: continue
            val rot = rotulos[i]

            if (foco >= 0) {
                web.layout(0, 0, w, h)
                web.pivotX = 0f; web.pivotY = 0f
                web.scaleX = 1f; web.scaleY = 1f
                web.translationX = 0f; web.translationY = 0f
                // A conta em foco fica por cima; as outras, atrás (mas vivas).
                web.translationZ = if (i == foco) 2f else 0f
                focavel(web, i == foco)
                web.importantForAccessibility =
                    if (i == foco) IMPORTANT_FOR_ACCESSIBILITY_AUTO else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                rot?.layout(0, 0, 0, 0)
                rot?.visibility = View.INVISIBLE
                continue
            }

            focavel(web, true)
            web.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_AUTO
            web.translationZ = 0f
            val col = i % cols
            val row = i / cols
            val x = col * (cw + espaco)
            val y = row * (ch + espaco)
            rot?.visibility = View.VISIBLE
            rot?.layout(x, y, x + cw, y + alturaRotulo)
            rot?.translationZ = 1f

            val ay = y + alturaRotulo
            val aw = cw
            val ah = ch - alturaRotulo
            if (miniatura) {
                web.layout(0, 0, w, h)
                val s = min(aw / w.toFloat(), ah / h.toFloat())
                web.pivotX = 0f; web.pivotY = 0f
                web.scaleX = s; web.scaleY = s
                web.translationX = x + (aw - w * s) / 2f
                web.translationY = ay + (ah - h * s) / 2f
            } else {
                web.layout(x, ay, x + aw, ay + ah)
                web.scaleX = 1f; web.scaleY = 1f
                web.translationX = 0f; web.translationY = 0f
            }
        }
    }

    /**
     * `isFocusable = false` também desliga o "focável pelo toque", e `isFocusable = true` NÃO o
     * religa. Era o bug: a conta que ficava atrás de outra em tela cheia voltava sem aceitar
     * teclado nem gestos. Os dois andam juntos aqui.
     */
    private fun focavel(v: View, sim: Boolean) {
        v.isFocusable = sim
        v.isFocusableInTouchMode = sim
        if (!sim && v.hasFocus()) v.clearFocus()
    }

    private fun exato(px: Int) = MeasureSpec.makeMeasureSpec(px, MeasureSpec.EXACTLY)

    companion object {
        const val MAX = 4
    }
}
