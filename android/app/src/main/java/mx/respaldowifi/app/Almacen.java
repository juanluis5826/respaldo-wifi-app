package mx.respaldowifi.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import mx.respaldowifi.app.motor.Archivo;
import mx.respaldowifi.app.motor.Cola;
import mx.respaldowifi.app.motor.Enlace;
import mx.respaldowifi.app.motor.Motor;

/**
 * Estado compartido entre la pantalla (plugin) y el servicio en primer plano:
 * la PC conectada, el nombre del celular y la lista de archivos. Se guarda en disco
 * para que, si Android cierra la app, el respaldo pueda continuar donde se quedó.
 */
public final class Almacen {

    public interface Escucha {
        void progreso();
        void cambio(Archivo a);
        void cola();
        void fin(String motivo, String mensaje);
    }

    private static Almacen unico;

    public static synchronized Almacen de(Context c) {
        if (unico == null) unico = new Almacen(c.getApplicationContext());
        return unico;
    }

    private final Context ctx;
    private final SharedPreferences prefs;
    private final File archivoCola;
    private final Handler principal = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Escucha> escuchas = new CopyOnWriteArrayList<>();
    private long ultimoGuardado;
    private boolean guardadoPendiente;

    public final Cola cola = new Cola();
    public volatile boolean corriendo;
    public volatile Motor motor;
    /** Último aviso para mostrar en pantalla (ej. "Sin conexión con la PC"). */
    public volatile String mensaje = "";

    private Almacen(Context c) {
        ctx = c;
        prefs = c.getSharedPreferences("respaldo", Context.MODE_PRIVATE);
        archivoCola = new File(c.getFilesDir(), "cola.json");
        if (archivoCola.exists()) {
            try {
                byte[] b = java.nio.file.Files.readAllBytes(archivoCola.toPath());
                cola.cargarJson(new String(b, StandardCharsets.UTF_8));
            } catch (Exception ignorado) { /* lista dañada: empezar vacía */ }
        }
    }

    // ------------------------------------------------------------ PC y nombre

    public Enlace enlace() {
        String base = prefs.getString("base", null), token = prefs.getString("token", null);
        return base == null || token == null ? null : new Enlace(base, token);
    }

    public void guardarEnlace(Enlace e, String servidor, int trozo) {
        prefs.edit().putString("base", e.base).putString("token", e.token)
            .putString("servidor", servidor).putInt("trozo", trozo).apply();
    }

    public void olvidarEnlace() {
        prefs.edit().remove("base").remove("token").remove("servidor").apply();
    }

    public String servidor() { return prefs.getString("servidor", ""); }

    public int trozo() { return prefs.getInt("trozo", 1536 * 1024); }

    public String dispositivo() {
        String d = prefs.getString("dispositivo", "");
        return d == null ? "" : d;
    }

    public void guardarDispositivo(String d) {
        prefs.edit().putString("dispositivo", d == null ? "" : d.trim()).apply();
    }

    public static String modelo() {
        String m = Build.MODEL == null ? "" : Build.MODEL;
        String f = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        if (!f.isEmpty() && !m.toLowerCase(Locale.ROOT).startsWith(f.toLowerCase(Locale.ROOT))) {
            m = f.substring(0, 1).toUpperCase(Locale.ROOT) + f.substring(1) + " " + m;
        }
        return m.isEmpty() ? "Celular" : m;
    }

    /** "Nombre del celular AAAA-MM-DD", igual que nombreSesion() en movil.js. */
    public String nombreSesion() {
        String n = dispositivo();
        if (n.isEmpty()) n = "Celular";
        return n + " " + new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    // ----------------------------------------------------------------- avisos

    public void escuchar(Escucha e) { escuchas.addIfAbsent(e); }

    public void dejarDeEscuchar(Escucha e) { escuchas.remove(e); }

    public void avisarProgreso() { for (Escucha e : escuchas) e.progreso(); }

    public void avisarCambio(Archivo a) {
        for (Escucha e : escuchas) e.cambio(a);
        guardarPronto();
    }

    public void avisarCola() {
        for (Escucha e : escuchas) e.cola();
        guardar();
    }

    public void avisarFin(String motivo, String msg) {
        mensaje = msg == null ? "" : msg;
        for (Escucha e : escuchas) e.fin(motivo, mensaje);
    }

    // ---------------------------------------------------------------- guardar

    /** Guarda como máximo cada 3 segundos (los cambios de estado son muy seguidos). */
    public synchronized void guardarPronto() {
        long ahora = System.currentTimeMillis();
        if (ahora - ultimoGuardado > 3000) {
            guardar();
        } else if (!guardadoPendiente) {
            guardadoPendiente = true;
            principal.postDelayed(() -> {
                synchronized (Almacen.this) { guardadoPendiente = false; }
                guardar();
            }, 3000);
        }
    }

    public synchronized void guardar() {
        ultimoGuardado = System.currentTimeMillis();
        File tmp = new File(archivoCola.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(cola.aJson().getBytes(StandardCharsets.UTF_8));
            o.getFD().sync();
        } catch (IOException e) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(archivoCola);
    }

    public Context contexto() { return ctx; }
}
