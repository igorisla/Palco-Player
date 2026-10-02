# Palco Player — app Android

Player de pistas e vídeos de karaokê para shows ao vivo: dois decks com crossfader, telão,
repertório por local, tom e andamento salvos por música.

A tela do app é a mesma página do Palco Player do PC (`web/palco-player.html`).
Cada alteração enviada para este repositório monta um novo instalador automaticamente
(aba **Actions**) e publica em **Releases** o arquivo `PalcoPlayer.apk`.

## Instalar no tablet
1. No tablet, abra a página **Releases** deste repositório e toque em `PalcoPlayer.apk`.
2. Permita "instalar apps desconhecidos" para o navegador quando o Android pedir.
3. Abra o Palco Player, toque em **Conectar pasta de músicas** e escolha a pasta Karaoke.
   O app lembra dessa pasta.

## Levar os repertórios do PC para o tablet
No Palco Player do PC, toque em **Enviar para o tablet**. Coloque o arquivo
`palco-repertorios.json` dentro da pasta Karaoke do tablet. Ao abrir, o app importa sozinho.
