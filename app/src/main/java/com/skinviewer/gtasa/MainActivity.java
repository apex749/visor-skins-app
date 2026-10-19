package com.skinviewer.gtasa;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final int REQ_FILE = 1;
    private static final int REQ_TREE = 2;

    private WebView web;
    private ValueCallback<Uri[]> fileCb;
    private final List<Uri> docs = Collections.synchronizedList(new ArrayList<Uri>());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.parseColor("#252A31"));
        getWindow().setNavigationBarColor(Color.parseColor("#1C1F24"));

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#1C1F24"));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (!HOST.equals(u.getHost())) return null;
                String p = u.getPath();
                try {
                    if (p == null || "/".equals(p) || "/index.html".equals(p)) {
                        return reply("text/html", "UTF-8", getAssets().open("index.html"));
                    }
                    if (p.startsWith("/doc/")) {
                        int id = Integer.parseInt(p.substring(5));
                        InputStream in = getContentResolver().openInputStream(docs.get(id));
                        if (in != null) return reply("application/octet-stream", null, in);
                    }
                } catch (Exception e) {
                    // cae al 404
                }
                return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                        new HashMap<String, String>(), new ByteArrayInputStream(new byte[0]));
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !HOST.equals(r.getUrl().getHost());
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                if (p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                try {
                    startActivityForResult(Intent.createChooser(i, "Elegir archivos"), REQ_FILE);
                } catch (Exception e) {
                    fileCb = null;
                    return false;
                }
                return true;
            }
        });

        web.loadUrl("https://" + HOST + "/index.html");
    }

    private WebResourceResponse reply(String mime, String enc, InputStream in) {
        Map<String, String> h = new HashMap<String, String>();
        h.put("Access-Control-Allow-Origin", "*");
        return new WebResourceResponse(mime, enc, 200, "OK", h, in);
    }

    private void toast(final String m) {
        runOnUiThread(new Runnable() {
            public void run() { Toast.makeText(MainActivity.this, m, Toast.LENGTH_LONG).show(); }
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_FILE) {
            Uri[] out = null;
            if (res == RESULT_OK && data != null) {
                ClipData cd = data.getClipData();
                if (cd != null) {
                    out = new Uri[cd.getItemCount()];
                    for (int i = 0; i < out.length; i++) out[i] = cd.getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    out = new Uri[]{data.getData()};
                }
            }
            if (fileCb != null) {
                fileCb.onReceiveValue(out);
                fileCb = null;
            }
        } else if (req == REQ_TREE && res == RESULT_OK && data != null && data.getData() != null) {
            final Uri tree = data.getData();
            toast("Leyendo carpeta…");
            new Thread(new Runnable() {
                public void run() { scanTree(tree); }
            }).start();
        }
    }

    private void scanTree(Uri tree) {
        JSONArray arr = new JSONArray();
        try {
            String rootId = DocumentsContract.getTreeDocumentId(tree);
            String name = rootId;
            int c = name.lastIndexOf(':');
            if (c >= 0) name = name.substring(c + 1);
            int sl = name.lastIndexOf('/');
            if (sl >= 0) name = name.substring(sl + 1);
            if (name.isEmpty()) name = "carpeta";
            walk(tree, rootId, name, arr, 0);
        } catch (Exception e) {
            toast("Error leyendo la carpeta: " + e.getMessage());
            return;
        }
        final String js = "window.onNativeFolder(" + arr.toString() + ")";
        runOnUiThread(new Runnable() {
            public void run() { web.evaluateJavascript(js, null); }
        });
        toast(arr.length() + " archivos .dff/.txd encontrados");
    }

    private void walk(Uri tree, String docId, String path, JSONArray out, int depth) throws Exception {
        if (depth > 8) return;
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        List<String[]> dirs = new ArrayList<String[]>();
        Cursor c = getContentResolver().query(kids, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE}, null, null, null);
        if (c == null) return;
        try {
            while (c.moveToNext()) {
                String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                long size = c.getLong(3);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    dirs.add(new String[]{id, name});
                    continue;
                }
                String l = name.toLowerCase(Locale.ROOT);
                if (l.endsWith(".dff") || l.endsWith(".txd")) {
                    Uri u = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                    int idx;
                    synchronized (docs) {
                        docs.add(u);
                        idx = docs.size() - 1;
                    }
                    JSONObject o = new JSONObject();
                    o.put("id", idx);
                    o.put("name", name);
                    o.put("size", size);
                    o.put("path", path + "/" + name);
                    out.put(o);
                }
            }
        } finally {
            c.close();
        }
        for (String[] d : dirs) walk(tree, d[0], path + "/" + d[1], out, depth + 1);
    }

    private class Bridge {
        @JavascriptInterface
        public void pickFolder() {
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE);
                    } catch (Exception e) {
                        toast("No se pudo abrir el selector de carpetas");
                    }
                }
            });
        }

        @JavascriptInterface
        public void saveImage(String b64, String name) {
            try {
                byte[] data = Base64.decode(b64, Base64.DEFAULT);
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/VisorSkins");
                Uri u = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                OutputStream o = getContentResolver().openOutputStream(u);
                try { o.write(data); } finally { o.close(); }
                toast("Render guardado en Imágenes/VisorSkins");
            } catch (Exception e) {
                toast("No se pudo guardar: " + e.getMessage());
            }
        }
    }
}
