package com.ticos.multiidle

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import kotlin.math.abs
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
 *
 * ZOOM POR CONTA (pinça com dois dedos): o pokeidle no celular não deixa a página diminuir
 * (o "zoom out" do navegador para em 100%). Então o zoom é feito aqui: com zoom 0,7 a WebView
 * ganha uma tela 1/0,7 vezes maior e é desenhada 0,7 vezes menor — o jogo se reorganiza para
 * a tela "maior" e tudo cabe (o modal do Mapa inteiro, por exemplo). Com zoom acima de 1, o
 * contrário: tudo maior. Durante o gesto só o desenho muda; a página se reorganiza ao soltar.
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

    /** Zoom de cada conta: o da página (`zoomLayout`) e o que está na tela durante a pinça. */
    private val zoomLayout = FloatArray(MAX) { 1f }
    private val zoomVisual = FloatArray(MAX) { 1f }

    /** Chamado ao soltar a pinça, para o app guardar o zoom daquela conta. */
    var aoMudarZoom: ((Int, Float) -> Unit)? = null

    fun zoomDe(i: Int) = zoomLayout[i]

    fun definirZoom(i: Int, z: Float) {
        val v = ajustarZoom(z)
        zoomLayout[i] = v
        zoomVisual[i] = v
        requestLayout()
    }

    private fun ajustarZoom(z: Float): Float {
        val v = z.coerceIn(ZOOM_MIN, ZOOM_MAX)
        return if (abs(v - 1f) < 0.05f) 1f else v
    }

    /** Onde cada conta está na tela (para saber em qual delas a pinça começou). */
    private val areas = Array(MAX) { RectF() }

    /**
     * GRADE EM MINIATURA: todas as WebViews ficam no MESMO lugar (canto de cima, à esquerda) e só o
     * DESENHO e o TOQUE são levados até a célula de cada uma (`desloc`).
     *
     * Por quê: o navegador da WebView calcula quanto dela está visível na tela sem considerar a
     * redução. Uma conta da linha de baixo, posta lá com `translationY`, "passava da tela" pela
     * metade aos olhos dele — e o jogo encolhia a altura útil pela metade (o layout cortado das
     * contas de baixo). No canto de cima, como a Conta 1 sempre esteve, isso não acontece.
     */
    private val deslocX = FloatArray(MAX)
    private val deslocY = FloatArray(MAX)
    private var desenhoDeslocado = false
    private val areasRotulo = Array(MAX) { RectF() }

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
            val z = zoomLayout[i]
            val ww = if (tamanhoCheio) w else cw
            val wh = if (tamanhoCheio) h else (ch - alturaRotulo)
            web.measure(exato((ww / z).toInt().coerceAtLeast(1)), exato((wh / z).toInt().coerceAtLeast(1)))
            rot?.measure(exato(cw), exato(alturaRotulo))
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        val (cols, rows) = grade(quantidade, w, h)
        val cw = (w - espaco * (cols - 1)) / cols
        val ch = (h - espaco * (rows - 1)) / rows
        desenhoDeslocado = foco < 0 && miniatura

        for (i in 0 until MAX) {
            deslocX[i] = 0f
            deslocY[i] = 0f
            areasRotulo[i].setEmpty()
            val web = webs[i] ?: continue
            val rot = rotulos[i]

            val z = zoomLayout[i]
            val zv = zoomVisual[i]
            if (foco >= 0) {
                web.layout(0, 0, (w / z).toInt(), (h / z).toInt())
                web.pivotX = 0f; web.pivotY = 0f
                web.scaleX = zv; web.scaleY = zv
                web.translationX = 0f; web.translationY = 0f
                areas[i].set(0f, 0f, w.toFloat(), h.toFloat())
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
            areasRotulo[i].set(x.toFloat(), y.toFloat(), (x + cw).toFloat(), (y + alturaRotulo).toFloat())
            rot?.translationZ = 1f

            val ay = y + alturaRotulo
            val aw = cw
            val ah = ch - alturaRotulo
            areas[i].set(x.toFloat(), ay.toFloat(), (x + aw).toFloat(), (ay + ah).toFloat())
            web.pivotX = 0f; web.pivotY = 0f
            if (miniatura) {
                val lw = (w / z).toInt()
                val lh = (h / z).toInt()
                web.layout(0, 0, lw, lh)
                val s = min(aw / w.toFloat(), ah / h.toFloat()) * zv
                web.scaleX = s; web.scaleY = s
                web.translationX = 0f
                web.translationY = 0f
                deslocX[i] = x + (aw - lw * s) / 2f
                deslocY[i] = ay + (ah - lh * s) / 2f
            } else {
                web.layout(x, ay, x + (aw / z).toInt(), ay + (ah / z).toInt())
                web.scaleX = zv; web.scaleY = zv
                web.translationX = 0f; web.translationY = 0f
            }
        }
    }

    // ------------------------------------------------------------------ pinça

    private var alvoPinca = -1
    private var pincando = false
    private var cancelarFilhos = false

    private val pinca = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            alvoPinca = contaEm(d.focusX, d.focusY)
            if (alvoPinca < 0 || webs[alvoPinca] == null) return false
            pincando = true
            cancelarFilhos = true
            return true
        }

        override fun onScale(d: ScaleGestureDetector): Boolean {
            val i = alvoPinca
            if (i < 0) return false
            zoomVisual[i] = (zoomVisual[i] * d.scaleFactor).coerceIn(ZOOM_MIN, ZOOM_MAX)
            requestLayout()
            return true
        }

        override fun onScaleEnd(d: ScaleGestureDetector) {
            val i = alvoPinca
            if (i < 0) return
            val z = ajustarZoom(zoomVisual[i])
            zoomLayout[i] = z
            zoomVisual[i] = z
            requestLayout()
            aoMudarZoom?.invoke(i, z)
        }
    }).apply { isQuickScaleEnabled = false }

    private fun contaEm(x: Float, y: Float): Int {
        if (foco >= 0) return foco
        for (i in 0 until quantidade) if (webs[i] != null && areas[i].contains(x, y)) return i
        return -1
    }

    /**
     * Dois dedos fazendo pinça = zoom DA CONTA (não do jogo). Assim que a pinça é reconhecida, a
     * página recebe um "cancelar" e não vê mais nada até os dedos saírem da tela.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        pinca.onTouchEvent(ev)
        if (pincando) {
            if (cancelarFilhos) {
                cancelarFilhos = false
                val c = MotionEvent.obtain(ev)
                c.action = MotionEvent.ACTION_CANCEL
                entregar(c)
                c.recycle()
            }
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                pincando = false
                alvoPinca = -1
                alvoToque = SEM_ALVO
            }
            return true
        }
        return entregar(ev)
    }

    // ------------------------------------------------------------------ toque na grade deslocada

    /** Quem recebe o gesto em curso: índice da conta, RÓTULO (caminho normal) ou ninguém. */
    private var alvoToque = SEM_ALVO

    private fun entregar(ev: MotionEvent): Boolean {
        if (!desenhoDeslocado) return super.dispatchTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            alvoToque = when {
                (0 until quantidade).any { areasRotulo[it].contains(ev.x, ev.y) } -> ROTULO
                else -> contaEm(ev.x, ev.y).let { if (it >= 0) it else SEM_ALVO }
            }
        }
        val alvo = alvoToque
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) alvoToque = SEM_ALVO
        return when {
            alvo == ROTULO -> super.dispatchTouchEvent(ev)
            alvo >= 0 -> {
                val web = webs[alvo] ?: return false
                // Da tela para dentro da conta: tira o deslocamento e desfaz a redução.
                val m = Matrix()
                m.setTranslate(-deslocX[alvo] - web.left, -deslocY[alvo] - web.top)
                m.postScale(1f / web.scaleX, 1f / web.scaleY)
                val local = MotionEvent.obtain(ev)
                local.transform(m)
                val ok = web.dispatchTouchEvent(local)
                local.recycle()
                ok
            }
            else -> false
        }
    }

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        val i = if (desenhoDeslocado) webs.indexOf(child) else -1
        if (i < 0) return super.drawChild(canvas, child, drawingTime)
        canvas.save()
        canvas.clipRect(areas[i])
        canvas.translate(deslocX[i], deslocY[i])
        val r = super.drawChild(canvas, child, drawingTime)
        canvas.restore()
        return r
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
        private const val SEM_ALVO = -1
        private const val ROTULO = -2
        const val ZOOM_MIN = 0.4f
        const val ZOOM_MAX = 2.0f
    }
}
