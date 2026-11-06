// MultiIdle — sniper do RMT (Mercado da Comunidade), só na conta principal.
//
// Como o RMT funciona (lido do próprio jogo, 11/2026): todo anúncio novo fica 2 minutos RETIDO e,
// quando libera, quem pediu a compra nos primeiros 3 s entra num SORTEIO. Velocidade não ganha
// nada — e o sniper não tenta ganhar por velocidade. Ele só:
//   1. olha o mercado das regras do jogador a cada 30 s (o botão Atualizar do jogo deixa a cada 5 s);
//   2. anúncio que cabe numa regra (preço máximo por unidade, moeda, quantidade, limite do dia,
//      saldo) é guardado e, na hora em que libera, entra no sorteio com o MESMO pedido do botão
//      "Comprar" ({ t: 'market.comprar', id, qtd, preco, moeda });
//   3. o resultado (levou / perdeu / recusado) volta para o app, que conta o que foi gasto.
// O `preco` vai no pedido: se o vendedor mudar o preço depois, o servidor recusa — nunca cobra outro.
(() => {
  const mi = window.__multiIdle;
  if (!mi || mi.sniper) return;
  mi.sniper = true;

  const CICLO_MS = 30_000; // de quanto em quanto tempo cada regra olha o mercado
  const ENTRE_PEDIDOS_MS = 2_000; // nunca mais de uma leitura do mercado a cada 2 s
  const ESPERA_RESPOSTA_MS = 8_000;
  const ANTECEDENCIA_MAX_MS = 10 * 60_000; // anúncio que libera daqui a mais que isso: vê depois
  const MARGEM_LIBERACAO_MS = 400; // pede logo depois de liberar, dentro dos 3 s do sorteio

  const fila = [];
  let emVoo = null; // { regra, enviadoEm }
  let ultimoEnvio = 0;
  const ultimaLeitura = {}; // regra.id -> quando leu
  const agendados = new Map(); // anúncio id -> { timer, regra, qtd, preco, moeda, custo }
  const pendentes = new Map(); // anúncio id -> { regra, qtd, preco, moeda, custo, ate }
  let catalogoEnviado = false;

  const app = () => mi.app();
  function config() {
    try { return JSON.parse(app().sniperConfig()); } catch (_) { return null; }
  }
  function avisar(tipo, dados) {
    try { app().sniperEvento(JSON.stringify({ tipo, ...dados })); } catch (_) {}
  }

  // ---------------------------------------------------------------- o que já está comprometido
  const reservado = (moeda) => {
    let s = 0;
    for (const x of agendados.values()) if (x.moeda === moeda) s += x.custo;
    for (const x of pendentes.values()) if (x.moeda === moeda) s += x.custo;
    return s;
  };
  const reservadoNaRegra = (id) => {
    let s = 0;
    for (const x of agendados.values()) if (x.regra.id === id) s += x.qtd;
    for (const x of pendentes.values()) if (x.regra.id === id) s += x.qtd;
    return s;
  };

  // ---------------------------------------------------------------- leitura do mercado
  function pedidoDaRegra(r) {
    if (r.tipo === 'item') return { t: 'market.item', itemId: r.alvo, moeda: r.moeda };
    return {
      t: 'market.listar',
      tipo: 'pokemon',
      moeda: r.moeda,
      busca: '',
      ordem: 'baratos',
      criterios: [],
      elementos: [],
      categoria: '',
      soShiny: r.shiny === 'sim',
      soP5: false,
      soMega: false,
      soLendario: false,
      soTmElemental: false,
      soTmAoe: false,
      semOutland: false,
      nivelMin: '', nivelMax: '',
      potenciaMin: r.potMin > 0 ? r.potMin : '', potenciaMax: '',
      ivMin: r.ivMin > 0 ? r.ivMin : '', ivMax: '',
      qualidadeMin: '', qualidadeMax: '',
      notaMin: '', notaMax: '',
      pagina: 0,
      especieId: r.alvo,
    };
  }

  // A vitrine de pokemon é a mesma que a tela do RMT usa: com o RMT aberto na conta principal, o
  // sniper não pede pokemon (a resposta poderia cruzar com a do jogador). Item não tem esse problema.
  const rmtAbertoNaTela = () => !!document.querySelector('#cm-lista');

  function tique() {
    // A lista de nomes vai ao app mesmo com o sniper desligado: é com ela que a 1ª regra é criada.
    if (!catalogoEnviado && mi.pronto()) {
      let principal = false;
      try { principal = app().souPrincipal() === true; } catch (_) {}
      if (principal) enviarCatalogo();
    }
    const c = config();
    if (!c || !c.ligado || !mi.pronto()) return;
    const agora = Date.now();
    if (emVoo && agora - emVoo.enviadoEm > ESPERA_RESPOSTA_MS) emVoo = null;
    for (const r of c.regras || []) {
      if (!r.ativo || (r.restante ?? 0) - reservadoNaRegra(r.id) <= 0) continue;
      if (agora - (ultimaLeitura[r.id] || 0) < CICLO_MS) continue;
      if (fila.some((x) => x.id === r.id)) continue;
      fila.push(r);
    }
    if (emVoo || !fila.length || agora - ultimoEnvio < ENTRE_PEDIDOS_MS) return;
    const r = fila.shift();
    if (r.tipo === 'pokemon' && rmtAbertoNaTela()) return; // tenta no próximo ciclo
    if (!mi.enviar(pedidoDaRegra(r))) return;
    emVoo = { regra: r, enviadoEm: agora };
    ultimoEnvio = agora;
    ultimaLeitura[r.id] = agora;
  }

  function aoResponderMercado(m) {
    if (!emVoo || m.t !== 'market') return;
    const r = emVoo.regra;
    const minha = r.tipo === 'item'
      ? m.aba === 'item' && Number(m.itemId) === Number(r.alvo) && m.moeda === r.moeda
      : m.aba === 'vitrine';
    if (!minha) return;
    emVoo = null;
    avaliar(r, Array.isArray(m.linhas) ? m.linhas : []);
    // A lista de pokemon pedida pelo sniper não vai para a tela do jogo.
    return r.tipo === 'pokemon' ? 'engolir' : undefined;
  }

  function especieDo(a) {
    const f = a.ficha || {};
    return Number(f.speciesId ?? f.pokeId ?? f.especieId ?? a.speciesId ?? NaN);
  }

  function avaliar(r, linhas) {
    const c = config();
    if (!c || !c.ligado) return;
    const candidatos = linhas
      .filter((a) => a && a.id != null && !agendados.has(a.id) && !pendentes.has(a.id))
      .filter((a) => a.moeda === r.moeda && Number(a.preco) > 0 && Number(a.preco) <= r.max)
      .filter((a) => a.vendedorId == null || a.vendedorId !== mi.eu.id)
      .filter((a) => r.tipo !== 'pokemon' || (
        (!Number.isFinite(especieDo(a)) || especieDo(a) === Number(r.alvo))
        && !(r.shiny === 'sim' && a.ficha && a.ficha.shiny === false)
        && !(r.shiny === 'nao' && a.ficha && a.ficha.shiny === true)
      ))
      .sort((x, y) => Number(x.preco) - Number(y.preco));
    for (const a of candidatos) {
      let restante = (r.restante ?? 0) - reservadoNaRegra(r.id);
      if (restante <= 0) break;
      const preco = Number(a.preco);
      let qtd = r.tipo === 'item' ? Math.min(Number(a.qtd) || 1, restante) : 1;
      const saldo = (r.moeda === 'orb' ? mi.eu.orbs : mi.eu.gold) - reservado(r.moeda);
      const doDia = (r.moeda === 'orb' ? c.restanteOrbHoje : c.restanteGoldHoje) - reservado(r.moeda);
      qtd = Math.min(qtd, Math.floor(Math.min(saldo, doDia) / preco));
      if (qtd < 1) {
        avisar('semSaldo', { regra: r.id, nome: r.nome, preco, moeda: r.moeda });
        continue;
      }
      agendar(r, a, qtd, preco);
    }
  }

  function agendar(r, a, qtd, preco) {
    const libera = Number(a.compravelEm) || 0;
    const espera = libera ? libera + mi.desvio - Date.now() + MARGEM_LIBERACAO_MS : 0;
    if (espera > ANTECEDENCIA_MAX_MS) return;
    const item = { regra: r, qtd, preco, moeda: a.moeda, custo: qtd * preco };
    item.timer = setTimeout(() => comprar(a.id, item), Math.max(0, espera));
    agendados.set(a.id, item);
    avisar('viu', { regra: r.id, nome: r.nome, qtd, preco, moeda: a.moeda, liberaEmS: Math.max(0, Math.round(espera / 1000)) });
  }

  function comprar(id, item) {
    agendados.delete(id);
    const c = config();
    // Desligou o sniper, apagou ou pausou a regra enquanto o anúncio estava retido: não compra.
    const regra = c && c.ligado && (c.regras || []).find((x) => x.id === item.regra.id && x.ativo);
    if (!regra) return;
    const ok = mi.enviar({ t: 'market.comprar', id, qtd: item.qtd, preco: item.preco, moeda: item.moeda });
    if (!ok) return;
    pendentes.set(id, { ...item, ate: Date.now() + 20_000 });
    avisar('pediu', { regra: item.regra.id, nome: item.regra.nome, qtd: item.qtd, preco: item.preco, moeda: item.moeda });
  }

  // ---------------------------------------------------------------- resultado da compra
  function aoEventoDeBatalha(m) {
    if (m.t !== 'batalha' || !Array.isArray(m.ev)) return;
    for (const e of m.ev) {
      if (!e) continue;
      const id = e.id != null ? Number(e.id) : null;
      const p = id != null ? (pendentes.get(id) ?? pendentes.get(String(id))) : null;
      if (!p) continue;
      if (e.k === 'marketComprado' || (e.k === 'mktSorteio' && e.fase === 'fim' && e.ok)) {
        pendentes.delete(id);
        pendentes.delete(String(id));
        avisar('levou', { regra: p.regra.id, nome: p.regra.nome, qtd: p.qtd, preco: p.preco, moeda: p.moeda, custo: p.custo });
      } else if (e.k === 'mktSorteio' && e.fase === 'fim') {
        pendentes.delete(id);
        pendentes.delete(String(id));
        avisar(e.perdeu ? 'perdeu' : 'recusado', { regra: p.regra.id, nome: p.regra.nome, motivo: String(e.msg || '') });
      }
    }
  }

  // Pedido sem resposta em 20 s: libera a reserva (o próximo ciclo olha de novo).
  function limparPendentes() {
    const agora = Date.now();
    for (const [id, p] of pendentes) if (agora > p.ate) pendentes.delete(id);
  }

  // ---------------------------------------------------------------- catálogo para o app
  // Os nomes de itens e espécies que o app mostra na hora de criar uma regra.
  async function enviarCatalogo() {
    catalogoEnviado = true;
    const lista = [];
    const juntar = async (url, chave, tipo, idDe, nomeDe) => {
      try {
        const r = await fetch(url, { cache: 'no-store' });
        if (!r.ok) return;
        const d = await r.json();
        for (const x of d[chave] || []) {
          const id = idDe(x);
          const nome = nomeDe(x);
          if (id != null && nome) lista.push({ t: tipo, id, n: String(nome) });
        }
      } catch (_) {}
    };
    await juntar('/assets/items.json', 'items', 'i', (x) => x.id, (x) => x.name);
    await juntar('/assets/creatures.json', 'creatures', 'p', (x) => x.pokeId, (x) => x.name);
    await juntar('/assets/creatures-novos.json', 'creatures', 'p', (x) => x.pokeId, (x) => x.name);
    await juntar('/assets/creatures-outland-novos.json', 'creatures', 'p', (x) => x.pokeId, (x) => x.name);
    if (lista.length) {
      try { app().sniperCatalogo(JSON.stringify(lista)); } catch (_) {}
    } else {
      catalogoEnviado = false;
    }
  }

  mi.ouvir((m) => {
    aoEventoDeBatalha(m);
    return aoResponderMercado(m);
  });
  setInterval(() => { limparPendentes(); tique(); }, 1000);
})();
