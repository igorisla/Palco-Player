package br.sanmartin.palcoplayer;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.speech.RecognizerIntent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Palco Player para Android.
 * A tela é a mesma página do Palco Player do PC (assets/www/index.html), aberta num WebView.
 * Este código só faz o que o navegador não deixa: lembrar da pasta de músicas para sempre
 * e entregar os arquivos dela para a página tocar.
 */
public class MainActivity extends Activity {

    static final String HOST = "appassets.androidplatform.net";
    static final String BASE = "https://" + HOST;
    static final int REQ_TREE = 42;
    static final int REQ_VOICE = 43;
    static final int REQ_MIC = 44;
    PermissionRequest pendingMic;

    WebView web;
    SharedPreferences prefs;
    volatile String filesJson = "{\"root\":\"\",\"files\":[]}";
    final ExecutorService exec = Executors.newSingleThreadExecutor();
    View fullView;
    WebChromeClient.CustomViewCallback fullCallback;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("palco", MODE_PRIVATE);

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#10131A"));
        setContentView(web);

        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);

        web.addJavascriptInterface(new Bridge(), "PalcoAndroid");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                return intercept(req);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (ActivityNotFoundException ignored) { }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            // microfone para "Ouvir o teclado"
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    boolean wantsMic = false;
                    for (String r : request.getResources())
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) wantsMic = true;
                    if (!wantsMic) { request.deny(); return; }
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                    } else {
                        pendingMic = request;
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                    }
                });
            }

            // tela cheia do telão
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (fullView != null) { callback.onCustomViewHidden(); return; }
                fullView = view;
                fullCallback = callback;
                ((FrameLayout) getWindow().getDecorView()).addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                hideBars();
            }

            @Override
            public void onHideCustomView() {
                if (fullView == null) return;
                ((FrameLayout) getWindow().getDecorView()).removeView(fullView);
                fullView = null;
                if (fullCallback != null) fullCallback.onCustomViewHidden();
                fullCallback = null;
                hideBars();
            }
        });

        if (saved != null) web.restoreState(saved);
        else web.loadUrl(BASE + "/assets/www/index.html");
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req != REQ_MIC || pendingMic == null) return;
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED)
            pendingMic.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
        else pendingMic.deny();
        pendingMic = null;
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideBars();
    }

    @SuppressWarnings("deprecation")
    void hideBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    // O botão "voltar" não fecha o app no meio do show.
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (fullView != null) {
            web.evaluateJavascript("document.exitFullscreen&&document.exitFullscreen()", null);
            return;
        }
        js("window.toast&&toast('Para sair do Palco Player use o botão Início do tablet')");
    }

    // ---------- pasta de músicas ----------

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_VOICE) {
            if (res != RESULT_OK || data == null) return;
            ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (r != null && !r.isEmpty())
                js("window.onPalcoVoice&&window.onPalcoVoice(" + JSONObject.quote(r.get(0)) + ")");
            return;
        }
        if (req != REQ_TREE) return;
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri tree = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException e) {
            // segue mesmo assim; nesta sessão a leitura funciona
        }
        prefs.edit().putString("tree", tree.toString()).apply();
        scan(tree);
    }

    void openPicker() {
        runOnUiThread(() -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            try { startActivityForResult(i, REQ_TREE); }
            catch (ActivityNotFoundException e) { jsError("Este tablet não abriu a escolha de pastas"); }
        });
    }

    // ---------- busca por voz ----------
    void openVoice() {
        runOnUiThread(() -> {
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR");
            i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Fale o cantor ou o nome da música");
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            try { startActivityForResult(i, REQ_VOICE); }
            catch (ActivityNotFoundException e) { jsError("Este tablet não tem o reconhecimento de voz do Google"); }
        });
    }

    void scan(Uri tree) {
        exec.submit(() -> {
            try {
                String rootDoc = DocumentsContract.getTreeDocumentId(tree);
                String rootName = docName(tree, rootDoc);
                if (rootName == null || rootName.isEmpty()) rootName = "Pasta";
                JSONArray arr = new JSONArray();
                walk(tree, rootDoc, rootName, arr, 0);
                JSONObject o = new JSONObject();
                o.put("root", rootName);
                o.put("files", arr);
                filesJson = o.toString();
                js("window.onPalcoFolder&&window.onPalcoFolder()");
            } catch (SecurityException e) {
                prefs.edit().remove("tree").apply();
                jsError("O tablet não deixou ler a pasta. Toque em Conectar pasta e escolha de novo.");
            } catch (Exception e) {
                jsError("Não foi possível ler a pasta: " + e.getMessage());
            }
        });
    }

    String docName(Uri tree, String docId) {
        Uri u = DocumentsContract.buildDocumentUriUsingTree(tree, docId);
        try (Cursor c = getContentResolver().query(u,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) { }
        return null;
    }

    static boolean wanted(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".mp3") || n.endsWith(".wav") || n.endsWith(".wave") || n.endsWith(".mp4")
                || n.endsWith(".m4v") || n.endsWith(".webm") || n.endsWith(".mov") || n.endsWith(".mkv")
                || n.equals("palco-repertorios.json");
    }

    void walk(Uri tree, String docId, String path, JSONArray out, int depth) throws Exception {
        if (depth > 10) return;
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        try (Cursor c = getContentResolver().query(kids, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                if (name == null || name.startsWith(".")) continue;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    walk(tree, id, path + "/" + name, out, depth + 1);
                } else if (wanted(name)) {
                    Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                    JSONObject f = new JSONObject();
                    f.put("path", path + "/" + name);
                    f.put("name", name);
                    f.put("url", BASE + "/media/" + Uri.encode(doc.toString()));
                    out.put(f);
                }
            }
        }
    }

    // ---------- servir a página e as músicas ----------

    WebResourceResponse intercept(WebResourceRequest req) {
        Uri u = req.getUrl();
        if (!HOST.equals(u.getHost())) return null;
        String enc = u.getEncodedPath();
        if (enc == null) return null;
        try {
            if (enc.startsWith("/assets/")) {
                String a = u.getPath().substring("/assets/".length());
                InputStream in = getAssets().open(a);
                String mime = mimeFor(a);
                WebResourceResponse r = new WebResourceResponse(mime, mime.startsWith("text/") ? "utf-8" : null, in);
                Map<String, String> h = new HashMap<>();
                h.put("Cache-Control", "no-cache");
                r.setResponseHeaders(h);
                return r;
            }
            if (enc.startsWith("/media/")) {
                Uri doc = Uri.parse(Uri.decode(enc.substring("/media/".length())));
                return serveMedia(doc, header(req, "Range"));
            }
        } catch (Exception e) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", new HashMap<>(), null);
        }
        return null;
    }

    static String header(WebResourceRequest req, String name) {
        for (Map.Entry<String, String> e : req.getRequestHeaders().entrySet())
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
        return null;
    }

    WebResourceResponse serveMedia(Uri doc, String range) throws IOException {
        String mime = getContentResolver().getType(doc);
        if (mime == null) mime = mimeFor(doc.toString());
        ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(doc, "r");
        if (pfd == null) throw new IOException("sem arquivo");
        long size = pfd.getStatSize();
        FileInputStream fis = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
        Map<String, String> h = new HashMap<>();
        h.put("Accept-Ranges", "bytes");
        h.put("Access-Control-Allow-Origin", "*");
        h.put("Cache-Control", "no-store");

        if (size < 0) {
            return new WebResourceResponse(mime, null, 200, "OK", h, fis);
        }
        long start = 0, end = size - 1;
        boolean partial = false;
        if (range != null && range.startsWith("bytes=")) {
            String spec = range.substring(6).split(",")[0].trim();
            int dash = spec.indexOf('-');
            if (dash >= 0) {
                String a = spec.substring(0, dash).trim(), b = spec.substring(dash + 1).trim();
                if (a.isEmpty() && !b.isEmpty()) {
                    start = Math.max(0, size - Long.parseLong(b));
                } else {
                    if (!a.isEmpty()) start = Long.parseLong(a);
                    if (!b.isEmpty()) end = Long.parseLong(b);
                }
                partial = true;
            }
        }
        if (end >= size) end = size - 1;
        if (start > end) start = end < 0 ? 0 : end;
        long len = Math.max(0, end - start + 1);
        if (start > 0) fis.getChannel().position(start);
        h.put("Content-Length", String.valueOf(len));
        if (partial) h.put("Content-Range", "bytes " + start + "-" + end + "/" + size);
        return new WebResourceResponse(mime, null, partial ? 206 : 200, partial ? "Partial Content" : "OK",
                h, new Bounded(fis, len));
    }

    static String mimeFor(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".html")) return "text/html";
        if (n.endsWith(".js")) return "text/javascript";
        if (n.endsWith(".css")) return "text/css";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".wav") || n.endsWith(".wave")) return "audio/wav";
        if (n.endsWith(".mp4") || n.endsWith(".m4v")) return "video/mp4";
        if (n.endsWith(".webm")) return "video/webm";
        if (n.endsWith(".mov")) return "video/quicktime";
        if (n.endsWith(".mkv")) return "video/x-matroska";
        return "application/octet-stream";
    }

    /** Entrega só o trecho pedido do arquivo (para pular para qualquer ponto da música). */
    static class Bounded extends InputStream {
        final InputStream in;
        long left;
        Bounded(InputStream in, long left) { this.in = in; this.left = left; }
        @Override public int read() throws IOException {
            if (left <= 0) return -1;
            int b = in.read();
            if (b >= 0) left--;
            return b;
        }
        @Override public int read(byte[] buf, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = in.read(buf, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }
        @Override public int available() throws IOException { return (int) Math.min(in.available(), left); }
        @Override public void close() throws IOException { in.close(); }
    }

    // ---------- ponte com a página ----------

    void js(String code) {
        web.post(() -> web.evaluateJavascript(code, null));
    }

    void jsError(String msg) {
        js("window.onPalcoFolderError&&window.onPalcoFolderError(" + JSONObject.quote(msg) + ")");
    }

    class Bridge {
        @JavascriptInterface public void pickFolder() { openPicker(); }

        @JavascriptInterface public void restore() {
            String t = prefs.getString("tree", null);
            if (t != null) scan(Uri.parse(t));
        }

        @JavascriptInterface public void rescan() {
            String t = prefs.getString("tree", null);
            if (t != null) scan(Uri.parse(t)); else openPicker();
        }

        @JavascriptInterface public String files() { return filesJson; }

        @JavascriptInterface public void voice() { openVoice(); }
    }

    @Override
    protected void onDestroy() {
        exec.shutdownNow();
        super.onDestroy();
    }
}
