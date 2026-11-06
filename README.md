# MultiIdle

App Android para jogar **várias contas do [pokeidle.io](https://pokeidle.io) ao mesmo tempo** no celular
(até 4, que é o limite do jogo), no estilo do IdleDesk.

## Instalar

1. No celular, baixe [`apk/MultiIdle.apk`](apk/MultiIdle.apk) (botão "Download raw file" no GitHub).
2. Abra o arquivo e permita "instalar apps desconhecidos" para o navegador/gerenciador de arquivos.
3. Abra o **MultiIdle** e entre em cada conta (na primeira vez).

As versões novas instalam por cima e mantêm os logins (o APK sempre sai com a mesma assinatura).

## Como funciona

- Cada conta roda num **perfil separado** do WebView: login, cookies e dados próprios. Os logins ficam salvos.
- **▦ Todas**: mostra as contas lado a lado (toque na faixa com o nome para abrir uma em tela cheia).
- **Abas com o nome**: abre aquela conta em tela cheia. As outras continuam rodando por trás.
- **Botão Voltar**: volta para a grade; na grade, só minimiza o app (não fecha as contas).
- **Menu ⋮**
  - Recarregar uma conta ou todas
  - Quantidade de contas (1 a 4) e nome de cada uma
  - *Grade em miniatura*: cada conta é desenhada como a tela inteira reduzida (padrão). Desligado,
    a página se ajusta ao tamanho do quadradinho.
  - *Manter tela acesa*
  - *Rodar com a tela apagada*: o jogo continua se achando visível com a tela apagada ou o app
    minimizado (sem isso o navegador interno desacelera a página e a conexão cai). Pede para o app
    ficar fora da otimização de bateria.
  - *Rodar em segundo plano*: notificação fixa que impede o Android de matar o app e de pôr a CPU/Wi-Fi para dormir.
  - *Escurecer tela*: tela preta com brilho mínimo, as contas continuam rodando (toque duas vezes para voltar).
  - *Encerrar tudo*

### Observações

- Precisa do **Android System WebView / Chrome atualizado** (perfis separados). Se for antigo, o app avisa
  e só deixa 1 conta.
- **Login com Google**: o Google não aceita login dentro de apps, então ao tocar em "Continuar com Google"
  o MultiIdle abre o login no **navegador do celular** e a volta é entregue à mesma conta do app.
  Para isso funcionar, permita uma vez: Configurações → Apps → MultiIdle → **Abrir por padrão** →
  Adicionar link → marque **pokeidle.io** (o app mostra esse atalho na primeira vez).
  Efeito colateral: links `pokeidle.io/app` clicados em outros apps passam a abrir no MultiIdle.
- O jogo conta quantas contas entram do mesmo aparelho (`id-dispositivo`). O app faz todas as contas
  usarem o **mesmo** id, para o jogo ver corretamente que são do mesmo celular, como manda o limite.
- Para rodar a noite toda, deixe o MultiIdle **sem otimização de bateria**
  (Configurações → Apps → MultiIdle → Bateria → Sem restrições). Algumas marcas (Xiaomi, Samsung...)
  fecham apps em segundo plano mesmo com a notificação.

## Atualização automática

O app consulta [`apk/versao.json`](apk/versao.json) ao abrir e a cada 2 h. Quando há versão nova,
baixa o APK (conferindo o SHA-256) e instala quando o app está aberto na tela — nunca com a tela
apagada, para não derrubar as contas no meio do caminho. A primeira atualização pede um toque em
"Atualizar" e a permissão "Instalar apps desconhecidos"; depois, no Android 12+, é sozinha.
Menu ⋮ → *Procurar atualização* força a busca.

Para publicar uma versão: suba `versionCode`/`versionName` em `app/build.gradle.kts`, rode
`scripts/publicar.sh` e faça commit/push de `apk/`.

## Compilar

Requer JDK 17+ e Android SDK (platform 35):

```
./gradlew assembleRelease
# saída: app/build/outputs/apk/release/app-release.apk
```
