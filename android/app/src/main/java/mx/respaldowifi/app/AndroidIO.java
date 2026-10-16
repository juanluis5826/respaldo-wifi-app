package mx.respaldowifi.app;

import android.content.ContentResolver;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import mx.respaldowifi.app.motor.Archivo;
import mx.respaldowifi.app.motor.ClienteApi;
import mx.respaldowifi.app.motor.Motor;

/** Piezas del motor que dependen de Android: leer archivos y usar la red WiFi. */
final class AndroidIO {
    private AndroidIO() { }

    /** Lee archivos elegidos por el usuario (content://) a partir de cierto byte. */
    static Motor.Fuente fuente(Context ctx) {
        final ContentResolver cr = ctx.getContentResolver();
        return (Archivo a, long offset) -> {
            Uri u = Uri.parse(a.uri);
            ParcelFileDescriptor pfd = null;
            try {
                pfd = cr.openFileDescriptor(u, "r");
            } catch (FileNotFoundException | IllegalArgumentException e) {
                throw new FileNotFoundException("El archivo ya no existe en el celular");
            }
            if (pfd != null) {
                FileInputStream fis = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
                try {
                    fis.getChannel().position(offset);
                    return new BufferedInputStream(fis, 256 * 1024);
                } catch (IOException e) {
                    try { fis.close(); } catch (IOException ignorado) { }
                }
            }
            // Algunos proveedores no permiten saltar: leer desde el inicio y descartar.
            InputStream in = cr.openInputStream(u);
            if (in == null) throw new FileNotFoundException("No se pudo abrir el archivo");
            long falta = offset;
            byte[] buf = new byte[64 * 1024];
            while (falta > 0) {
                long s = in.skip(falta);
                if (s <= 0) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, falta));
                    if (n < 0) { in.close(); throw new IOException("El archivo es más corto de lo esperado"); }
                    s = n;
                }
                falta -= s;
            }
            return new BufferedInputStream(in, 256 * 1024);
        };
    }

    /**
     * Abre las conexiones por la red WiFi aunque Android tenga los datos móviles como red
     * principal (pasa cuando el WiFi de la casa "no tiene internet").
     */
    static ClienteApi.Conector conectorWifi(Context ctx) {
        final ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        return (URL url) -> {
            Network wifi = null;
            try {
                if (cm != null) {
                    for (Network n : cm.getAllNetworks()) {
                        NetworkCapabilities c = cm.getNetworkCapabilities(n);
                        if (c != null && (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                            || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
                            wifi = n;
                            break;
                        }
                    }
                }
            } catch (RuntimeException ignorado) { /* sin permiso o error: usar la red normal */ }
            return (HttpURLConnection) (wifi != null ? wifi.openConnection(url) : url.openConnection());
        };
    }

    static String carpetaPorTipo(String mime, String nombre) {
        String t = mime == null ? "" : mime;
        if (t.startsWith("image/")) return "Fotos";
        if (t.startsWith("video/")) return "Videos";
        if (t.startsWith("audio/")) return "Audio";
        if (nombre != null && nombre.toLowerCase().matches(".*\\.(pdf|docx?|xlsx?|pptx?|txt|odt|ods|csv|rtf)$")) return "Documentos";
        return "Otros";
    }

    static String formatoBytes(double b) {
        String[] u = {"B", "KB", "MB", "GB", "TB"};
        int i = 0;
        while (b >= 1024 && i < u.length - 1) { b /= 1024; i++; }
        if (i == 0) return ((long) b) + " B";
        return (b < 10 ? String.format(java.util.Locale.US, "%.1f", b) : String.valueOf(Math.round(b))) + " " + u[i];
    }

    static String formatoTiempo(double s) {
        if (Double.isInfinite(s) || Double.isNaN(s) || s < 0) return "";
        long r = Math.round(s);
        if (r < 60) return r + " s";
        if (r < 3600) return (r / 60) + " min";
        return (r / 3600) + " h " + ((r % 3600) / 60) + " min";
    }
}
