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
  function aplicar() {
    // No início do documento o <html> pode ainda não existir.
    const raiz = document.documentElement;
    if (!raiz) return;
    const celular = raiz.classList.contains('mobile');
    if (!celular) {
      if (nosso) { raiz.style.removeProperty('--vh'); raiz.style.removeProperty('--vw'); nosso = false; }
      return;
    }
    const vv = window.visualViewport;
    const h = vv ? vv.height : window.innerHeight;
    const w = vv ? vv.width : window.innerWidth;
    if (!(h > 0 && w > 0)) return;
    raiz.style.setProperty('--vh', `${h / 100}px`);
    raiz.style.setProperty('--vw', `${w / 100}px`);
    nosso = true;
  }
  window.addEventListener('resize', aplicar);
  window.visualViewport?.addEventListener('resize', aplicar);
  document.addEventListener('DOMContentLoaded', aplicar);
  // A classe `mobile` chega depois da carga (mobile.mjs): observa o <html> para pegar na hora.
  const observar = () => {
    if (!document.documentElement) return false;
    new MutationObserver(aplicar).observe(document.documentElement, { attributes: true, attributeFilter: ['class'] });
    aplicar();
    return true;
  };
  if (!observar()) document.addEventListener('readystatechange', function uma() {
    if (observar()) document.removeEventListener('readystatechange', uma);
  });
  setInterval(aplicar, 5000);
})();
