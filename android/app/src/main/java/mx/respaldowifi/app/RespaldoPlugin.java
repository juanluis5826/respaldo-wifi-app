package mx.respaldowifi.app;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.provider.Settings;

import androidx.activity.result.ActivityResult;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import mx.respaldowifi.app.motor.Archivo;
import mx.respaldowifi.app.motor.ClienteApi;
import mx.respaldowifi.app.motor.Cola;
import mx.respaldowifi.app.motor.Enlace;
import mx.respaldowifi.app.motor.Json;

/**
 * Puente entre la página (www/app.js) y la parte nativa.
 * En JavaScript se usa como Capacitor.Plugins.Respaldo.
 */
@CapacitorPlugin(
    name = "Respaldo",
    permissions = {
        @Permission(alias = "notificaciones", strings = { Manifest.permission.POST_NOTIFICATIONS })
    }
)
public class RespaldoPlugin extends Plugin implements Almacen.Escucha {

    private Almacen almacen;
    private final ExecutorService trabajo = Executors.newSingleThreadExecutor();
    private final Handler principal = new Handler(Looper.getMainLooper());
    private final Set<Long> cambiados = new HashSet<>();
    private boolean envioProgramado;
    private volatile ClienteApi medicion;

    @Override
    public void load() {
        almacen = Almacen.de(getContext());
        almacen.escuchar(this);
        ServicioRespaldo.crearCanales(getContext());
    }

    @Override
    protected void handleOnDestroy() {
        almacen.dejarDeEscuchar(this);
        trabajo.shutdownNow();
    }

    // ============================================================ estado

    @PluginMethod
    public void obtenerEstado(PluginCall call) {
        JSObject r = resumen(true);
        call.resolve(r);
    }

    private JSObject resumen(boolean conLista) {
        JSObject r = new JSObject();
        Enlace e = almacen.enlace();
        if (e != null) {
            JSObject c = new JSObject();
            c.put("host", e.host());
            c.put("base", e.base);
            c.put("servidor", almacen.servidor());
            r.put("conexion", c);
        } else {
            r.put("conexion", org.json.JSONObject.NULL);
        }
        r.put("dispositivo", almacen.dispositivo());
        r.put("modelo", Almacen.modelo());
        r.put("corriendo", almacen.corriendo);
        r.put("mensaje", almacen.mensaje);
        r.put("velocidad", almacen.motor != null ? almacen.motor.velocidad() : 0);
        r.put("totales", mapa(almacen.cola.totales().aMapa()));
        r.put("avanceParcial", almacen.cola.hayAvanceParcial());
        r.put("notificaciones", notificacionesPermitidas());
        r.put("bateriaLibre", bateriaSinRestriccion());
        if (conLista) {
            JSArray l = new JSArray();
            for (Archivo a : almacen.cola.copia()) l.put(item(a));
            r.put("archivos", l);
        }
        return r;
    }

    private static JSObject item(Archivo a) {
        JSObject o = new JSObject();
        o.put("id", a.id);
        o.put("ruta", a.ruta);
        o.put("tam", a.tam);
        o.put("estado", a.estado);
        o.put("enviados", Math.min(a.enviados, a.tam));
        o.put("error", a.error);
        return o;
    }

    private static JSObject mapa(Map<String, Object> m) {
        JSObject o = new JSObject();
        for (Map.Entry<String, Object> e : m.entrySet()) o.put(e.getKey(), e.getValue());
        return o;
    }

    // ============================================================ conectar

    @PluginMethod
    public void conectar(PluginCall call) {
        String texto = call.getString("enlace", "");
        trabajo.execute(() -> {
            Enlace e;
            try {
                e = Enlace.leer(texto);
            } catch (IllegalArgumentException ex) {
                call.reject(ex.getMessage());
                return;
            }
            ClienteApi api = new ClienteApi(e, AndroidIO.conectorWifi(getContext()));
            api.tiempoLecturaMs = 10000;
            try {
                Map<String, Object> d = api.info();
                String servidor = Json.texto(d, "servidor", "PC");
                int trozo = (int) Json.entero(d, "trozo", 1536 * 1024);
                almacen.guardarEnlace(e, servidor, trozo);
                almacen.mensaje = "";
                JSObject r = new JSObject();
                r.put("host", e.host());
                r.put("servidor", servidor);
                call.resolve(r);
            } catch (ClienteApi.ErrorHttp ex) {
                if (ex.codigo == 401) {
                    call.reject("Ese enlace ya no es válido. En la PC pudieron presionar \"Nuevo enlace\": escanea el QR otra vez.");
                } else if (ex.codigo == 0) {
                    call.reject("No se pudo conectar con " + e.host() + ". Revisa que el celular y la PC estén en la misma red WiFi y que el programa siga abierto en la PC.");
                } else {
                    call.reject("La PC respondió con un error (" + ex.codigo + ").");
                }
            }
        });
    }

    @PluginMethod
    public void olvidarPc(PluginCall call) {
        if (almacen.corriendo) {
            call.reject("Pausa el respaldo antes de cambiar de PC.");
            return;
        }
        almacen.olvidarEnlace();
        call.resolve();
    }

    // ============================================================ elegir archivos

    @PluginMethod
    public void elegir(PluginCall call) {
        String tipo = call.getString("tipo", "archivos");
        Intent i;
        if ("carpeta".equals(tipo)) {
            i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        } else {
            i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            if ("galeria".equals(tipo)) i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(call, i, "alElegir");
        } catch (RuntimeException e) {
            call.reject("Este celular no tiene un selector de archivos disponible.");
        }
    }

    @ActivityCallback
    private void alElegir(PluginCall call, ActivityResult resultado) {
        if (call == null) return;
        Intent datos = resultado.getData();
        if (resultado.getResultCode() != android.app.Activity.RESULT_OK || datos == null) {
            JSObject r = new JSObject();
            r.put("nuevos", 0);
            r.put("repetidos", 0);
            r.put("cancelado", true);
            call.resolve(r);
            return;
        }
        String tipo = call.getString("tipo", "archivos");
        trabajo.execute(() -> {
            int[] cuenta = {0, 0};   // nuevos, repetidos
            try {
                if ("carpeta".equals(tipo)) {
                    agregarCarpeta(datos.getData(), cuenta);
                } else {
                    List<Uri> uris = new ArrayList<>();
                    if (datos.getClipData() != null) {
                        for (int k = 0; k < datos.getClipData().getItemCount(); k++) uris.add(datos.getClipData().getItemAt(k).getUri());
                    } else if (datos.getData() != null) {
                        uris.add(datos.getData());
                    }
                    for (Uri u : uris) agregarArchivo(u, cuenta);
                }
            } catch (RuntimeException e) {
                almacen.avisarCola();
                call.reject("No se pudo leer lo que elegiste: " + e.getMessage());
                return;
            }
            almacen.avisarCola();
            JSObject r = new JSObject();
            r.put("nuevos", cuenta[0]);
            r.put("repetidos", cuenta[1]);
            call.resolve(r);
        });
    }

    private void reservarPermiso(Uri u) {
        try {
            getContext().getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (RuntimeException ignorado) { /* el proveedor no lo permite: solo funcionará mientras la app esté abierta */ }
    }

    private void agregarArchivo(Uri u, int[] cuenta) {
        reservarPermiso(u);
        ContentResolver cr = getContext().getContentResolver();
        String nombre = null, mime = cr.getType(u);
        long tam = -1, mtime = 0;
        try (Cursor c = cr.query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int iN = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int iT = c.getColumnIndex(OpenableColumns.SIZE);
                int iM = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
                if (iN >= 0) nombre = c.getString(iN);
                if (iT >= 0 && !c.isNull(iT)) tam = c.getLong(iT);
                if (iM >= 0 && !c.isNull(iM)) mtime = c.getLong(iM);
            }
        } catch (RuntimeException ignorado) { }
        if (tam < 0) {
            try (android.os.ParcelFileDescriptor p = cr.openFileDescriptor(u, "r")) {
                if (p != null) tam = p.getStatSize();
            } catch (Exception ignorado) { }
        }
        if (tam < 0) return;   // no se puede saber el tamaño: no se puede reanudar
        if (nombre == null || nombre.isEmpty()) nombre = u.getLastPathSegment() == null ? "archivo" : u.getLastPathSegment();
        String ruta = AndroidIO.carpetaPorTipo(mime, nombre) + "/" + nombre;
        if (almacen.cola.agregar(u.toString(), ruta, tam, mtime) != null) cuenta[0]++; else cuenta[1]++;
    }

    /** Recorre la carpeta y sus subcarpetas, conservando la estructura (ej. "DCIM/Camera/..."). */
    private void agregarCarpeta(Uri arbol, int[] cuenta) {
        if (arbol == null) return;
        reservarPermiso(arbol);
        ContentResolver cr = getContext().getContentResolver();
        String raizId = DocumentsContract.getTreeDocumentId(arbol);
        String raizNombre = "Carpeta";
        try (Cursor c = cr.query(DocumentsContract.buildDocumentUriUsingTree(arbol, raizId),
            new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) raizNombre = c.getString(0);
        } catch (RuntimeException ignorado) { }

        String[] columnas = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        };
        ArrayDeque<String[]> pendientes = new ArrayDeque<>();   // {documentId, rutaRelativa}
        pendientes.add(new String[]{raizId, raizNombre});
        long ultimoAviso = 0;
        while (!pendientes.isEmpty()) {
            String[] dir = pendientes.poll();
            Uri hijos = DocumentsContract.buildChildDocumentsUriUsingTree(arbol, dir[0]);
            try (Cursor c = cr.query(hijos, columnas, null, null, null)) {
                if (c == null) continue;
                while (c.moveToNext()) {
                    String id = c.getString(0), nombre = c.getString(1), mime = c.getString(2);
                    if (nombre == null) continue;
                    String ruta = dir[1] + "/" + nombre;
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        pendientes.add(new String[]{id, ruta});
                        continue;
                    }
                    long tam = c.isNull(3) ? -1 : c.getLong(3);
                    if (tam < 0) continue;
                    long mtime = c.isNull(4) ? 0 : c.getLong(4);
                    Uri doc = DocumentsContract.buildDocumentUriUsingTree(arbol, id);
                    if (almacen.cola.agregar(doc.toString(), ruta, tam, mtime) != null) cuenta[0]++; else cuenta[1]++;
                }
            } catch (RuntimeException ignorado) { /* subcarpeta sin acceso */ }
            long ahora = System.currentTimeMillis();
            if (ahora - ultimoAviso > 400) {
                ultimoAviso = ahora;
                JSObject d = new JSObject();
                d.put("encontrados", cuenta[0] + cuenta[1]);
                notifyListeners("leyendo", d);
            }
        }
    }

    // ============================================================ respaldo

    @PluginMethod
    public void iniciar(PluginCall call) {
        if (almacen.enlace() == null) {
            call.reject("Primero conecta con la PC.");
            return;
        }
        String d = call.getString("dispositivo", null);
        if (d != null) almacen.guardarDispositivo(d);
        try {
            ServicioRespaldo.iniciar(getContext());
            call.resolve();
        } catch (RuntimeException e) {
            call.reject("Android no permitió iniciar el respaldo: " + e.getMessage());
        }
    }

    @PluginMethod
    public void pausar(PluginCall call) {
        ServicioRespaldo.pausar(getContext());
        call.resolve();
    }

    @PluginMethod
    public void reintentarFallidos(PluginCall call) {
        almacen.cola.reintentarFallidos();
        almacen.avisarCola();
        iniciar(call);
    }

    @PluginMethod
    public void vaciar(PluginCall call) {
        if (almacen.corriendo) {
            call.reject("Pausa el respaldo antes de vaciar la lista.");
            return;
        }
        almacen.cola.vaciar();
        almacen.mensaje = "";
        // Soltar los permisos de lectura que se habían reservado.
        try {
            ContentResolver cr = getContext().getContentResolver();
            for (android.content.UriPermission p : cr.getPersistedUriPermissions()) {
                cr.releasePersistableUriPermission(p.getUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
        } catch (RuntimeException ignorado) { }
        almacen.avisarCola();
        call.resolve();
    }

    @PluginMethod
    public void guardarDispositivo(PluginCall call) {
        almacen.guardarDispositivo(call.getString("dispositivo", ""));
        call.resolve();
    }

    // ============================================================ medidor de velocidad

    /** Igual que medirVelocidad() de movil.js: ping + 8 segundos enviando datos que la PC descarta. */
    @PluginMethod
    public void medirVelocidad(PluginCall call) {
        Enlace e = almacen.enlace();
        if (e == null) { call.reject("Primero conecta con la PC."); return; }
        if (almacen.corriendo) { call.reject("Espera a que termine o pausa el respaldo."); return; }
        String dispositivo = call.getString("dispositivo", "Celular");
        new Thread(() -> {
            ClienteApi api = new ClienteApi(e, AndroidIO.conectorWifi(getContext()));
            medicion = api;
            try {
                long[] tiempos = new long[5];
                for (int k = 0; k < 5; k++) {
                    long t0 = System.nanoTime();
                    api.ping();
                    tiempos[k] = (System.nanoTime() - t0) / 1_000_000;
                }
                java.util.Arrays.sort(tiempos);
                long ping = tiempos[2];
                JSObject p = new JSObject();
                p.put("fase", "enviando");
                p.put("ping", ping);
                notifyListeners("medicion", p);

                byte[] bloque = new byte[Math.min(almacen.trozo(), 4 * 1024 * 1024)];
                new SecureRandom().nextBytes(bloque);
                final long duracion = 8000;
                final long inicio = System.currentTimeMillis();
                final long[] completados = {0}, enCurso = {0};
                final ArrayDeque<long[]> marcas = new ArrayDeque<>();
                final boolean[] fin = {false};
                Thread reloj = new Thread(() -> {
                    while (!fin[0]) {
                        long ahora = System.currentTimeMillis();
                        marcas.addLast(new long[]{ahora, completados[0] + enCurso[0]});
                        while (marcas.size() > 2 && ahora - marcas.peekFirst()[0] > 2000) marcas.pollFirst();
                        if (marcas.size() > 1) {
                            long[] a = marcas.peekFirst(), b = marcas.peekLast();
                            double dt = (b[0] - a[0]) / 1000.0;
                            if (dt > 0.3) {
                                JSObject m = new JSObject();
                                m.put("fase", "enviando");
                                m.put("mbs", (b[1] - a[1]) / dt / 1048576.0);
                                notifyListeners("medicion", m);
                            }
                        }
                        if (ahora - inicio > duracion) api.cancelar();
                        try { Thread.sleep(200); } catch (InterruptedException ie) { return; }
                    }
                });
                reloj.start();
                long[] primera = null, ultima = null;
                try {
                    while (System.currentTimeMillis() - inicio < duracion) {
                        enCurso[0] = 0;
                        try {
                            api.prueba(bloque, b -> enCurso[0] = b);
                        } catch (ClienteApi.ErrorHttp ex) {
                            if (ex.codigo == -1) break;   // se acabó el tiempo
                            throw ex;
                        }
                        completados[0] += bloque.length;
                        enCurso[0] = 0;
                        long[] marca = {System.currentTimeMillis(), completados[0]};
                        if (primera == null) primera = marca; else ultima = marca;
                    }
                } finally {
                    fin[0] = true;
                    reloj.interrupt();
                }
                double bps;
                if (primera != null && ultima != null) {
                    bps = (ultima[1] - primera[1]) / ((ultima[0] - primera[0]) / 1000.0);
                } else if (primera != null) {
                    bps = primera[1] / ((primera[0] - inicio) / 1000.0);
                } else {
                    call.reject("La red es demasiado lenta para medir en 8 segundos");
                    return;
                }
                double mbs = bps / 1048576.0;
                try {
                    new ClienteApi(e, AndroidIO.conectorWifi(getContext())).resultado(mbs, ping, dispositivo);
                } catch (ClienteApi.ErrorHttp ignorado) { }
                JSObject r = new JSObject();
                r.put("mbs", mbs);
                r.put("ping", ping);
                call.resolve(r);
            } catch (ClienteApi.ErrorHttp ex) {
                call.reject(ex.codigo == 401 ? "Enlace vencido: vuelve a escanear el QR" : "No se pudo conectar con la PC");
            } finally {
                medicion = null;
            }
        }, "medidor").start();
    }

    // ============================================================ permisos y batería

    private boolean notificacionesPermitidas() {
        if (Build.VERSION.SDK_INT < 33) return true;
        return ContextCompat.checkSelfPermission(getContext(), Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    @PluginMethod
    public void pedirNotificaciones(PluginCall call) {
        if (Build.VERSION.SDK_INT < 33 || getPermissionState("notificaciones") == PermissionState.GRANTED) {
            JSObject r = new JSObject();
            r.put("permitido", true);
            call.resolve(r);
            return;
        }
        requestPermissionForAlias("notificaciones", call, "alPedirNotificaciones");
    }

    @PermissionCallback
    private void alPedirNotificaciones(PluginCall call) {
        JSObject r = new JSObject();
        r.put("permitido", notificacionesPermitidas());
        call.resolve(r);
    }

    private boolean bateriaSinRestriccion() {
        try {
            PowerManager pm = (PowerManager) getContext().getSystemService(android.content.Context.POWER_SERVICE);
            return pm.isIgnoringBatteryOptimizations(getContext().getPackageName());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Abre la pantalla de Android donde el usuario puede quitar el ahorro de batería a la app. */
    @PluginMethod
    public void abrirAjustesBateria(PluginCall call) {
        Intent[] opciones = {
            new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getContext().getPackageName())),
        };
        for (Intent i : opciones) {
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(i);
                call.resolve();
                return;
            } catch (RuntimeException ignorado) { }
        }
        call.reject("No se pudieron abrir los ajustes");
    }

    // ============================================================ avisos hacia la página

    @Override
    public void progreso() { programarEnvio(); }

    @Override
    public void cambio(Archivo a) {
        synchronized (cambiados) { cambiados.add(a.id); }
        programarEnvio();
    }

    @Override
    public void cola() {
        principal.post(() -> notifyListeners("cola", new JSObject()));
    }

    @Override
    public void fin(String motivo, String mensaje) {
        principal.post(() -> {
            enviarProgreso();
            JSObject d = new JSObject();
            d.put("motivo", motivo);
            d.put("mensaje", mensaje);
            notifyListeners("fin", d);
        });
    }

    /** Agrupa los avisos: como máximo 4 por segundo para no saturar la página. */
    private void programarEnvio() {
        synchronized (cambiados) {
            if (envioProgramado) return;
            envioProgramado = true;
        }
        principal.postDelayed(this::enviarProgreso, 250);
    }

    private void enviarProgreso() {
        Set<Long> ids;
        synchronized (cambiados) {
            envioProgramado = false;
            ids = new HashSet<>(cambiados);
            cambiados.clear();
        }
        JSObject r = resumen(false);
        JSArray l = new JSArray();
        for (Archivo a : almacen.cola.copia()) {
            if (ids.contains(a.id) || Archivo.SUBIENDO.equals(a.estado)) l.put(item(a));
        }
        r.put("cambios", l);
        notifyListeners("progreso", r);
    }
}
