// MultiIdle — botão de silenciar.
//
// Os sons do pokeidle são todos <audio> (captura, shiny, drop raro, mensagem…). Com o app no
// mudo, o play() deles simplesmente não acontece: nem som, nem "foco de áudio" — que era o que
// fazia o Android baixar ou pausar a música do Spotify a cada captura.
(() => {
  if (window.__multiIdleSilenciar) return;
  window.__multiIdleSilenciar = true;
  const play = HTMLMediaElement.prototype.play;
  const mudo = () => {
    try { return window.MultiIdleApp.mudo() === true; } catch (_) { return false; }
  };
  HTMLMediaElement.prototype.play = function () {
    if (mudo()) return Promise.resolve();
    return play.apply(this, arguments);
  };
})();
