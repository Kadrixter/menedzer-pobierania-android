package pl.kadrixter.pobieranie;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Silnik pobierania: kolejka, wznawianie (HTTP Range), nieskończone ponawianie błędów sieci. */
public class Downloader {
    public static final int QUEUED = 0, ACTIVE = 1, RETRY = 2, PAUSED = 3, DONE = 4, ERROR = 5, NEED_WEB = 6;
    public static final String[] STATUS_PL = {"W kolejce", "Pobieranie", "Ponawianie", "Wstrzymane", "Ukończone", "Błąd",
            "Wymaga przeklikania"};

    static final int MAX_PARALLEL = 3;
    static final int BACKOFF_MAX = 60;       // s
    static final int HARD_FAIL_LIMIT = 8;    // 404/403 itp.
    static final int BUF = 256 * 1024;
    static final String UA = "Mozilla/5.0 (Linux; Android) MenedzerPobierania/1.0";

    public static class Item {
        public String id, url, filename, mime, uri, etag, lastModified, error = "";
        public String ua, referer;   // ustawiane po przeklikaniu strony w apce
        public boolean web;          // używaj ciasteczek z wbudowanej przeglądarki
        public String origUrl;       // link wklejony przez użytkownika
        public int status = QUEUED;
        public long total = -1, downloaded = 0, added;
        public volatile double speed;
        int attempt;
        long retryAt;
        volatile boolean stop;

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", id); o.put("url", url); o.put("filename", filename); o.put("mime", mime);
            o.put("uri", uri); o.put("etag", etag); o.put("lastModified", lastModified);
            o.put("error", error); o.put("status", status); o.put("total", total);
            o.put("downloaded", downloaded); o.put("added", added);
            o.put("ua", ua); o.put("referer", referer); o.put("web", web); o.put("origUrl", origUrl);
            return o;
        }

        static Item fromJson(JSONObject o) {
            Item i = new Item();
            i.id = o.optString("id"); i.url = o.optString("url");
            i.filename = str(o, "filename"); i.mime = str(o, "mime"); i.uri = str(o, "uri");
            i.etag = str(o, "etag"); i.lastModified = str(o, "lastModified");
            i.error = o.optString("error", ""); i.status = o.optInt("status", QUEUED);
            i.total = o.optLong("total", -1); i.downloaded = o.optLong("downloaded", 0);
            i.added = o.optLong("added", System.currentTimeMillis());
            i.ua = str(o, "ua"); i.referer = str(o, "referer"); i.web = o.optBoolean("web", false);
            i.origUrl = str(o, "origUrl");
            return i;
        }

        private static String str(JSONObject o, String k) {
            return o.isNull(k) ? null : o.optString(k, null);
        }

        public String displayName() {
            if (filename != null) return filename;
            String s = url.replaceAll("[?#].*$", "");
            s = s.substring(s.lastIndexOf('/') + 1);
            return s.isEmpty() ? url : s;
        }
    }

    public interface Listener { void onDone(Item it); }

    static class HardError extends IOException { HardError(String m) { super(m); } }

    private static Downloader inst;

    public static synchronized Downloader get(Context c) {
        if (inst == null) inst = new Downloader(c.getApplicationContext());
        return inst;
    }

    private final Context ctx;
    private final File stateFile;
    public final List<Item> items = new ArrayList<>();
    private final Map<String, Thread> workers = new HashMap<>();
    public volatile Listener listener;
    private volatile boolean dirty;

    private Downloader(Context ctx) {
        this.ctx = ctx;
        this.stateFile = new File(ctx.getFilesDir(), "state.json");
        DownloadService.createChannels(ctx);
        load();
        Thread t = new Thread(this::schedulerLoop, "scheduler");
        t.setDaemon(true);
        t.start();
    }

    // ---------------- stan ----------------
    private void load() {
        try {
            if (!stateFile.exists()) return;
            byte[] b = java.nio.file.Files.readAllBytes(stateFile.toPath());
            JSONArray arr = new JSONObject(new String(b, StandardCharsets.UTF_8)).getJSONArray("items");
            for (int k = 0; k < arr.length(); k++) {
                Item it = Item.fromJson(arr.getJSONObject(k));
                if (it.status == ACTIVE || it.status == RETRY) it.status = QUEUED; // przerwane -> wznów
                if (it.status != DONE && it.uri != null) {
                    long s = currentSize(Uri.parse(it.uri));
                    if (s >= 0) it.downloaded = s;
                }
                items.add(it);
            }
        } catch (Exception ignored) { }
    }

    public synchronized void save(boolean force) {
        if (!force && !dirty) return;
        dirty = false;
        try {
            JSONArray arr = new JSONArray();
            for (Item i : items) arr.put(i.toJson());
            JSONObject root = new JSONObject().put("items", arr);
            File tmp = new File(stateFile.getPath() + ".tmp");
            try (FileOutputStream f = new FileOutputStream(tmp)) {
                f.write(root.toString().getBytes(StandardCharsets.UTF_8));
                f.getFD().sync();
            }
            if (!tmp.renameTo(stateFile)) {
                stateFile.delete();
                tmp.renameTo(stateFile);
            }
        } catch (Exception ignored) { }
    }

    void mark() { dirty = true; }

    // ---------------- akcje ----------------
    private static final Pattern URL_RE = Pattern.compile("(?i)\\b(?:https?|ftp)://\\S+");

    /** Dodaje wszystkie linki znalezione w tekście. Zwraca liczbę dodanych. */
    public int addFromText(String text) {
        if (text == null) return 0;
        Matcher m = URL_RE.matcher(text);
        int n = 0;
        synchronized (this) {
            while (m.find()) {
                Item it = new Item();
                it.id = UUID.randomUUID().toString().substring(0, 12);
                it.url = m.group().replaceAll("[)\\]>,.;'\"]+$", "");
                it.origUrl = it.url;
                it.added = System.currentTimeMillis();
                items.add(it);
                n++;
            }
            if (n > 0) { mark(); notifyAll(); }
        }
        if (n > 0) save(true);
        return n;
    }

    public synchronized Item find(String id) {
        for (Item i : items) if (i.id.equals(id)) return i;
        return null;
    }

    public synchronized void pause(String id) {
        Item it = find(id);
        if (it != null && (it.status == QUEUED || it.status == ACTIVE || it.status == RETRY)) {
            it.status = PAUSED; it.stop = true; it.speed = 0; mark();
        }
    }

    public synchronized void resume(String id) {
        Item it = find(id);
        if (it != null && (it.status == PAUSED || it.status == ERROR)) {
            it.status = QUEUED; it.error = ""; it.attempt = 0; it.retryAt = 0; mark(); notifyAll();
        }
    }

    /** Wbudowana przeglądarka złapała prawdziwy plik – podmień link i pobieraj dalej. */
    public void resolveFromWeb(String id, String url, String ua, String referer) {
        synchronized (this) {
            Item it = find(id);
            if (it == null) return;
            it.url = url; it.ua = ua; it.referer = referer; it.web = true;
            it.status = QUEUED; it.error = ""; it.attempt = 0; it.retryAt = 0;
            mark();
            notifyAll();
        }
        save(true);
        try { ctx.getSystemService(android.app.NotificationManager.class).cancel(webNotifId(id)); } catch (Exception ignored) { }
    }

    static int webNotifId(String id) { return 5000 + (id.hashCode() & 0xffff); }

    private void needWeb(Item it) {
        synchronized (this) {
            if (it.stop) return;
            it.status = NEED_WEB; it.speed = 0;
            it.error = "Link prowadzi do strony, nie do pliku – otwórz ją i przeklikaj";
            mark();
        }
        save(true);
        // powiadomienie: dotknij, żeby otworzyć stronę (gdy apka jest w tle)
        try {
            android.content.Intent i = new android.content.Intent(ctx, BrowserActivity.class)
                    .putExtra(BrowserActivity.EXTRA_ID, it.id)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(ctx, it.id.hashCode(), i,
                    android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
            android.app.Notification n = new android.app.Notification.Builder(ctx, DownloadService.CH_DONE)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle("Trzeba przeklikać stronę")
                    .setContentText(it.displayName() + " – dotknij, żeby otworzyć")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            ctx.getSystemService(android.app.NotificationManager.class).notify(webNotifId(it.id), n);
        } catch (Exception ignored) { }
    }

    public synchronized void pauseAll() { for (Item i : new ArrayList<>(items)) pause(i.id); }

    public synchronized void resumeAll() { for (Item i : new ArrayList<>(items)) resume(i.id); }

    /** Sieć wróciła -> ponów od razu zamiast czekać na backoff. */
    public synchronized void retryNow() {
        for (Item i : items) if (i.status == RETRY) i.retryAt = 0;
        notifyAll();
    }

    public void remove(String id) {
        Item it;
        Thread t;
        synchronized (this) {
            it = find(id);
            if (it == null) return;
            it.stop = true;
            items.remove(it);
            t = workers.get(id);
            mark();
        }
        final Item fi = it;
        final Thread ft = t;
        new Thread(() -> {
            try { if (ft != null) ft.join(5000); } catch (InterruptedException ignored) { }
            if (fi.status != DONE && fi.uri != null) {
                try { ctx.getContentResolver().delete(Uri.parse(fi.uri), null, null); } catch (Exception ignored) { }
            }
            save(true);
        }).start();
    }

    public synchronized void clearDone() {
        items.removeIf(i -> i.status == DONE);
        mark();
    }

    /** Czy jest coś do zrobienia (kolejka / pobieranie / ponawianie). */
    public synchronized boolean isBusy() {
        for (Item i : items) if (i.status == QUEUED || i.status == ACTIVE || i.status == RETRY) return true;
        return false;
    }

    public synchronized List<Item> snapshot() { return new ArrayList<>(items); }

    // ---------------- planista ----------------
    private void schedulerLoop() {
        while (true) {
            synchronized (this) {
                workers.values().removeIf(t -> !t.isAlive());
                long now = System.currentTimeMillis();
                for (Item it : items) {
                    if (workers.size() >= MAX_PARALLEL) break;
                    if (workers.containsKey(it.id)) continue;
                    if (it.status == QUEUED || (it.status == RETRY && now >= it.retryAt)) {
                        it.stop = false;
                        Thread t = new Thread(() -> run(it), "dl-" + it.id);
                        workers.put(it.id, t);
                        t.start();
                    }
                }
                try { wait(isBusy() ? 500 : 5000); } catch (InterruptedException ignored) { }
            }
        }
    }

    private void run(Item it) {
        synchronized (this) {
            if (it.stop || (it.status != QUEUED && it.status != RETRY)) return;
            it.status = ACTIVE; it.error = ""; mark();
        }
        try {
            download(it);
        } catch (HardError e) {
            it.attempt++;
            if (it.attempt >= HARD_FAIL_LIMIT) {
                synchronized (this) {
                    if (!it.stop) { it.status = ERROR; it.error = e.getMessage(); it.speed = 0; mark(); }
                }
                save(true);
            } else scheduleRetry(it, e.getMessage());
        } catch (Exception e) {
            if (it.stop) return;
            it.attempt++;
            scheduleRetry(it, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private synchronized void scheduleRetry(Item it, String msg) {
        if (it.stop) return;
        int delay = (int) Math.min(BACKOFF_MAX, Math.pow(2, Math.min(it.attempt, 6)));
        if (msg != null && msg.length() > 120) msg = msg.substring(0, 120);
        it.status = RETRY; it.speed = 0;
        it.error = msg + " (próba " + it.attempt + ", za " + delay + "s)";
        it.retryAt = System.currentTimeMillis() + delay * 1000L;
        mark();
    }

    // ---------------- pobieranie ----------------
    private long currentSize(Uri uri) {
        try (ParcelFileDescriptor p = ctx.getContentResolver().openFileDescriptor(uri, "r")) {
            return p == null ? -1 : p.getStatSize();
        } catch (Exception e) {
            return -1;
        }
    }

    private HttpURLConnection connect(Item it, long pos) throws IOException {
        URL url = new URL(it.url);
        for (int hop = 0; hop < 10; hop++) {
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            c.setRequestProperty("User-Agent", it.ua != null ? it.ua : UA);
            c.setRequestProperty("Accept-Encoding", "identity");
            if (it.referer != null) c.setRequestProperty("Referer", it.referer);
            if (it.web) {
                try {
                    String ck = android.webkit.CookieManager.getInstance().getCookie(url.toString());
                    if (ck != null && !ck.isEmpty()) c.setRequestProperty("Cookie", ck);
                } catch (Exception ignored) { }
            }
            if (pos > 0) {
                c.setRequestProperty("Range", "bytes=" + pos + "-");
                String v = (it.etag != null && !it.etag.startsWith("W/")) ? it.etag : it.lastModified;
                if (v != null) c.setRequestProperty("If-Range", v);
            }
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new IOException("Przekierowanie bez adresu");
                url = new URL(url, loc);
                continue;
            }
            return c;
        }
        throw new IOException("Za dużo przekierowań");
    }

    private void download(Item it) throws Exception {
        ContentResolver cr = ctx.getContentResolver();
        Uri uri = it.uri != null ? Uri.parse(it.uri) : null;
        long pos = 0;
        if (uri != null) {
            pos = currentSize(uri);
            if (pos < 0) { uri = null; it.uri = null; pos = 0; } // plik usunięty z telefonu -> od nowa
        }

        HttpURLConnection c = connect(it, pos);
        try {
            int code = c.getResponseCode();
            if (code == 416 && pos > 0) {
                if (it.total > 0 && pos >= it.total) { finish(it); return; }
                truncate(uri);
                throw new IOException("Serwer odrzucił zakres – zaczynam od nowa");
            }
            if (code == 408 || code == 425 || code == 429 || code >= 500) throw new IOException("HTTP " + code);
            if (code >= 400) throw new HardError("HTTP " + code + " " + c.getResponseMessage());

            // zamiast pliku przyszła strona (np. ostrzeżenie Google Drive, licznik, captcha) -> do przeklikania
            String ct = c.getContentType();
            if (code == 200 && ct != null && ct.toLowerCase().startsWith("text/html")
                    && c.getHeaderField("Content-Disposition") == null) {
                needWeb(it);
                return;
            }

            if (uri == null) {
                String name = fileName(c);
                String mime = mimeFor(name, c.getContentType());
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, mime);
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                v.put(MediaStore.Downloads.IS_PENDING, 1);
                uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new IOException("Nie mogę utworzyć pliku w Pobranych");
                synchronized (this) {
                    it.uri = uri.toString(); it.mime = mime;
                    String real = queryName(uri);
                    it.filename = real != null ? real : name;
                    mark();
                }
                save(true);
                pos = 0;
            }

            boolean partial = code == 206;
            long total;
            if (partial) {
                Matcher m = Pattern.compile("/(\\d+)\\s*$").matcher(String.valueOf(c.getHeaderField("Content-Range")));
                total = m.find() ? Long.parseLong(m.group(1)) : -1;
            } else {
                pos = 0;
                total = c.getContentLengthLong();
            }
            synchronized (this) {
                it.total = total; it.downloaded = pos;
                String et = c.getHeaderField("ETag"), lm = c.getHeaderField("Last-Modified");
                if (et != null) it.etag = et;
                if (lm != null) it.lastModified = lm;
                it.attempt = 0;
                mark();
            }

            try (ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "rw");
                 FileOutputStream fos = new FileOutputStream(pfd.getFileDescriptor());
                 FileChannel ch = fos.getChannel();
                 InputStream in = c.getInputStream()) {
                if (!partial) ch.truncate(0);
                ch.position(pos);
                byte[] buf = new byte[BUF];
                long winT = System.currentTimeMillis(), winB = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (it.stop) { fos.getFD().sync(); it.speed = 0; return; }
                    ByteBuffer bb = ByteBuffer.wrap(buf, 0, n);
                    while (bb.hasRemaining()) ch.write(bb);
                    it.downloaded += n;
                    winB += n;
                    long dt = System.currentTimeMillis() - winT;
                    if (dt >= 1000) {
                        it.speed = winB * 1000.0 / dt;
                        winT = System.currentTimeMillis(); winB = 0;
                        mark();
                    }
                }
                fos.getFD().sync();
            }
        } finally {
            c.disconnect();
        }

        if (it.stop) return;
        long size = currentSize(uri);
        it.downloaded = size;
        if (it.total >= 0 && size != it.total) throw new IOException("Niekompletny plik (" + size + "/" + it.total + " B)");
        if (it.total < 0) it.total = size;
        finish(it);
    }

    private void truncate(Uri uri) {
        try (ParcelFileDescriptor p = ctx.getContentResolver().openFileDescriptor(uri, "rwt")) {
            // samo otwarcie z "t" czyści plik
        } catch (Exception ignored) { }
    }

    private void finish(Item it) {
        Uri uri = Uri.parse(it.uri);
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.IS_PENDING, 0);
        try { ctx.getContentResolver().update(uri, v, null, null); } catch (Exception ignored) { }
        synchronized (this) {
            String real = queryName(uri);
            if (real != null) it.filename = real;
            it.status = DONE; it.speed = 0; it.error = "";
            if (it.total > 0) it.downloaded = it.total;
            mark();
        }
        save(true);
        Listener l = listener;
        if (l != null) l.onDone(it);
    }

    private String queryName(Uri uri) {
        try (Cursor cur = ctx.getContentResolver().query(uri, new String[]{MediaStore.Downloads.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) return cur.getString(0);
        } catch (Exception ignored) { }
        return null;
    }

    static String fileName(HttpURLConnection c) {
        String name = null;
        String cd = c.getHeaderField("Content-Disposition");
        if (cd != null) {
            Matcher m = Pattern.compile("(?i)filename\\*\\s*=\\s*[^']*''([^;]+)").matcher(cd);
            if (m.find()) name = decode(m.group(1).trim().replace("\"", ""));
            else {
                m = Pattern.compile("(?i)filename\\s*=\\s*\"?([^\";]+)\"?").matcher(cd);
                if (m.find()) name = m.group(1).trim();
            }
        }
        if (name == null || name.isEmpty()) {
            String p = c.getURL().getPath();
            name = decode(p.substring(p.lastIndexOf('/') + 1));
        }
        name = name.replaceAll("[<>:\"/\\\\|?*\\x00-\\x1f]", "_").trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) name = "plik";
        return name.length() > 150 ? name.substring(0, 150) : name;
    }

    private static String decode(String s) {
        try { return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8"); } catch (Exception e) { return s; }
    }

    static String mimeFor(String name, String contentType) {
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase());
            if (m != null) return m;
            return "application/octet-stream"; // nieznane rozszerzenie – nie pozwól Androidowi go zmienić
        }
        if (contentType != null && !contentType.isEmpty()) return contentType.split(";")[0].trim();
        return "application/octet-stream";
    }
}
