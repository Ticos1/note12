// MultiIdle — núcleo comum dos scripts que rodam na página do pokeidle.io de cada conta.
//
// Observa o WebSocket do jogo (sem mudar nada do que passa por ele) e mantém um resumo da
// conta: id, ouro, gemas, bolas, itens e o desvio do relógio do servidor. Os módulos
// (compra automática, sniper) assinam as mensagens por `ouvir` e mandam comandos por `enviar`.
// Uma resposta que o módulo pediu pode ser "engolida" para não bagunçar a tela do jogo.
(() => {
  if (window.__multiIdle) return;
  const Original = window.WebSocket;
  const ouvintes = [];
  const mi = (window.__multiIdle = {
    eu: { ok: false, id: null, gold: 0, orbs: 0, balls: {}, items: {} },
    catalogoBolas: [],
    desvio: 0, // Date.now() - relógio do servidor
    ws: null,
    ouvir(fn) { ouvintes.push(fn); },
    pronto() { return !!(mi.ws && mi.ws.readyState === 1 && mi.eu.ok); },
    enviar(obj) {
      if (!mi.pronto()) return false;
      try { mi.ws.send(JSON.stringify(obj)); return true; } catch (_) { return false; }
    },
    agoraServidor() { return Date.now() - mi.desvio; },
    app() { return window.MultiIdleApp || null; },
  });

  function aoReceber(ev) {
    let m;
    try { m = JSON.parse(ev.data); } catch (_) { return; }
    if (!m || typeof m !== 'object') return;
    if (m.t === 'welcome' && Array.isArray(m.catalogoBolas)) mi.catalogoBolas = m.catalogoBolas;
    const e = m.estado;
    if (e && (m.t === 'welcome' || m.t === 'estado')) {
      // O servidor manda só os campos que mudaram; estes chegam inteiros quando mudam.
      if ('id' in e) mi.eu.id = e.id;
      if ('balls' in e) mi.eu.balls = e.balls || {};
      if ('items' in e) mi.eu.items = e.items || {};
      if ('gold' in e) mi.eu.gold = Number(e.gold) || 0;
      if ('orbs' in e) mi.eu.orbs = Number(e.orbs) || 0;
      if (Number.isFinite(e.servidorAgora)) mi.desvio = Date.now() - e.servidorAgora;
      if (m.t === 'welcome') mi.eu.ok = true;
    }
    for (const fn of ouvintes) {
      try { if (fn(m) === 'engolir') ev.__multiIdleEngolir = true; } catch (_) {}
    }
  }

  class SocketObservado extends Original {
    constructor(url, protocolos) {
      super(url, protocolos);
      try {
        if (String(url).includes('pokeidle')) {
          mi.ws = this;
          mi.eu.ok = false;
          this.addEventListener('message', aoReceber);
        }
      } catch (_) {}
    }
    // O jogo usa `ws.onmessage = ...`. Nosso ouvinte (acima) roda antes e pode marcar a resposta
    // que só o MultiIdle pediu; essa não chega ao jogo.
    set onmessage(fn) {
      this.__jogo = fn;
      super.onmessage = typeof fn === 'function'
        ? (ev) => { if (!ev.__multiIdleEngolir) fn.call(this, ev); }
        : fn;
    }
    get onmessage() { return this.__jogo ?? null; }
  }
  window.WebSocket = SocketObservado;
})();
