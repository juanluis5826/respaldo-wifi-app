package mx.respaldowifi.app.motor;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lector y escritor de JSON muy pequeño (sin librerías externas).
 * Objetos -> Map<String,Object>, arreglos -> List<Object>, números -> Double/Long,
 * texto -> String, true/false -> Boolean, null -> null.
 */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    public static Object leer(String texto) {
        if (texto == null) return null;
        Json j = new Json(texto);
        j.espacios();
        if (j.i >= j.s.length()) return null;
        Object v = j.valor();
        return v;
    }

    /** Lee un objeto; si el texto no es un objeto JSON devuelve un mapa vacío. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> objeto(String texto) {
        try {
            Object v = leer(texto);
            if (v instanceof Map) return (Map<String, Object>) v;
        } catch (RuntimeException ignorado) { /* respuesta no JSON */ }
        return new LinkedHashMap<>();
    }

    public static long entero(Map<String, Object> m, String clave, long porDefecto) {
        Object v = m.get(clave);
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) {
            try { return Long.parseLong((String) v); } catch (NumberFormatException e) { return porDefecto; }
        }
        return porDefecto;
    }

    public static boolean logico(Map<String, Object> m, String clave) {
        Object v = m.get(clave);
        return v instanceof Boolean ? (Boolean) v : false;
    }

    public static String texto(Map<String, Object> m, String clave, String porDefecto) {
        Object v = m.get(clave);
        return v == null ? porDefecto : String.valueOf(v);
    }

    // ------------------------------------------------------------------ lector

    private void espacios() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private Object valor() {
        espacios();
        if (i >= s.length()) throw new IllegalArgumentException("JSON incompleto");
        char c = s.charAt(i);
        switch (c) {
            case '{': return leerObjeto();
            case '[': return leerArreglo();
            case '"': return leerTexto();
            case 't': esperar("true"); return Boolean.TRUE;
            case 'f': esperar("false"); return Boolean.FALSE;
            case 'n': esperar("null"); return null;
            default: return leerNumero();
        }
    }

    private void esperar(String palabra) {
        if (!s.startsWith(palabra, i)) throw new IllegalArgumentException("JSON inválido en " + i);
        i += palabra.length();
    }

    private Map<String, Object> leerObjeto() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; // {
        espacios();
        if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
        while (true) {
            espacios();
            String clave = leerTexto();
            espacios();
            if (s.charAt(i) != ':') throw new IllegalArgumentException("Falta ':'");
            i++;
            m.put(clave, valor());
            espacios();
            char c = s.charAt(i++);
            if (c == '}') return m;
            if (c != ',') throw new IllegalArgumentException("Falta ','");
        }
    }

    private List<Object> leerArreglo() {
        List<Object> l = new ArrayList<>();
        i++; // [
        espacios();
        if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
        while (true) {
            l.add(valor());
            espacios();
            char c = s.charAt(i++);
            if (c == ']') return l;
            if (c != ',') throw new IllegalArgumentException("Falta ','");
        }
    }

    private String leerTexto() {
        if (s.charAt(i) != '"') throw new IllegalArgumentException("Se esperaba texto");
        i++;
        StringBuilder b = new StringBuilder();
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') { b.append(c); continue; }
            char e = s.charAt(i++);
            switch (e) {
                case 'n': b.append('\n'); break;
                case 't': b.append('\t'); break;
                case 'r': b.append('\r'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                default: b.append(e);
            }
        }
    }

    private Object leerNumero() {
        int ini = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        String n = s.substring(ini, i);
        if (n.isEmpty()) throw new IllegalArgumentException("JSON inválido en " + ini);
        if (n.indexOf('.') < 0 && n.indexOf('e') < 0 && n.indexOf('E') < 0) {
            try { return Long.parseLong(n); } catch (NumberFormatException ignorado) { /* muy grande */ }
        }
        return Double.parseDouble(n);
    }

    // ---------------------------------------------------------------- escritor

    public static String escribir(Object v) {
        StringBuilder b = new StringBuilder();
        escribir(b, v);
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    private static void escribir(StringBuilder b, Object v) {
        if (v == null) { b.append("null"); return; }
        if (v instanceof String) { comillas(b, (String) v); return; }
        if (v instanceof Boolean || v instanceof Long || v instanceof Integer) { b.append(v); return; }
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (d == Math.rint(d) && Math.abs(d) < 1e15) b.append((long) d); else b.append(d);
            return;
        }
        if (v instanceof Map) {
            b.append('{');
            Iterator<Map.Entry<String, Object>> it = ((Map<String, Object>) v).entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Object> e = it.next();
                comillas(b, e.getKey());
                b.append(':');
                escribir(b, e.getValue());
                if (it.hasNext()) b.append(',');
            }
            b.append('}');
            return;
        }
        if (v instanceof List) {
            b.append('[');
            List<Object> l = (List<Object>) v;
            for (int k = 0; k < l.size(); k++) {
                if (k > 0) b.append(',');
                escribir(b, l.get(k));
            }
            b.append(']');
            return;
        }
        comillas(b, String.valueOf(v));
    }

    private static void comillas(StringBuilder b, String t) {
        b.append('"');
        for (int k = 0; k < t.length(); k++) {
            char c = t.charAt(k);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c);
            }
        }
        b.append('"');
    }
}
