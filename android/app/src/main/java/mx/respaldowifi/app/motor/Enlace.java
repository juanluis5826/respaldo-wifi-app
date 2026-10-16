package mx.respaldowifi.app.motor;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Enlace a la PC, tal como lo muestra el QR del panel:
 *   http://192.168.1.50:8000/movil.php?t=abc123...
 *   http://192.168.1.50/respaldo-wifi/movil.php?t=abc123...   (XAMPP)
 * De ahí se obtiene la carpeta base (para llamar a api.php) y el token.
 */
public final class Enlace {
    /** Termina en "/", por ejemplo "http://192.168.1.50:8000/". */
    public final String base;
    public final String token;

    public Enlace(String base, String token) {
        this.base = base.endsWith("/") ? base : base + "/";
        this.token = token;
    }

    public String api() { return base + "api.php"; }

    /** Nombre corto para mostrar: "192.168.1.50:8000". */
    public String host() {
        try {
            URI u = URI.create(base);
            return u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
        } catch (RuntimeException e) {
            return base;
        }
    }

    public static Enlace leer(String texto) {
        if (texto == null) throw new IllegalArgumentException("Enlace vacío");
        String t = texto.trim();
        if (!t.matches("(?i)^https?://.+")) {
            throw new IllegalArgumentException("El enlace debe empezar con http://");
        }
        URI u;
        try {
            u = URI.create(t.replace(" ", "%20"));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("El enlace no es válido");
        }
        if (u.getHost() == null) throw new IllegalArgumentException("El enlace no tiene dirección de la PC");

        String token = parametros(u.getRawQuery()).get("t");
        if (token == null || token.length() < 16) {
            throw new IllegalArgumentException("El enlace no trae el código de acceso (t=...). Escanea el QR del panel de la PC.");
        }

        String ruta = u.getRawPath() == null ? "/" : u.getRawPath();
        int barra = ruta.lastIndexOf('/');
        String carpeta = barra >= 0 ? ruta.substring(0, barra + 1) : "/";
        String base = u.getScheme().toLowerCase() + "://" + u.getRawAuthority() + carpeta;
        return new Enlace(base, token);
    }

    public static Map<String, String> parametros(String query) {
        Map<String, String> m = new LinkedHashMap<>();
        if (query == null) return m;
        for (String par : query.split("&")) {
            int k = par.indexOf('=');
            String c = k < 0 ? par : par.substring(0, k);
            String v = k < 0 ? "" : par.substring(k + 1);
            m.put(decodificar(c), decodificar(v));
        }
        return m;
    }

    private static String decodificar(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    public static String codificar(String s) {
        try {
            return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }
}
