package mx.respaldowifi.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import mx.respaldowifi.app.motor.Archivo;
import mx.respaldowifi.app.motor.ClienteApi;
import mx.respaldowifi.app.motor.Cola;
import mx.respaldowifi.app.motor.Enlace;
import mx.respaldowifi.app.motor.Motor;

/**
 * Servicio en primer plano: sube la lista aunque la pantalla esté apagada.
 * Muestra la notificación "Respaldando… X/Y archivos" mientras trabaja.
 */
public class ServicioRespaldo extends Service {
    private static final String TAG = "RespaldoWiFi";
    public static final String INICIAR = "mx.respaldowifi.app.INICIAR";
    public static final String PAUSAR = "mx.respaldowifi.app.PAUSAR";
    static final String CANAL_PROGRESO = "progreso";
    static final String CANAL_AVISOS = "avisos";
    static final int ID_PROGRESO = 1;
    static final int ID_AVISO = 2;

    private Almacen almacen;
    private Thread hilo;
    private boolean otraVuelta;          // llegó "Iniciar" mientras ya se estaba subiendo
    private volatile boolean porTiempo;
    private long ultimaNotif, ultimoLog;
    private PowerManager.WakeLock bloqueoCpu;
    private WifiManager.WifiLock bloqueoWifi, bloqueoWifiRapido;

    /** Inicia (o continúa) el respaldo. Llamar desde la pantalla o una notificación. */
    public static void iniciar(Context c) {
        Intent i = new Intent(c, ServicioRespaldo.class).setAction(INICIAR);
        ContextCompat.startForegroundService(c, i);
    }

    public static void pausar(Context c) {
        Almacen a = Almacen.de(c);
        Motor m = a.motor;
        if (m != null) m.pausar();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        almacen = Almacen.de(this);
        crearCanales(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String accion = intent != null ? intent.getAction() : INICIAR;   // null = Android lo reinició
        if (PAUSAR.equals(accion)) {
            synchronized (this) {
                otraVuelta = false;
                if (hilo == null) { detener(); return START_NOT_STICKY; }
            }
            Motor m = almacen.motor;
            if (m != null) m.pausar();
            return START_NOT_STICKY;
        }

        if (!entrarEnPrimerPlano()) {
            avisoFinal("Respaldo detenido", "Toca para abrir la app y continuar.", true);
            detener();
            return START_NOT_STICKY;
        }
        synchronized (this) {
            if (hilo == null) {
                hilo = new Thread(this::trabajar, "respaldo");
                hilo.start();
            } else {
                otraVuelta = true;
            }
        }
        return START_STICKY;
    }

    private boolean entrarEnPrimerPlano() {
        try {
            int tipo = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC : 0;
            ServiceCompat.startForeground(this, ID_PROGRESO, notificacionProgreso(), tipo);
            return true;
        } catch (RuntimeException e) {
            // Android 12+ no deja iniciarlo desde segundo plano (ej. reinicio automático).
            Log.w(TAG, "No se pudo iniciar en primer plano", e);
            return false;
        }
    }

    private void trabajar() {
        Motor.Fin fin;
        while (true) {
            fin = correr();
            synchronized (this) {
                if (!otraVuelta || fin == Motor.Fin.ENLACE_VENCIDO) {
                    hilo = null;
                    break;
                }
                otraVuelta = false;
            }
        }
        terminar(fin);
    }

    private Motor.Fin correr() {
        bloquear();
        almacen.corriendo = true;
        almacen.mensaje = "";
        porTiempo = false;
        NotificationManagerSeguro.cancelar(this, ID_AVISO);
        Motor.Fin fin = Motor.Fin.COMPLETO;
        try {
            Enlace e = almacen.enlace();
            if (e == null) {
                fin = Motor.Fin.ENLACE_VENCIDO;
            } else {
                ClienteApi api = new ClienteApi(e, AndroidIO.conectorWifi(this));
                Motor m = new Motor(almacen.cola, api, AndroidIO.fuente(this), new Motor.Oyente() {
                    @Override public void cambio(Archivo a) {
                        if (a.terminado()) soltarPermiso(a);
                        almacen.avisarCambio(a);
                        actualizarNotificacion(false);
                    }
                    @Override public void progreso() {
                        almacen.avisarProgreso();
                        actualizarNotificacion(false);
                    }
                });
                almacen.motor = m;
                // Si el usuario agrega archivos mientras se sube, se vuelve a dar otra vuelta.
                do {
                    fin = m.ejecutar(almacen.nombreSesion());
                } while ((fin == Motor.Fin.COMPLETO || fin == Motor.Fin.CON_ERRORES)
                    && almacen.cola.siguientePendiente() != null);
            }
        } catch (RuntimeException ex) {
            Log.e(TAG, "Error inesperado", ex);
            fin = Motor.Fin.SIN_CONEXION;
        } finally {
            almacen.cola.soltarEnCurso();
            almacen.motor = null;
            almacen.corriendo = false;
            almacen.guardar();
            liberar();
        }
        return fin;
    }

    private void terminar(Motor.Fin fin) {
        Cola.Totales t = almacen.cola.totales();
        String titulo, texto, motivo = fin.name();
        boolean continuar = false;
        switch (fin) {
            case COMPLETO:
                titulo = "¡Respaldo completo!";
                texto = t.n + " archivo" + (t.n == 1 ? "" : "s") + " · " + AndroidIO.formatoBytes(t.total);
                break;
            case CON_ERRORES:
                titulo = "Terminado con " + t.errores + " error" + (t.errores == 1 ? "" : "es");
                texto = t.listos + " de " + t.n + " listos. Abre la app para reintentar.";
                break;
            case PAUSADO:
                if (porTiempo) {
                    motivo = "TIEMPO";
                    titulo = "Android pausó el respaldo";
                    texto = "Se alcanzó el límite de tiempo en segundo plano. Toca Continuar.";
                } else {
                    titulo = "Respaldo en pausa";
                    texto = t.listos + " de " + t.n + " archivos listos.";
                }
                continuar = true;
                break;
            case ENLACE_VENCIDO:
                titulo = "El enlace venció";
                texto = "Vuelve a escanear el QR de la PC.";
                break;
            default:
                titulo = "Sin conexión con la PC";
                texto = "Revisa que la PC siga encendida y en la misma red WiFi. Toca Continuar.";
                continuar = true;
        }
        Log.i(TAG, "FIN " + motivo + " listos=" + t.listos + "/" + t.n + " errores=" + t.errores);
        almacen.avisarFin(motivo, titulo + ". " + texto);
        avisoFinal(titulo, texto, continuar);
        synchronized (this) {
            if (hilo == null) detener();   // si mientras tanto pidieron continuar, no detener
        }
    }

    private void detener() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    /** Android 15+: los servicios de sincronización tienen un límite de ~6 horas al día. */
    @Override
    public void onTimeout(int startId, int fgsType) {
        porTiempo = true;
        Motor m = almacen.motor;
        if (m != null) m.pausar(); else detener();
    }

    @Override
    public void onDestroy() {
        Motor m = almacen.motor;
        if (m != null) m.pausar();
        liberar();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ------------------------------------------------------- bloqueos

    private void bloquear() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            bloqueoCpu = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RespaldoWiFi:subida");
            bloqueoCpu.setReferenceCounted(false);
            bloqueoCpu.acquire(24 * 60 * 60 * 1000L);
        } catch (RuntimeException e) { Log.w(TAG, "WakeLock", e); }
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            //noinspection deprecation
            bloqueoWifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "RespaldoWiFi:wifi");
            bloqueoWifi.setReferenceCounted(false);
            bloqueoWifi.acquire();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                bloqueoWifiRapido = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "RespaldoWiFi:rapido");
                bloqueoWifiRapido.setReferenceCounted(false);
                bloqueoWifiRapido.acquire();
            }
        } catch (RuntimeException e) { Log.w(TAG, "WifiLock", e); }
    }

    private void liberar() {
        try { if (bloqueoCpu != null && bloqueoCpu.isHeld()) bloqueoCpu.release(); } catch (RuntimeException ignorado) { }
        try { if (bloqueoWifi != null && bloqueoWifi.isHeld()) bloqueoWifi.release(); } catch (RuntimeException ignorado) { }
        try { if (bloqueoWifiRapido != null && bloqueoWifiRapido.isHeld()) bloqueoWifiRapido.release(); } catch (RuntimeException ignorado) { }
    }

    /** Ya se subió: dejar de reservar el permiso de lectura de ese archivo. */
    private void soltarPermiso(Archivo a) {
        try {
            getContentResolver().releasePersistableUriPermission(Uri.parse(a.uri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (RuntimeException ignorado) { /* era parte de una carpeta, o no estaba reservado */ }
    }

    // ------------------------------------------------------- notificaciones

    static void crearCanales(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel p = new NotificationChannel(CANAL_PROGRESO, "Respaldo en curso", NotificationManager.IMPORTANCE_LOW);
        p.setDescription("Avance del respaldo mientras se envían los archivos a la PC");
        p.setShowBadge(false);
        NotificationChannel a = new NotificationChannel(CANAL_AVISOS, "Avisos del respaldo", NotificationManager.IMPORTANCE_DEFAULT);
        a.setDescription("Cuando el respaldo termina, se pausa o pierde la conexión");
        nm.createNotificationChannel(p);
        nm.createNotificationChannel(a);
    }

    private PendingIntent abrirApp() {
        Intent i = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent accion(String a, int codigo) {
        Intent i = new Intent(this, ServicioRespaldo.class).setAction(a);
        if (INICIAR.equals(a) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return PendingIntent.getForegroundService(this, codigo, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        }
        return PendingIntent.getService(this, codigo, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification notificacionProgreso() {
        Cola.Totales t = almacen.cola.totales();
        Motor m = almacen.motor;
        double v = m != null ? m.velocidad() : 0;
        int pct = t.total > 0 ? (int) (t.enviados * 100 / t.total) : 0;
        String titulo = "Respaldando… " + t.listos + "/" + t.n + " archivos";
        StringBuilder texto = new StringBuilder(pct + "% · " + AndroidIO.formatoBytes(t.enviados) + " de " + AndroidIO.formatoBytes(t.total));
        if (v > 0) {
            texto.append(" · ").append(AndroidIO.formatoBytes(v)).append("/s");
            String r = AndroidIO.formatoTiempo((t.total - t.enviados) / v);
            if (!r.isEmpty()) texto.append(" · faltan ").append(r);
        }
        return new NotificationCompat.Builder(this, CANAL_PROGRESO)
            .setSmallIcon(R.drawable.ic_notificacion)
            .setColor(0xFF0F7B5F)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setProgress(100, pct, t.total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(abrirApp())
            .addAction(0, "Pausar", accion(PAUSAR, 1))
            .build();
    }

    private void actualizarNotificacion(boolean forzar) {
        long ahora = SystemClock.elapsedRealtime();
        if (!forzar && ahora - ultimaNotif < 1000) return;
        if (ahora - ultimoLog > 5000) {
            ultimoLog = ahora;
            Cola.Totales t = almacen.cola.totales();
            Log.i(TAG, "PROGRESO " + t.enviados + "/" + t.total + " bytes, " + t.listos + "/" + t.n + " archivos");
        }
        ultimaNotif = ahora;
        NotificationManagerSeguro.mostrar(this, ID_PROGRESO, notificacionProgreso());
    }

    private void avisoFinal(String titulo, String texto, boolean conContinuar) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CANAL_AVISOS)
            .setSmallIcon(R.drawable.ic_notificacion)
            .setColor(0xFF0F7B5F)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(texto))
            .setAutoCancel(true)
            .setContentIntent(abrirApp());
        if (conContinuar) b.addAction(0, "Continuar", accion(INICIAR, 2));
        NotificationManagerSeguro.mostrar(this, ID_AVISO, b.build());
    }

    /** Mostrar notificaciones sin fallar si el usuario no dio el permiso. */
    static final class NotificationManagerSeguro {
        static void mostrar(Context c, int id, Notification n) {
            try {
                NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
                nm.notify(id, n);
            } catch (RuntimeException ignorado) { }
        }

        static void cancelar(Context c, int id) {
            try {
                ((NotificationManager) c.getSystemService(NOTIFICATION_SERVICE)).cancel(id);
            } catch (RuntimeException ignorado) { }
        }
    }
}
