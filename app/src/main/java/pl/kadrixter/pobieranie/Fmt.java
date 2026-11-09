package pl.kadrixter.pobieranie;

import java.util.Locale;

final class Fmt {
    private Fmt() { }

    static String size(long n) {
        if (n < 0) return "?";
        String[] u = {"B", "KB", "MB", "GB", "TB"};
        double d = n;
        int i = 0;
        while (d >= 1024 && i < u.length - 1) { d /= 1024; i++; }
        return i == 0 ? n + " B" : String.format(Locale.getDefault(), "%.1f %s", d, u[i]);
    }

    static String eta(double sec) {
        if (sec <= 0 || sec > 86400 * 30) return "";
        long s = (long) sec, h = s / 3600, m = (s % 3600) / 60;
        return h > 0 ? h + "h " + m + "m" : m + "m " + (s % 60) + "s";
    }

    static String files(int n) {
        if (n == 1) return "1 plik";
        int d = n % 10, dd = n % 100;
        return n + ((d >= 2 && d <= 4 && (dd < 12 || dd > 14)) ? " pliki" : " plików");
    }
}
