package pl.kadrixter.pobieranie;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int[] STATUS_COLOR = {
            0xFF9AA0A6, 0xFF4CC38A, 0xFFF0A832, 0xFFC9B458, 0xFF7AA7FF, 0xFFEF5350, 0xFFB388FF};
    /** Pozycje, dla których strona już raz sama się otworzyła (żeby nie otwierać w kółko). */
    private static final java.util.Set<String> autoOpened = new java.util.HashSet<>();
    private boolean resumed;

    private Downloader dl;
    private EditText input;
    private TextView summary, empty;
    private Adapter adapter;
    private final Handler h = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        DownloadService.createChannels(this);
        dl = Downloader.get(this);

        input = findViewById(R.id.input);
        summary = findViewById(R.id.summary);
        empty = findViewById(R.id.empty);
        ListView list = findViewById(R.id.list);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setEmptyView(empty);
        list.setOnItemLongClickListener((p, v, pos, id) -> { itemMenu(adapter.data.get(pos)); return true; });
        list.setOnItemClickListener((p, v, pos, id) -> {
            Downloader.Item it = adapter.data.get(pos);
            if (it.status == Downloader.DONE) open(it);
            else if (it.status == Downloader.NEED_WEB) openWeb(it);
            else itemMenu(it);
        });

        findViewById(R.id.btn_add).setOnClickListener(v -> addFromInput());
        findViewById(R.id.btn_paste).setOnClickListener(v -> paste());
        findViewById(R.id.btn_pause_all).setOnClickListener(v -> dl.pauseAll());
        findViewById(R.id.btn_resume_all).setOnClickListener(v -> { dl.resumeAll(); DownloadService.ensureRunning(this); });
        findViewById(R.id.btn_clear).setOnClickListener(v -> dl.clearDone());
        input.setOnEditorActionListener((v, actionId, e) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE || (e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                addFromInput();
                return true;
            }
            return false;
        });

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        askBattery();
        handleShare(getIntent());
        DownloadService.ensureRunning(this); // wznów to, co zostało po ostatnim razie
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShare(intent);
    }

    private void handleShare(Intent i) {
        if (i == null) return;
        String text = null;
        if (Intent.ACTION_SEND.equals(i.getAction())) text = i.getStringExtra(Intent.EXTRA_TEXT);
        else if (Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) text = i.getDataString();
        if (text == null) return;
        int n = dl.addFromText(text);
        Toast.makeText(this, n > 0 ? "Dodano: " + n : "Nie znaleziono linku", Toast.LENGTH_SHORT).show();
        if (n > 0) DownloadService.ensureRunning(this);
        setIntent(new Intent(this, MainActivity.class));
    }

    private void addFromInput() {
        String t = input.getText().toString();
        if (t.trim().isEmpty()) { paste(); return; }
        int n = dl.addFromText(t);
        if (n == 0) {
            Toast.makeText(this, "Link musi zaczynać się od http:// lub https://", Toast.LENGTH_SHORT).show();
            return;
        }
        input.setText("");
        DownloadService.ensureRunning(this);
    }

    private void paste() {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        ClipData c = cm.getPrimaryClip();
        if (c == null || c.getItemCount() == 0) { Toast.makeText(this, "Schowek jest pusty", Toast.LENGTH_SHORT).show(); return; }
        CharSequence t = c.getItemAt(0).coerceToText(this);
        int n = dl.addFromText(t == null ? null : t.toString());
        Toast.makeText(this, n > 0 ? "Dodano: " + n : "W schowku nie ma linku", Toast.LENGTH_SHORT).show();
        if (n > 0) DownloadService.ensureRunning(this);
    }

    private void askBattery() {
        PowerManager pm = getSystemService(PowerManager.class);
        SharedPreferences sp = getSharedPreferences("app", MODE_PRIVATE);
        if (pm.isIgnoringBatteryOptimizations(getPackageName()) || sp.getBoolean("battery_asked", false)) return;
        sp.edit().putBoolean("battery_asked", true).apply();
        new AlertDialog.Builder(this)
                .setTitle("Pobieranie w tle")
                .setMessage("Żeby telefon nie przerywał pobierania przy wygaszonym ekranie, zezwól aplikacji działać bez ograniczeń baterii.")
                .setPositiveButton("Zezwól", (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    }
                })
                .setNegativeButton("Nie teraz", null)
                .show();
    }

    private void itemMenu(Downloader.Item it) {
        List<String> opts = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        if (it.status == Downloader.DONE) { opts.add("Otwórz"); acts.add(() -> open(it)); }
        if (it.status != Downloader.DONE) { opts.add("🌐 Otwórz stronę i przeklikaj"); acts.add(() -> openWeb(it)); }
        if (it.status == Downloader.PAUSED || it.status == Downloader.ERROR) {
            opts.add("Wznów"); acts.add(() -> { dl.resume(it.id); DownloadService.ensureRunning(this); });
        }
        if (it.status == Downloader.QUEUED || it.status == Downloader.ACTIVE || it.status == Downloader.RETRY) {
            opts.add("Wstrzymaj"); acts.add(() -> dl.pause(it.id));
        }
        opts.add("Kopiuj link");
        acts.add(() -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("link", it.url));
            Toast.makeText(this, "Skopiowano", Toast.LENGTH_SHORT).show();
        });
        opts.add("Usuń z listy");
        acts.add(() -> confirmRemove(it));
        new AlertDialog.Builder(this)
                .setTitle(it.displayName())
                .setItems(opts.toArray(new String[0]), (d, w) -> acts.get(w).run())
                .show();
    }

    private void confirmRemove(Downloader.Item it) {
        if (it.status == Downloader.DONE) { dl.remove(it.id); return; }
        new AlertDialog.Builder(this)
                .setMessage("Usunąć z listy? Niedokończony plik zostanie skasowany.")
                .setPositiveButton("Usuń", (d, w) -> dl.remove(it.id))
                .setNegativeButton("Anuluj", null)
                .show();
    }

    private void openWeb(Downloader.Item it) {
        autoOpened.add(it.id);
        startActivity(new Intent(this, BrowserActivity.class).putExtra(BrowserActivity.EXTRA_ID, it.id));
    }

    private void open(Downloader.Item it) {
        if (it.uri == null) return;
        Intent v = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse(it.uri), it.mime != null ? it.mime : "*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(v, "Otwórz plik"));
        } catch (Exception e) {
            Toast.makeText(this, "Brak aplikacji do otwarcia tego pliku", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------- odświeżanie ----------------
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            adapter.data = dl.snapshot();
            adapter.notifyDataSetChanged();
            int done = 0, active = 0;
            double speed = 0;
            for (Downloader.Item i : adapter.data) {
                if (i.status == Downloader.DONE) done++;
                if (i.status == Downloader.ACTIVE || i.status == Downloader.RETRY || i.status == Downloader.QUEUED) active++;
                speed += i.speed;
            }
            String s = Fmt.files(adapter.data.size()) + " · ukończone " + done;
            if (active > 0) s += "\nw toku " + active + " · " + Fmt.size((long) speed) + "/s";
            summary.setText(s);

            // link dał stronę zamiast pliku -> od razu pokaż ją do przeklikania (raz na pozycję)
            if (resumed) {
                for (Downloader.Item i : adapter.data) {
                    if (i.status == Downloader.NEED_WEB && !autoOpened.contains(i.id)) {
                        openWeb(i);
                        return; // odświeżanie wróci w onResume
                    }
                }
            }
            h.postDelayed(this, 500);
        }
    };

    @Override protected void onResume() { super.onResume(); resumed = true; h.removeCallbacks(refresh); h.post(refresh); }

    @Override protected void onPause() { resumed = false; h.removeCallbacks(refresh); dl.save(true); super.onPause(); }

    private class Adapter extends BaseAdapter {
        List<Downloader.Item> data = new ArrayList<>();

        @Override public int getCount() { return data.size(); }
        @Override public Object getItem(int i) { return data.get(i); }
        @Override public long getItemId(int i) { return data.get(i).id.hashCode(); }
        @Override public boolean hasStableIds() { return true; }

        @Override
        public View getView(int pos, View v, ViewGroup parent) {
            if (v == null) v = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_download, parent, false);
            Downloader.Item it = data.get(pos);
            TextView name = v.findViewById(R.id.name), info = v.findViewById(R.id.info), status = v.findViewById(R.id.status);
            ProgressBar bar = v.findViewById(R.id.bar);
            Button toggle = v.findViewById(R.id.toggle);

            name.setText(it.displayName());
            int pct = it.total > 0 ? (int) (it.downloaded * 1000 / it.total) : (it.status == Downloader.DONE ? 1000 : 0);
            bar.setIndeterminate(it.status == Downloader.ACTIVE && it.total <= 0);
            bar.setProgress(pct);

            StringBuilder sb = new StringBuilder();
            if (it.status == Downloader.DONE) sb.append(Fmt.size(it.total > 0 ? it.total : it.downloaded));
            else {
                sb.append(Fmt.size(it.downloaded)).append(" / ").append(Fmt.size(it.total));
                if (it.total > 0) sb.append("  ·  ").append(pct / 10).append('.').append(pct % 10).append('%');
                if (it.status == Downloader.ACTIVE && it.speed > 0) {
                    sb.append("  ·  ").append(Fmt.size((long) it.speed)).append("/s");
                    if (it.total > 0) {
                        String e = Fmt.eta((it.total - it.downloaded) / it.speed);
                        if (!e.isEmpty()) sb.append("  ·  ").append(e);
                    }
                }
            }
            info.setText(sb);

            String st = Downloader.STATUS_PL[it.status];
            if ((it.status == Downloader.RETRY || it.status == Downloader.ERROR || it.status == Downloader.NEED_WEB)
                    && it.error != null && !it.error.isEmpty())
                st += " – " + it.error;
            status.setText(st);
            status.setTextColor(STATUS_COLOR[it.status]);

            boolean canPause = it.status == Downloader.QUEUED || it.status == Downloader.ACTIVE || it.status == Downloader.RETRY;
            boolean canResume = it.status == Downloader.PAUSED || it.status == Downloader.ERROR;
            boolean needWeb = it.status == Downloader.NEED_WEB;
            toggle.setVisibility(canPause || canResume || needWeb ? View.VISIBLE : View.GONE);
            toggle.setText(needWeb ? "🌐" : canPause ? "⏸" : "▶");
            toggle.setOnClickListener(x -> {
                if (needWeb) { openWeb(it); return; }
                if (canPause) dl.pause(it.id);
                else { dl.resume(it.id); DownloadService.ensureRunning(MainActivity.this); }
                h.removeCallbacks(refresh);
                h.post(refresh);
            });
            v.findViewById(R.id.remove).setOnClickListener(x -> confirmRemove(it));
            return v;
        }
    }
}
