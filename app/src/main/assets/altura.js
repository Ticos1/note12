// MultiIdle — a altura de tela certa para o CSS do jogo.
//
// O estilo do pokeidle dimensiona os painéis com `calc(100 * var(--vh, 1vh))` (o histórico de
// CAPTURAS, por exemplo: `min(440px, 100vh - 140px)`). No celular o jogo não define `--vh` e cai
// no `1vh` do navegador — e dentro do app o `vh` da WebView sai bem menor que a tela, o que
// espremia o painel de Capturas em ~120 px. Aqui `--vh`/`--vw` recebem a medida de verdade
// (a mesma `visualViewport` que o jogo usa para a altura do app). No desktop, onde o próprio jogo
// cuida dessas variáveis (escala-ui.js), nada é tocado.
(() => {
  if (window.__multiIdleAltura) return;
  window.__multiIdleAltura = true;
  let nosso = false;
  // Só escreve quando muda: o observador do `style` chamaria `aplicar` de novo em laço.
  function definir(raiz, nome, valor) {
    if (raiz.style.getPropertyValue(nome) !== valor) raiz.style.setProperty(nome, valor);
  }
  function aplicar() {
    // No início do documento o <html> pode ainda não existir.
    const raiz = document.documentElement;
    if (!raiz) return;
    const celular = raiz.classList.contains('mobile');
    if (!celular) {
      if (nosso) {
        raiz.style.removeProperty('--vh'); raiz.style.removeProperty('--vw'); raiz.style.removeProperty('--m-alt');
        nosso = false;
      }
      return;
    }
    // A altura vem do APP (o tamanho da WebView em px de CSS), não da `visualViewport`: dentro do
    // app a WebView às vezes informa uma área visível bem menor que ela mesma (a Conta 4 chegou a
    // usar só metade da tela, até em tela cheia a 100%). O teclado continua certo: o app encolhe a
    // WebView inteira quando ele abre. Sem o app (navegador comum), vale a `visualViewport`.
    const vv = window.visualViewport;
    let h = vv ? vv.height : window.innerHeight;
    let w = vv ? vv.width : window.innerWidth;
    try {
      const [aw, ah] = String(window.MultiIdleApp.tamanhoCss()).split(',').map(Number);
      // Conferência: a largura do app tem de bater com a da página (a largura nunca veio errada).
      if (aw > 0 && ah > 0 && Math.abs(window.innerWidth - aw) <= aw * 0.15) { w = aw; h = ah; }
    } catch (_) {}
    if (!(h > 0 && w > 0)) return;
    definir(raiz, '--vh', `${h / 100}px`);
    definir(raiz, '--vw', `${w / 100}px`);
    // `--m-alt` é a altura do app inteiro no celular; o mobile.mjs escreve a da `visualViewport`
    // a cada `resize`. O observador do `style` (abaixo) devolve a altura certa logo depois.
    definir(raiz, '--m-alt', `${Math.round(h)}px`);
    nosso = true;
    reescreverFolhas();
  }

  // Nem todo painel passa pelo `--vh`: o mobile.css escreve `vh` PURO em vários lugares — o
  // histórico de CAPTURAS no celular é `height: min(400px, 54vh)`, que vence a regra do estilo.css
  // e continuava espremido com o `vh` errado da WebView. Então cada `Nvh`/`Nvw` puro das folhas do
  // jogo vira `calc(N * var(--vh, 1vh))` (o fallback mantém o valor original sem a variável).
  // Regras que já usam `var(--vh…)`/`var(--vw…)` ficam como estão.
  const feitas = new WeakSet();
  let qtdFolhas = -1;
  const UNIDADE = /(^|[^\w.-])(-?(?:\d+\.?\d*|\.\d+))(?:[dsl])?(vh|vw)\b/g;
  function trocar(valor) {
    if (!/v[hw]\b/.test(valor) || /var\(--v[hw]/.test(valor)) return null;
    let mudou = false;
    const novo = valor.replace(UNIDADE, (_, antes, n, u) => {
      mudou = true;
      return `${antes}calc(${n} * var(--${u}, 1${u}))`;
    });
    return mudou ? novo : null;
  }
  function reescreverRegras(regras) {
    for (const r of regras) {
      const st = r.style;
      if (st && /v[hw]\b/.test(st.cssText)) {
        for (let i = 0; i < st.length; i++) {
          const prop = st[i];
          const novo = trocar(st.getPropertyValue(prop));
          if (novo) st.setProperty(prop, novo, st.getPropertyPriority(prop));
        }
      }
      let filhas = null;
      try { filhas = r.cssRules; } catch (_) {}
      if (filhas && filhas.length) reescreverRegras(filhas);
    }
  }
  function reescreverFolhas() {
    const folhas = document.styleSheets;
    if (!folhas || folhas.length === qtdFolhas) return;
    let pendente = false;
    for (const f of folhas) {
      if (feitas.has(f)) continue;
      let regras;
      try { regras = f.cssRules; } catch (e) {
        // SecurityError = folha de outra origem (não dá para ler, nunca); o resto = ainda carregando.
        if (e && e.name === 'SecurityError') feitas.add(f); else pendente = true;
        continue;
      }
      if (!regras) { pendente = true; continue; } // ainda carregando
      try { reescreverRegras(regras); } catch (e) { console.warn('[MultiIdle altura]', e); }
      feitas.add(f);
    }
    if (!pendente) qtdFolhas = folhas.length;
  }
  window.addEventListener('resize', aplicar);
  window.visualViewport?.addEventListener('resize', aplicar);
  document.addEventListener('DOMContentLoaded', aplicar);
  // A classe `mobile` chega depois da carga (mobile.mjs): observa o <html> para pegar na hora.
  const observar = () => {
    if (!document.documentElement) return false;
    new MutationObserver(aplicar).observe(document.documentElement, { attributes: true, attributeFilter: ['class', 'style'] });
    aplicar();
    return true;
  };
  if (!observar()) document.addEventListener('readystatechange', function uma() {
    if (observar()) document.removeEventListener('readystatechange', uma);
  });
  window.addEventListener('load', aplicar);
  setInterval(aplicar, 5000);
})();
