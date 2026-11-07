# MultiIdle — instruções para o Claude

## Publicar o APK (atualização automática)

O dono do projeto **autoriza** publicar o APK novo sempre que **pedir** (ex.: "publica o apk",
"atualiza meu apk", ou ao pedir uma correção que ele queira receber no celular).

Publicar = o app no celular dele se atualiza sozinho (lê `apk/versao.json`). Por isso:

- **Só publique quando ele pedir.** Correções de código podem ser commitadas e enviadas sem
  publicar; nesse caso, avise que ainda não foram publicadas.
- Passos:
  1. Subir `versionCode` (+1) e `versionName` em `app/build.gradle.kts`.
  2. Rodar `bash scripts/publicar.sh` (compila e atualiza `apk/MultiIdle.apk` e `apk/versao.json`).
  3. Commitar `app/build.gradle.kts` e a pasta `apk/` e dar push no branch de desenvolvimento.
- Nunca publique um APK que não compilou ou com mudanças que não foram testadas de alguma forma.

## Idioma

Responder ao usuário em português do Brasil.
