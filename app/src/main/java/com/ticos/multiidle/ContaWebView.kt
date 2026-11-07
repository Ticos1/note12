package com.ticos.multiidle

import android.content.Context
import android.view.View
import android.webkit.WebView

/**
 * WebView que não "se esconde" do jogo quando a tela apaga ou o app é minimizado.
 *
 * Normalmente o Android avisa a WebView que a janela ficou invisível, e o navegador interno
 * passa a tratar a página como aba em segundo plano: marca `document.hidden`, desacelera os
 * timers (no fim, um disparo por minuto) e o jogo acaba perdendo a conexão — e o pokeidle só
 * conta o tempo com a conexão de pé. Aqui esse aviso é trocado por "visível", então o jogo
 * segue rodando normalmente com a tela apagada (o desenho na tela para, o resto continua).
 */
class ContaWebView(ctx: Context) : WebView(ctx) {

    /** Liga/desliga o "fingir que está visível" (menu: Rodar com a tela apagada). */
    var manterAtiva = true

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(if (manterAtiva) View.VISIBLE else visibility)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, if (manterAtiva) View.VISIBLE else visibility)
    }
}
