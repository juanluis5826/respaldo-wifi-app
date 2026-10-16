package mx.respaldowifi.app.motor;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Map;

/**
 * Sube la cola de archivos por trozos reanudables. Es la misma lógica de subirArchivo()
 * en movil.js, pero corre en el servicio en primer plano, así que sigue con la pantalla apagada.
 *
 *  1. "estado": la PC dice cuántos bytes ya tiene de ese archivo (para reanudar).
 *  2. "subir": se manda el trozo desde ese offset.
 *  3. 409 -> la PC dice desde dónde seguir ("recibido").
 *     413 -> trozo demasiado grande: se parte a la mitad.
 *     401 -> enlace vencido: se detiene.
 *     sin conexión / error 5xx -> reintenta con espera creciente.
 */
public final class Motor {

    /** Cómo abrir un archivo del celular a partir de cierto byte. */
    public interface Fuente {
        InputStream abrir(Archivo a, long offset) throws IOException;
    }

    public interface Oyente {
        /** Cambió el estado de un archivo (Enviando, Listo, Error...). */
        void cambio(Archivo a);

        /** Avanzaron los bytes enviados. Se llama muy seguido. */
        void progreso();
    }

    public enum Fin { COMPLETO, CON_ERRORES, PAUSADO, ENLACE_VENCIDO, SIN_CONEXION }

    public static final int MAX_INTENTOS = 6;          // igual que movil.js (errores de la PC)
    public static final int MAX_INTENTOS_RED = 12;     // cortes de WiFi: ~2.5 minutos esperando
    public static final int TROZO_MINIMO = 256 * 1024;

    private final Cola cola;
    private final ClienteApi cliente;
    private final Fuente fuente;
    private final Oyente oyente;
    private final Object espera = new Object();
    private final ArrayDeque<long[]> muestras = new ArrayDeque<>();

    private volatile boolean pausado;
    private volatile int trozo = 1536 * 1024;          // se ajusta con lo que diga la PC
    private volatile String servidor = "";
    /** Detalle técnico del último error (para diagnóstico). */
    public volatile String ultimoError = "";
    /** Multiplica las esperas entre reintentos (las pruebas lo bajan para ir rápido). */
    public double factorEspera = 1.0;

    public Motor(Cola cola, ClienteApi cliente, Fuente fuente, Oyente oyente) {
        this.cola = cola;
        this.cliente = cliente;
        this.fuente = fuente;
        this.oyente = oyente;
    }

    public String servidor() { return servidor; }

    public int trozo() { return trozo; }

    /** Pausa: corta el trozo que se está enviando. Lo ya enviado queda guardado en la PC. */
    public void pausar() {
        pausado = true;
        cliente.cancelar();
        synchronized (espera) { espera.notifyAll(); }
    }

    public boolean pausado() { return pausado; }

    /** Bytes por segundo en los últimos ~6 segundos. */
    public double velocidad() {
        synchronized (muestras) {
            if (muestras.size() < 2) return 0;
            long[] a = muestras.peekFirst(), b = muestras.peekLast();
            double dt = (b[0] - a[0]) / 1000.0;
            return dt > 0.5 ? (b[1] - a[1]) / dt : 0;
        }
    }

    private void muestra() {
        long ahora = System.currentTimeMillis();
        long bytes = cola.totales().enviados;
        synchronized (muestras) {
            muestras.addLast(new long[]{ahora, bytes});
            while (muestras.size() > 2 && ahora - muestras.peekFirst()[0] > 6000) muestras.pollFirst();
        }
    }

    /** Sube todo lo pendiente. Bloquea hasta terminar, pausar o perder la conexión. */
    public Fin ejecutar(String nombreSesion) {
        pausado = false;
        cliente.reiniciarCancelacion();
        synchronized (muestras) { muestras.clear(); }

        Fin f = leerInfo();
        if (f != null) return f;

        while (true) {
            if (pausado) return Fin.PAUSADO;
            Archivo a = cola.siguientePendiente();
            if (a == null) break;
            Fin r = subirArchivo(a, nombreSesion);
            if (r != null) {
                cola.soltarEnCurso();
                return r;
            }
        }
        return cola.totales().errores > 0 ? Fin.CON_ERRORES : Fin.COMPLETO;
    }

    private Fin leerInfo() {
        int intentos = 0;
        while (true) {
            if (pausado) return Fin.PAUSADO;
            try {
                Map<String, Object> d = cliente.info();
                long t = Json.entero(d, "trozo", 0);
                if (t >= TROZO_MINIMO) trozo = (int) Math.min(t, 64L * 1024 * 1024);
                servidor = Json.texto(d, "servidor", "PC");
                return null;
            } catch (ClienteApi.ErrorHttp e) {
                if (e.codigo == -1) return Fin.PAUSADO;
                if (e.codigo == 401) return Fin.ENLACE_VENCIDO;
                if (++intentos >= MAX_INTENTOS_RED) return Fin.SIN_CONEXION;
                dormir(esperaRed(intentos));
            }
        }
    }

    /** Devuelve null si hay que seguir con el siguiente archivo, o el motivo para detenerse. */
    private Fin subirArchivo(Archivo a, String nombreSesion) {
        a.estado = Archivo.SUBIENDO;
        a.error = "";
        if (a.sesion == null || a.sesion.isEmpty()) a.sesion = nombreSesion;
        oyente.cambio(a);

        int intentos = 0, intentosRed = 0, desfases = 0;
        int fallosEnvio = 0;        // cortes durante el envío de un trozo (no al preguntar estado)
        boolean enviando = false;
        long offset = -1;           // -1 = preguntar a la PC desde dónde seguir
        InputStream in = null;
        long posicion = -1;         // hasta dónde se ha leído "in"

        try {
            while (true) {
                if (pausado) {
                    a.estado = Archivo.PENDIENTE;
                    oyente.cambio(a);
                    return Fin.PAUSADO;
                }
                try {
                    if (offset < 0) {
                        Map<String, Object> st = cliente.estado(a.sesion, a.ruta, a.tam);
                        if (Json.logico(st, "completo")) {
                            a.enviados = a.tam;
                            a.estado = a.enviados > 0 && a.yaIniciado ? Archivo.LISTO : Archivo.OMITIDO;
                            a.error = "";
                            muestra();
                            oyente.cambio(a);
                            return null;
                        }
                        offset = Json.entero(st, "recibido", 0);
                        a.enviados = offset;
                        // La PC sí responde, pero el envío del trozo se cortó 2 veces: casi seguro
                        // el trozo es más grande de lo que acepta (PHP a veces corta en vez de dar 413).
                        if (fallosEnvio >= 2 && trozo > TROZO_MINIMO) {
                            trozo = Math.max(TROZO_MINIMO, trozo / 2);
                            fallosEnvio = 0;
                            intentosRed = 0;
                        }
                    }

                    int largo = (int) Math.min(trozo, a.tam - offset);
                    if (in == null || posicion != offset) {
                        cerrar(in);
                        in = null;
                        in = fuente.abrir(a, offset);
                        posicion = offset;
                    }
                    a.yaIniciado = true;
                    final long base = offset;
                    posicion = -1;   // si algo falla a la mitad, hay que volver a abrir
                    enviando = true;
                    Map<String, Object> r = cliente.subir(a.sesion, a.ruta, a.tam, offset, a.mtime, in, largo,
                        enviados -> {
                            a.enviados = base + Math.min(enviados, largo);
                            muestra();
                            oyente.progreso();
                        });
                    enviando = false;
                    posicion = base + largo;
                    fallosEnvio = 0;
                    intentos = 0;
                    intentosRed = 0;
                    desfases = 0;
                    if (!a.error.isEmpty()) { a.error = ""; oyente.cambio(a); }
                    offset = Json.entero(r, "recibido", base + largo);
                    a.enviados = offset;
                    muestra();
                    oyente.progreso();
                    if (Json.logico(r, "completo")) {
                        a.estado = Archivo.LISTO;
                        oyente.cambio(a);
                        return null;
                    }
                } catch (ClienteApi.ErrorHttp e) {
                    posicion = -1;
                    ultimoError = (enviando ? "subir: " : "estado: ") + e.getMessage();
                    if (enviando && e.codigo == 0) fallosEnvio++;
                    enviando = false;
                    switch (e.codigo) {
                        case -1:
                            a.estado = Archivo.PENDIENTE;
                            oyente.cambio(a);
                            return Fin.PAUSADO;
                        case 401:
                            a.estado = Archivo.PENDIENTE;
                            a.error = "Enlace vencido";
                            oyente.cambio(a);
                            return Fin.ENLACE_VENCIDO;
                        case 409:
                            if (++desfases <= 20) {
                                offset = Json.entero(e.datos, "recibido", 0);
                                a.enviados = offset;
                                continue;
                            }
                            break;   // demasiados desfases: tratarlo como error normal
                        case 413:
                            if (trozo > TROZO_MINIMO) {
                                trozo = Math.max(TROZO_MINIMO, trozo / 2);
                                offset = -1;
                                continue;
                            }
                            a.estado = Archivo.ERROR;
                            a.error = e.mensajePc("Rechazado por la PC");
                            oyente.cambio(a);
                            return null;
                        case 400:
                            a.estado = Archivo.ERROR;
                            a.error = e.mensajePc("Rechazado por la PC");
                            oyente.cambio(a);
                            return null;
                        case 0:
                            intentosRed++;
                            if (intentosRed >= MAX_INTENTOS_RED) {
                                a.estado = Archivo.PENDIENTE;
                                a.error = "Sin conexión con la PC";
                                oyente.cambio(a);
                                return Fin.SIN_CONEXION;
                            }
                            a.error = "Sin conexión, reintentando (" + intentosRed + ")…";
                            oyente.cambio(a);
                            offset = -1;
                            dormir(esperaRed(intentosRed));
                            continue;
                        default:
                            break;
                    }
                    intentos++;
                    if (intentos >= MAX_INTENTOS) {
                        a.estado = Archivo.ERROR;
                        a.error = e.mensajePc("Error " + e.codigo);
                        oyente.cambio(a);
                        return null;
                    }
                    a.error = "Reintentando (" + intentos + ")…";
                    oyente.cambio(a);
                    offset = -1;
                    dormir((long) (1500L * intentos));
                } catch (IOException | RuntimeException e) {
                    // No se pudo leer el archivo en el celular (se borró, se movió o se perdió el permiso).
                    a.estado = Archivo.ERROR;
                    a.error = e instanceof SecurityException
                        ? "Sin permiso para leerlo: vuelve a elegirlo"
                        : (e instanceof ClienteApi.ArchivoCambio ? e.getMessage() : "No se pudo leer el archivo en el celular");
                    oyente.cambio(a);
                    return null;
                }
            }
        } finally {
            cerrar(in);
        }
    }

    private static long esperaRed(int intento) {
        return Math.min(1500L * intento, 15000L);
    }

    private void dormir(long ms) {
        long total = (long) (ms * factorEspera);
        long fin = System.currentTimeMillis() + total;
        synchronized (espera) {
            while (!pausado) {
                long falta = fin - System.currentTimeMillis();
                if (falta <= 0) return;
                try {
                    espera.wait(falta);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static void cerrar(InputStream in) {
        if (in != null) {
            try { in.close(); } catch (IOException ignorado) { }
        }
    }
}
