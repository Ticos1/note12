# MultiIdle no PC (Brave)

`MultiIdle-PC.bat` abre as contas do pokeidle.io no **Brave**, cada uma numa janela própria e
isolada (login separado, como navegadores diferentes), lado a lado. Não instala nada e não mexe
no seu Brave normal.

1. Baixe o `MultiIdle-PC.bat` e salve na Área de Trabalho.
   (Se o PC bloquear o download: abra o Bloco de Notas, cole o conteúdo do arquivo e salve como
   `MultiIdle-PC.bat`, com "Tipo: Todos os arquivos".)
2. Dê dois cliques. Na primeira vez, entre em cada conta (Google funciona normal).
   Os logins ficam salvos em `%LOCALAPPDATA%\MultiIdle`.
3. Para mudar a quantidade de contas, edite a linha `set CONTAS=3`.

### Extensões

Cada janela é um perfil separado do Brave: **instale a extensão uma vez em cada janela** (pela
Chrome Web Store, que o Brave aceita). Elas ficam salvas junto com o login daquela conta.
O padrão (`set MODO=normal`) abre janelas comuns, com a barra de ferramentas e os ícones das
extensões. `set MODO=app` abre janelas limpas, sem barra — as extensões continuam rodando, mas
não dá para clicar nelas nem instalar novas.

As janelas abrem com opções do Brave que impedem que ele desacelere janelas escondidas ou
minimizadas, então dá para deixar o jogo atrás das janelas de trabalho.

**A mesma conta não roda em dois lugares**: abrir no PC derruba aquela conta no celular (e
vice-versa). Feche no celular antes.
