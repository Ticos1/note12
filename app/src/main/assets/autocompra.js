// MultiIdle — compra automática de Ultra Ball e Ultra Potion (usa o núcleo).
//
// Quando uma delas fica abaixo do mínimo, manda o MESMO comando que o botão "Comprar" do Market
// ({ t: 'shop.buy', ... }). Os termos do jogo permitem automação que segue as regras de quem
// joga à mão; por isso: uma compra por vez, no máximo a cada 30 s por item, só com ouro.
(() => {
  const mi = window.__multiIdle;
  if (!mi || mi.autoCompra) return;
  mi.autoCompra = true;
  const pendente = {};
  const ID_ULTRA_POTION = 202;
  const PRECO_ULTRA_POTION = 400;

  function config() {
    try { return JSON.parse(mi.app().config()); } catch (_) { return null; }
  }

  function tentar(chave, kind, id, preco, tem, regra, reserva, nome) {
    if (!regra || !(regra.lote > 0) || tem >= regra.min) { delete pendente[chave]; return; }
    const p = pendente[chave];
    // Já pediu e o servidor ainda não respondeu com a mochila nova: espera (até 30 s).
    if (p && Date.now() < p.ate && tem <= p.antes) return;
    let qty = Math.floor(regra.lote);
    if (preco > 0) qty = Math.min(qty, Math.floor((mi.eu.gold - reserva) / preco));
    if (qty < 1) return;
    if (!mi.enviar({ t: 'shop.buy', kind, id, qty })) return;
    pendente[chave] = { ate: Date.now() + 30000, antes: tem };
    try { mi.app().comprou(nome, qty); } catch (_) {}
  }

  setInterval(() => {
    const c = config();
    if (!c || !c.ligado || !mi.pronto()) return;
    const reserva = Math.max(0, Number(c.reservaOuro) || 0);
    const ultraBall = mi.catalogoBolas.find(
      (b) => b && b.compravel !== false && /ultra/i.test(String(b.nome || b.name || '')),
    );
    if (ultraBall) {
      tentar('ball', 'ball', ultraBall.id, Number(ultraBall.priceGold) || 0,
        Number(mi.eu.balls[ultraBall.id]) || 0, c.ball, reserva, 'Ultra Ball');
    }
    tentar('pot', 'item', ID_ULTRA_POTION, PRECO_ULTRA_POTION,
      Number(mi.eu.items[ID_ULTRA_POTION]) || 0, c.pot, reserva, 'Ultra Potion');
  }, 5000);
})();
