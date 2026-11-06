// MultiIdle — compra automática de Ultra Ball e Ultra Potion.
//
// Roda dentro da página do pokeidle.io de cada conta. Não clica em nada: escuta o mesmo
// WebSocket do jogo para saber quantas bolas/poções e quanto ouro a conta tem, e quando uma
// delas fica abaixo do mínimo manda o MESMO comando que o botão "Comprar" do Market manda
// ({ t: 'shop.buy', ... }). Os termos do jogo permitem automação que segue as regras de quem
// joga à mão; por isso: uma compra por vez, no máximo a cada 30 s por item, só com ouro.
(() => {
  if (window.__multiIdleAutoCompra) return;
  window.__multiIdleAutoCompra = true;

  const Original = window.WebSocket;
  const eu = { ok: false, balls: {}, items: {}, gold: 0 };
  let catalogoBolas = [];
  let ws = null;
  const pendente = {};
  const ID_ULTRA_POTION = 202;
  const PRECO_ULTRA_POTION = 400;

  function aoReceber(ev) {
    let m;
    try { m = JSON.parse(ev.data); } catch { return; }
    if (!m) return;
    if (m.t === 'welcome' && Array.isArray(m.catalogoBolas)) catalogoBolas = m.catalogoBolas;
    const e = m.estado;
    if (e && (m.t === 'welcome' || m.t === 'estado')) {
      // O servidor manda só os campos que mudaram; bolas/itens/ouro chegam inteiros quando mudam.
      if ('balls' in e) eu.balls = e.balls || {};
      if ('items' in e) eu.items = e.items || {};
      if ('gold' in e) eu.gold = Number(e.gold) || 0;
      if (m.t === 'welcome') eu.ok = true;
    }
  }

  class SocketObservado extends Original {
    constructor(url, protocolos) {
      super(url, protocolos);
      try {
        if (String(url).includes('pokeidle')) {
          ws = this;
          eu.ok = false;
          this.addEventListener('message', aoReceber);
        }
      } catch (_) {}
    }
  }
  window.WebSocket = SocketObservado;

  function config() {
    try { return JSON.parse(window.MultiIdleApp.config()); } catch (_) { return null; }
  }

  function tentar(chave, kind, id, preco, tem, regra, reserva, nome) {
    if (!regra || !(regra.lote > 0) || tem >= regra.min) { delete pendente[chave]; return; }
    const p = pendente[chave];
    // Já pediu e o servidor ainda não respondeu com a mochila nova: espera (até 30 s).
    if (p && Date.now() < p.ate && tem <= p.antes) return;
    let qty = Math.floor(regra.lote);
    if (preco > 0) qty = Math.min(qty, Math.floor((eu.gold - reserva) / preco));
    if (qty < 1) return;
    try {
      ws.send(JSON.stringify({ t: 'shop.buy', kind, id, qty }));
      pendente[chave] = { ate: Date.now() + 30000, antes: tem };
      try { window.MultiIdleApp.comprou(nome, qty); } catch (_) {}
    } catch (_) {}
  }

  setInterval(() => {
    const c = config();
    if (!c || !c.ligado || !ws || ws.readyState !== 1 || !eu.ok) return;
    const reserva = Math.max(0, Number(c.reservaOuro) || 0);
    const ultraBall = catalogoBolas.find(
      (b) => b && b.compravel !== false && /ultra/i.test(String(b.nome || b.name || '')),
    );
    if (ultraBall) {
      tentar('ball', 'ball', ultraBall.id, Number(ultraBall.priceGold) || 0,
        Number(eu.balls[ultraBall.id]) || 0, c.ball, reserva, 'Ultra Ball');
    }
    tentar('pot', 'item', ID_ULTRA_POTION, PRECO_ULTRA_POTION,
      Number(eu.items[ID_ULTRA_POTION]) || 0, c.pot, reserva, 'Ultra Potion');
  }, 5000);
})();
