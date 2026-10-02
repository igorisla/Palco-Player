"""Copia a página do Palco Player (web/palco-player.html) para dentro do app Android,
acrescentando o cabeçalho HTML que o Claude acrescenta quando publica a página."""
import pathlib
raiz = pathlib.Path(__file__).resolve().parent.parent
corpo = (raiz / "web" / "palco-player.html").read_text(encoding="utf-8")
topo = ('<!doctype html><html lang="pt-BR"><head><meta charset="utf-8">'
        '<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">'
        '<style>:root{padding-top:env(safe-area-inset-top,0px);padding-bottom:env(safe-area-inset-bottom,0px)}'
        'html,body{margin:0}img{max-width:100%}[hidden]{display:none!important}</style></head><body>')
destino = raiz / "app" / "src" / "main" / "assets" / "www" / "index.html"
destino.parent.mkdir(parents=True, exist_ok=True)
destino.write_text(topo + corpo + "</body></html>", encoding="utf-8")
print("ok:", destino)
