package mx.respaldowifi.app.motor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Lista de archivos por respaldar. Todos los métodos son seguros entre hilos. */
public final class Cola {

    public static final class Totales {
        public long total, enviados;
        public int n, listos, errores, pendientes;

        public Map<String, Object> aMapa() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("total", total);
            m.put("enviados", enviados);
            m.put("n", n);
            m.put("listos", listos);
            m.put("errores", errores);
            m.put("pendientes", pendientes);
            return m;
        }
    }

    private final List<Archivo> lista = new ArrayList<>();
    private final Set<String> claves = new HashSet<>();
    private long siguienteId = 1;

    private static String clave(String ruta, long tam) { return ruta + "|" + tam; }

    /** Agrega un archivo si no estaba ya (misma ruta y tamaño). Devuelve null si estaba repetido. */
    public synchronized Archivo agregar(String uri, String ruta, long tam, long mtime) {
        String k = clave(ruta, tam);
        if (claves.contains(k)) return null;
        claves.add(k);
        Archivo a = new Archivo();
        a.id = siguienteId++;
        a.uri = uri;
        a.ruta = ruta;
        a.tam = tam;
        a.mtime = mtime;
        lista.add(a);
        return a;
    }

    /** Siguiente archivo pendiente, o null si ya no hay. */
    public synchronized Archivo siguientePendiente() {
        for (Archivo a : lista) if (Archivo.PENDIENTE.equals(a.estado)) return a;
        return null;
    }

    public synchronized List<Archivo> copia() { return new ArrayList<>(lista); }

    public synchronized int tamano() { return lista.size(); }

    public synchronized void vaciar() {
        lista.clear();
        claves.clear();
    }

    /** Vuelve a poner en espera los que fallaron. Devuelve cuántos. */
    public synchronized int reintentarFallidos() {
        int n = 0;
        for (Archivo a : lista) {
            if (Archivo.ERROR.equals(a.estado)) {
                a.estado = Archivo.PENDIENTE;
                a.error = "";
                n++;
            }
        }
        return n;
    }

    /** Lo que estaba "Enviando" vuelve a "En espera" (al pausar). */
    public synchronized void soltarEnCurso() {
        for (Archivo a : lista) if (Archivo.SUBIENDO.equals(a.estado)) a.estado = Archivo.PENDIENTE;
    }

    public synchronized boolean hayAvanceParcial() {
        for (Archivo a : lista) if (a.enviados > 0 && Archivo.PENDIENTE.equals(a.estado)) return true;
        return false;
    }

    public synchronized Totales totales() {
        Totales t = new Totales();
        t.n = lista.size();
        for (Archivo a : lista) {
            t.total += a.tam;
            t.enviados += Math.min(a.enviados, a.tam);
            if (a.terminado()) t.listos++;
            else if (Archivo.ERROR.equals(a.estado)) t.errores++;
            else t.pendientes++;
        }
        return t;
    }

    // ------------------------------------------------------------ guardar/leer

    public synchronized String aJson() {
        List<Object> l = new ArrayList<>();
        for (Archivo a : lista) l.add(a.aMapa());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("siguienteId", siguienteId);
        m.put("archivos", l);
        return Json.escribir(m);
    }

    @SuppressWarnings("unchecked")
    public synchronized void cargarJson(String texto) {
        lista.clear();
        claves.clear();
        siguienteId = 1;
        Map<String, Object> m = Json.objeto(texto);
        Object l = m.get("archivos");
        if (l instanceof List) {
            for (Object o : (List<Object>) l) {
                if (!(o instanceof Map)) continue;
                Archivo a = Archivo.deMapa((Map<String, Object>) o);
                lista.add(a);
                claves.add(clave(a.ruta, a.tam));
                siguienteId = Math.max(siguienteId, a.id + 1);
            }
        }
        siguienteId = Math.max(siguienteId, Json.entero(m, "siguienteId", 1));
    }
}
