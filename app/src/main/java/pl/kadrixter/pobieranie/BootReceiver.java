package pl.kadrixter.pobieranie;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Po restarcie telefonu (lub aktualizacji aplikacji) dokończ niedokończone pobierania. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        try {
            DownloadService.ensureRunning(c);
        } catch (Exception ignored) { }
    }
}
