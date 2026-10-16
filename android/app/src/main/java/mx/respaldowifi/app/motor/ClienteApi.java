package mx.respaldowifi.app.motor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Habla con api.php de la PC usando exactamente el mismo protocolo que movil.js:
 *   GET  api.php?accion=info&t=TOKEN
 *   GET  api.php?accion=estado&t=TOKEN&sesion=..&ruta=..&tamano=..
 *   POST api.php?accion=subir   (multipart: t, sesion, ruta, tamano, offset, mtime, trozo)
 *   GET  api.php?accion=ping&t=TOKEN
 *   POST api.php?accion=prueba&t=TOKEN (datos crudos)
 *   GET  api.php?accion=resultado&t=TOKEN&mbps=..&ping=..&dispositivo=..
 */
public final class ClienteApi {

    /** Error de comunicación. codigo: 0 = sin conexión, -1 = cancelado, otro = código HTTP. */
    public static final class ErrorHttp extends IOException {
        public final int codigo;
        public final Map<String, Object> datos;

        public ErrorHttp(int codigo, Map<String, Object> datos, String mensaje) {
            super(mensaje);
            this.codigo = codigo;
            this.datos = datos == null ? new LinkedHashMap<>() : datos;
        }

        public String mensajePc(String porDefecto) {
            return Json.texto(datos, "error", porDefecto);
        }
    }

    /** Abre conexiones HTTP. En Android se usa para forzar la red WiFi. */
    public interface Conector {
        HttpURLConnection abrir(URL url) throws IOException;
    }

    public interface Progreso {
        void enviados(long bytes);
    }

    private static final String SEPARADOR = "----RespaldoWiFi7d1c9e0b";

    private final Enlace enlace;
    private final Conector conector;
    private volatile HttpURLConnection actual;
    private volatile boolean cancelado;

    public int tiempoConexionMs = 8000;
    public int tiempoLecturaMs = 120000;   // igual que xhr.timeout en movil.js

    public ClienteApi(Enlace enlace, Conector conector) {
        this.enlace = enlace;
        this.conector = conector != null ? conector : url -> (HttpURLConnection) url.openConnection();
    }

    public Enlace enlace() { return enlace; }

    /** Corta la petición en curso (para Pausar). */
    public void cancelar() {
        cancelado = true;
        HttpURLConnection c = actual;
        if (c != null) {
            try { c.disconnect(); } catch (RuntimeException ignorado) { }
        }
    }

    public void reiniciarCancelacion() { cancelado = false; }

    public boolean cancelado() { return cancelado; }

    // ----------------------------------------------------------------- acciones

    public Map<String, Object> info() throws ErrorHttp {
        return get("info", null);
    }

    public Map<String, Object> estado(String sesion, String ruta, long tamano) throws ErrorHttp {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("sesion", sesion);
        p.put("ruta", ruta);
        p.put("tamano", String.valueOf(tamano));
        return get("estado", p);
    }

    public Map<String, Object> ping() throws ErrorHttp {
        return get("ping", null);
    }

    public Map<String, Object> resultado(double mbps, long ping, String dispositivo) throws ErrorHttp {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("mbps", String.format(java.util.Locale.US, "%.2f", mbps));
        p.put("ping", String.valueOf(ping));
        p.put("dispositivo", dispositivo);
        return get("resultado", p);
    }

    /**
     * Envía un trozo. Lee exactamente "largo" bytes de "datos".
     * Devuelve {recibido, completo}. Si el offset no coincide lanza ErrorHttp 409 con datos.recibido.
     */
    public Map<String, Object> subir(String sesion, String ruta, long tamano, long offset, long mtime,
                                     InputStream datos, int largo, Progreso progreso) throws IOException {
        if (cancelado) throw new ErrorHttp(-1, null, "Cancelado");
        byte[] cabecera = cabeceraMultipart(sesion, ruta, tamano, offset, mtime);
        byte[] pie = ("\r\n--" + SEPARADOR + "--\r\n").getBytes(StandardCharsets.UTF_8);
        long total = (long) cabecera.length + largo + pie.length;

        // El token va también en la URL: si el trozo excede post_max_size, PHP descarta todo el
        // cuerpo y sin esto api.php respondería 401 en lugar de 413.
        HttpURLConnection c = abrir(enlace.api() + "?accion=subir&t=" + Enlace.codificar(enlace.token));
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(total);
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + SEPARADOR);
            OutputStream out;
            try {
                out = c.getOutputStream();
            } catch (IOException e) {
                throw sinConexion(e);
            }
            try {
                out.write(cabecera);
                byte[] buf = new byte[64 * 1024];
                int faltan = largo;
                long enviados = 0;
                while (faltan > 0) {
                    if (cancelado) throw new ErrorHttp(-1, null, "Cancelado");
                    int n;
                    try {
                        n = datos.read(buf, 0, Math.min(buf.length, faltan));
                    } catch (IOException | RuntimeException e) {
                        throw new ArchivoCambio("No se pudo leer el archivo en el celular");
                    }
                    if (n < 0) throw new ArchivoCambio("El archivo cambió o ya no se puede leer");
                    try {
                        out.write(buf, 0, n);
                    } catch (IOException e) {
                        throw sinConexion(e);
                    }
                    faltan -= n;
                    enviados += n;
                    if (progreso != null) progreso.enviados(enviados);
                }
                out.write(pie);
                out.flush();
            } catch (ArchivoCambio | ErrorHttp e) {
                throw e;
            } catch (IOException e) {
                throw sinConexion(e);
            }
            return respuesta(c);
        } finally {
            actual = null;
            c.disconnect();
        }
    }

    /** Envía datos de prueba que la PC descarta (medidor de velocidad). */
    public void prueba(byte[] datos, Progreso progreso) throws ErrorHttp {
        if (cancelado) throw new ErrorHttp(-1, null, "Cancelado");
        HttpURLConnection c;
        try {
            c = abrir(enlace.api() + "?accion=prueba&t=" + Enlace.codificar(enlace.token));
        } catch (ErrorHttp e) {
            throw e;
        }
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setReadTimeout(30000);
            c.setFixedLengthStreamingMode(datos.length);
            c.setRequestProperty("Content-Type", "application/octet-stream");
            OutputStream out = c.getOutputStream();
            int pos = 0;
            while (pos < datos.length) {
                if (cancelado) throw new ErrorHttp(-1, null, "Cancelado");
                int n = Math.min(64 * 1024, datos.length - pos);
                out.write(datos, pos, n);
                pos += n;
                if (progreso != null) progreso.enviados(pos);
            }
            out.flush();
            respuesta(c);
        } catch (ErrorHttp e) {
            throw e;
        } catch (IOException e) {
            throw sinConexion(e);
        } finally {
            actual = null;
            c.disconnect();
        }
    }

    // ---------------------------------------------------------------- internos

    /** El archivo del celular se movió, se borró o cambió de tamaño. */
    public static final class ArchivoCambio extends IOException {
        public ArchivoCambio(String m) { super(m); }
    }

    private ErrorHttp sinConexion(IOException e) {
        if (cancelado) return new ErrorHttp(-1, null, "Cancelado");
        return new ErrorHttp(0, null, "Sin conexión con la PC (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
    }

    private Map<String, Object> get(String accion, Map<String, String> params) throws ErrorHttp {
        StringBuilder q = new StringBuilder(enlace.api())
            .append("?accion=").append(accion)
            .append("&t=").append(Enlace.codificar(enlace.token));
        if (params != null) {
            for (Map.Entry<String, String> e : params.entrySet()) {
                q.append('&').append(e.getKey()).append('=').append(Enlace.codificar(e.getValue()));
            }
        }
        HttpURLConnection c = abrir(q.toString());
        try {
            c.setRequestMethod("GET");
            return respuesta(c);
        } catch (ErrorHttp e) {
            throw e;
        } catch (IOException e) {
            throw sinConexion(e);
        } finally {
            actual = null;
            c.disconnect();
        }
    }

    private HttpURLConnection abrir(String url) throws ErrorHttp {
        if (cancelado) throw new ErrorHttp(-1, null, "Cancelado");
        try {
            HttpURLConnection c = conector.abrir(new URL(url));
            c.setConnectTimeout(tiempoConexionMs);
            c.setReadTimeout(tiempoLecturaMs);
            c.setUseCaches(false);
            c.setRequestProperty("Cache-Control", "no-store");
            c.setRequestProperty("User-Agent", "RespaldoWiFi-Android/1.0");
            actual = c;
            return c;
        } catch (IOException e) {
            throw sinConexion(e);
        }
    }

    private Map<String, Object> respuesta(HttpURLConnection c) throws ErrorHttp {
        int codigo;
        String cuerpo;
        try {
            codigo = c.getResponseCode();
            InputStream in = codigo >= 400 ? c.getErrorStream() : c.getInputStream();
            cuerpo = in == null ? "" : leerTodo(in);
        } catch (IOException e) {
            throw sinConexion(e);
        }
        Map<String, Object> datos = Json.objeto(cuerpo);
        if (codigo < 200 || codigo >= 300) {
            throw new ErrorHttp(codigo, datos, "HTTP " + codigo + ": " + Json.texto(datos, "error", ""));
        }
        return datos;
    }

    private static String leerTodo(InputStream in) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = i.read(buf)) > 0) b.write(buf, 0, n);
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private byte[] cabeceraMultipart(String sesion, String ruta, long tamano, long offset, long mtime) {
        StringBuilder b = new StringBuilder();
        campo(b, "t", enlace.token);
        campo(b, "sesion", sesion);
        campo(b, "ruta", ruta);
        campo(b, "tamano", String.valueOf(tamano));
        campo(b, "offset", String.valueOf(offset));
        campo(b, "mtime", String.valueOf(mtime));
        b.append("--").append(SEPARADOR).append("\r\n")
            .append("Content-Disposition: form-data; name=\"trozo\"; filename=\"trozo\"\r\n")
            .append("Content-Type: application/octet-stream\r\n\r\n");
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void campo(StringBuilder b, String nombre, String valor) {
        b.append("--").append(SEPARADOR).append("\r\n")
            .append("Content-Disposition: form-data; name=\"").append(nombre).append("\"\r\n\r\n")
            .append(valor == null ? "" : valor).append("\r\n");
    }
}
