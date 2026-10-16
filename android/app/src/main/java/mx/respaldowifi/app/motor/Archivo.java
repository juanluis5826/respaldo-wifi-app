package mx.respaldowifi.app.motor;

import java.util.LinkedHashMap;
import java.util.Map;

/** Un archivo de la lista de respaldo (equivale a cada elemento de "cola" en movil.js). */
public final class Archivo {
    public static final String PENDIENTE = "pendiente";
    public static final String SUBIENDO = "subiendo";
    public static final String LISTO = "listo";
    public static final String OMITIDO = "omitido";   // ya estaba completo en la PC
    public static final String ERROR = "error";

    public long id;
    /** content://... o file://... (cómo leerlo en el celular). */
    public String uri;
    /** Ruta relativa en la PC, ej. "Fotos/IMG_001.jpg" o "DCIM/Camera/IMG_001.jpg". */
    public String ruta;
    public long tam;
    /** Fecha de modificación en milisegundos (igual que File.lastModified en JS). */
    public long mtime;
    public String estado = PENDIENTE;
    public long enviados;
    public String error = "";
    /** Carpeta de la PC: "Nombre del celular AAAA-MM-DD". Se fija al empezar a subirlo. */
    public String sesion;
    public boolean yaIniciado;

    public boolean terminado() {
        return LISTO.equals(estado) || OMITIDO.equals(estado);
    }

    public Map<String, Object> aMapa() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("uri", uri);
        m.put("ruta", ruta);
        m.put("tam", tam);
        m.put("mtime", mtime);
        m.put("estado", estado);
        m.put("enviados", enviados);
        m.put("error", error);
        m.put("sesion", sesion);
        m.put("yaIniciado", yaIniciado);
        return m;
    }

    public static Archivo deMapa(Map<String, Object> m) {
        Archivo a = new Archivo();
        a.id = Json.entero(m, "id", 0);
        a.uri = Json.texto(m, "uri", "");
        a.ruta = Json.texto(m, "ruta", "archivo");
        a.tam = Json.entero(m, "tam", 0);
        a.mtime = Json.entero(m, "mtime", 0);
        a.estado = Json.texto(m, "estado", PENDIENTE);
        a.enviados = Json.entero(m, "enviados", 0);
        a.error = Json.texto(m, "error", "");
        Object s = m.get("sesion");
        a.sesion = s == null ? null : String.valueOf(s);
        a.yaIniciado = Json.logico(m, "yaIniciado");
        if (SUBIENDO.equals(a.estado)) a.estado = PENDIENTE;   // se cortó a la mitad
        return a;
    }
}
