import mx.respaldowifi.app.motor.*;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Pruebas del motor de subida contra el api.php real (php -S). */
public class PruebaMotor {
    static Path RAIZ = Paths.get(System.getProperty("raiz"));
    static Path SRV = RAIZ.resolve("srv");
    static Path ORIG = RAIZ.resolve("orig");
    static Process php;
    static int fallos = 0;
    static int PUERTO = 8765;

    public static void main(String[] a) throws Exception {
        crearArchivos();
        Enlace e = arrancar("2M", "3M", null);

        prueba("Enlace del QR se interpreta bien", () -> {
            Enlace x = Enlace.leer("http://192.168.1.50/respaldo-wifi/movil.php?t=0123456789abcdef01234567");
            check(x.base.equals("http://192.168.1.50/respaldo-wifi/"), "base " + x.base);
            check(x.token.equals("0123456789abcdef01234567"), "token");
            Enlace y = Enlace.leer(" http://10.0.0.5:8000/movil.php?t=0123456789abcdef01234567 ");
            check(y.api().equals("http://10.0.0.5:8000/api.php"), "api " + y.api());
            boolean fallo = false;
            try { Enlace.leer("http://10.0.0.5:8000/movil.php"); } catch (IllegalArgumentException ex) { fallo = true; }
            check(fallo, "sin token debe fallar");
        });

        prueba("Subida normal: todo llega idéntico y con su fecha", () -> {
            Cola c = colaDe("Celular A 2026-10-16");
            Motor.Fin f = motor(e, c, null).ejecutar("Celular A 2026-10-16");
            check(f == Motor.Fin.COMPLETO, "fin " + f);
            verificar("Celular A 2026-10-16", true);
            check(c.totales().listos == c.tamano(), "todos listos");
        });

        prueba("Segunda vez: no duplica, marca 'Ya estaba'", () -> {
            Cola c = colaDe("x");
            Motor.Fin f = motor(e, c, null).ejecutar("Celular A 2026-10-16");
            check(f == Motor.Fin.COMPLETO, "fin " + f);
            for (Archivo x : c.copia()) check(Archivo.OMITIDO.equals(x.estado), x.ruta + " -> " + x.estado);
        });

        prueba("Pausar a la mitad y continuar sin volver a empezar", () -> {
            Cola c = new Cola();
            agregar(c, "grande.bin", "Videos/grande.bin");
            AtomicLong bytesLeidos = new AtomicLong();
            Motor[] m = new Motor[1];
            m[0] = motor(e, c, bytesLeidos);
            Thread pausador = new Thread(() -> {
                while (c.copia().get(0).enviados < 12_000_000) dormir(20);
                m[0].pausar();
            });
            pausador.start();
            Motor.Fin f = m[0].ejecutar("Pausa 2026-10-16");
            pausador.join();
            check(f == Motor.Fin.PAUSADO, "fin " + f);
            long parcial = Files.size(SRV.resolve("respaldos/Pausa 2026-10-16/Videos/grande.bin.part"));
            check(parcial > 8_000_000, "parcial en PC " + parcial);
            long antes = bytesLeidos.get();
            Motor.Fin f2 = motor(e, c, bytesLeidos).ejecutar("Pausa 2026-10-16");
            check(f2 == Motor.Fin.COMPLETO, "fin2 " + f2);
            long total = Files.size(ORIG.resolve("grande.bin"));
            long reenviado = bytesLeidos.get() - antes;
            check(reenviado <= total - parcial + 1, "reenvió " + reenviado + " de " + (total - parcial) + " que faltaban");
            igual(ORIG.resolve("grande.bin"), SRV.resolve("respaldos/Pausa 2026-10-16/Videos/grande.bin"));
        });

        prueba("La PC se apaga a la mitad: reintenta y termina", () -> {
            Cola c = new Cola();
            agregar(c, "grande.bin", "Videos/grande.bin");
            Motor m = motor(e, c, null);
            m.factorEspera = 0.5;
            Thread corte = new Thread(() -> {
                while (c.copia().get(0).enviados < 10_000_000) dormir(10);
                try {
                    php.destroyForcibly().waitFor();
                    System.out.println("    (servidor apagado)");
                    dormir(5000);
                    arrancar("2M", "3M", null);
                    System.out.println("    (servidor encendido de nuevo)");
                } catch (Exception ex) { throw new RuntimeException(ex); }
            });
            corte.start();
            Motor.Fin f = m.ejecutar("Corte 2026-10-16");
            corte.join();
            check(f == Motor.Fin.COMPLETO, "fin " + f);
            igual(ORIG.resolve("grande.bin"), SRV.resolve("respaldos/Corte 2026-10-16/Videos/grande.bin"));
        });

        prueba("Desfase 409: la PC tiene menos de lo esperado y se reenvía desde ahí", () -> {
            Cola c = new Cola();
            agregar(c, "mediano.bin", "Otros/mediano.bin");
            Path part = SRV.resolve("respaldos/Desfase 2026-10-16/Otros/mediano.bin.part");
            boolean[] hecho = {false};
            Motor[] m = new Motor[1];
            m[0] = new Motor(c, new ClienteApi(e, null), PruebaMotor::abrir, new Motor.Oyente() {
                public void cambio(Archivo x) { }
                public void progreso() {
                    try {
                        // Cuando ya llegaron 2 trozos, recortar el .part en la PC para provocar 409.
                        if (!hecho[0] && Files.exists(part) && Files.size(part) > 3_000_000) {
                            try (RandomAccessFile r = new RandomAccessFile(part.toFile(), "rw")) { r.setLength(1_000_000); }
                            hecho[0] = true;
                            System.out.println("    (.part recortado a 1 MB)");
                        }
                    } catch (IOException ex) { throw new UncheckedIOException(ex); }
                }
            });
            Motor.Fin f = m[0].ejecutar("Desfase 2026-10-16");
            check(hecho[0], "se provocó el desfase");
            check(f == Motor.Fin.COMPLETO, "fin " + f);
            igual(ORIG.resolve("mediano.bin"), SRV.resolve("respaldos/Desfase 2026-10-16/Otros/mediano.bin"));
        });

        prueba("Mismo nombre, distinto contenido: se guarda como (2)", () -> {
            Path otro = ORIG.resolve("otra_foto.jpg");
            Files.write(otro, aleatorio(123_457));
            Cola c = new Cola();
            c.agregar(otro.toUri().toString(), "Fotos/foto ñandú año.jpg", Files.size(otro), Files.getLastModifiedTime(otro).toMillis());
            Motor.Fin f = motor(e, c, null).ejecutar("Celular A 2026-10-16");
            check(f == Motor.Fin.COMPLETO, "fin " + f);
            igual(otro, SRV.resolve("respaldos/Celular A 2026-10-16/Fotos/foto ñandú año (2).jpg"));
        });

        prueba("Token equivocado: se detiene con 'enlace vencido'", () -> {
            Enlace malo = new Enlace(e.base, "ffffffffffffffffffffffff");
            Cola c = colaDe("x");
            Motor.Fin f = motor(malo, c, null).ejecutar("Malo");
            check(f == Motor.Fin.ENLACE_VENCIDO, "fin " + f);
        });

        prueba("PC apagada: avisa 'sin conexión' y deja todo en espera", () -> {
            Enlace nadie = new Enlace("http://127.0.0.1:1/", e.token);
            Cola c = colaDe("x");
            Motor m = motor(nadie, c, null);
            m.factorEspera = 0.01;
            Motor.Fin f = m.ejecutar("Nadie");
            check(f == Motor.Fin.SIN_CONEXION, "fin " + f);
            check(c.totales().pendientes == c.tamano(), "todo pendiente");
        });

        prueba("Medidor: ping, prueba y resultado", () -> {
            ClienteApi api = new ClienteApi(e, null);
            api.ping();
            api.prueba(aleatorio(4 * 1024 * 1024), null);
            api.resultado(12.34, 5, "Celular de prueba");
            String v = new String(Files.readAllBytes(SRV.resolve("datos/velocidad.json")), "UTF-8");
            check(v.contains("12.34") && v.contains("Celular de prueba"), v);
        });

        prueba("Guardar y recuperar la lista (si Android cierra la app)", () -> {
            Cola c = colaDe("x");
            c.copia().get(0).estado = Archivo.SUBIENDO;
            c.copia().get(1).estado = Archivo.ERROR;
            c.copia().get(1).error = "Algo \"raro\" ñ";
            Cola d = new Cola();
            d.cargarJson(c.aJson());
            check(d.tamano() == c.tamano(), "tamaño");
            check(Archivo.PENDIENTE.equals(d.copia().get(0).estado), "subiendo -> pendiente");
            check(d.copia().get(1).error.equals("Algo \"raro\" ñ"), "error " + d.copia().get(1).error);
            check(d.agregar(c.copia().get(2).uri, c.copia().get(2).ruta, c.copia().get(2).tam, 0) == null, "no duplica");
        });

        php.destroyForcibly().waitFor();

        prueba("413: la PC acepta menos de lo anunciado, el trozo se reduce solo", () -> {
            Enlace e2 = arrancar("1M", "1M", "router_413.php");
            Cola c = new Cola();
            agregar(c, "mediano.bin", "Otros/mediano.bin");
            Motor m = motor(e2, c, null);
            Motor.Fin f = m.ejecutar("Trozo 2026-10-16");
            check(f == Motor.Fin.COMPLETO, "fin " + f);
            check(m.trozo() < 1024 * 1024, "trozo final " + m.trozo());
            igual(ORIG.resolve("mediano.bin"), SRV.resolve("respaldos/Trozo 2026-10-16/Otros/mediano.bin"));
        });
        php.destroyForcibly().waitFor();

        System.out.println(fallos == 0 ? "\nTODAS LAS PRUEBAS PASARON" : "\nFALLARON " + fallos);
        System.exit(fallos == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ apoyo

    interface Cuerpo { void correr() throws Exception; }

    static void prueba(String nombre, Cuerpo c) {
        long t = System.currentTimeMillis();
        try {
            c.correr();
            System.out.println("OK   " + nombre + "  (" + (System.currentTimeMillis() - t) + " ms)");
        } catch (Throwable ex) {
            fallos++;
            System.out.println("FALLA " + nombre + ": " + ex);
        }
    }

    static void check(boolean b, String m) { if (!b) throw new AssertionError(m); }

    static void dormir(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ex) { } }

    static byte[] aleatorio(int n) { byte[] b = new byte[n]; new Random(n).nextBytes(b); return b; }

    static String[][] ARCHIVOS = {
        {"vacio.txt", "Documentos/vacio.txt", "0"},
        {"uno.txt", "Documentos/uno.txt", "1"},
        {"foto.jpg", "Fotos/foto ñandú año.jpg", "777777"},
        {"mediano.bin", "Otros/mediano.bin", "6000000"},
        {"grande.bin", "Videos/grande.bin", "31000000"},
        {"cam.jpg", "DCIM/Camera/IMG_20261016_0001.jpg", "2500001"},
    };

    static void crearArchivos() throws IOException {
        Files.createDirectories(ORIG);
        long fecha = 1_700_000_000_000L;
        for (String[] f : ARCHIVOS) {
            Path p = ORIG.resolve(f[0]);
            Files.write(p, aleatorio(Integer.parseInt(f[2])));
            Files.setLastModifiedTime(p, java.nio.file.attribute.FileTime.fromMillis(fecha));
            fecha += 86_400_000L;
        }
    }

    static Cola colaDe(String s) throws IOException {
        Cola c = new Cola();
        for (String[] f : ARCHIVOS) agregar(c, f[0], f[1]);
        return c;
    }

    static void agregar(Cola c, String nombre, String ruta) throws IOException {
        Path p = ORIG.resolve(nombre);
        c.agregar(p.toUri().toString(), ruta, Files.size(p), Files.getLastModifiedTime(p).toMillis());
    }

    static AtomicLong contador;

    static Motor motor(Enlace e, Cola c, AtomicLong leidos) {
        return new Motor(c, new ClienteApi(e, null), (arch, off) -> {
            InputStream in = abrir(arch, off);
            if (leidos == null) return in;
            return new FilterInputStream(in) {
                @Override public int read(byte[] b, int o, int l) throws IOException {
                    int n = super.read(b, o, l);
                    if (n > 0) leidos.addAndGet(n);
                    return n;
                }
            };
        }, new Motor.Oyente() {
            public void cambio(Archivo a) { }
            public void progreso() { }
        });
    }

    static InputStream abrir(Archivo a, long off) throws IOException {
        FileInputStream in = new FileInputStream(new File(java.net.URI.create(a.uri)));
        in.getChannel().position(off);
        return new BufferedInputStream(in, 256 * 1024);
    }

    static void verificar(String sesion, boolean fechas) throws Exception {
        long fecha = 1_700_000_000_000L;
        for (String[] f : ARCHIVOS) {
            Path dest = SRV.resolve("respaldos").resolve(sesion).resolve(f[1]);
            igual(ORIG.resolve(f[0]), dest);
            if (fechas) {
                long m = Files.getLastModifiedTime(dest).toMillis();
                check(m / 1000 == fecha / 1000, "fecha de " + f[1] + ": " + m + " vs " + fecha);
            }
            fecha += 86_400_000L;
        }
    }

    static void igual(Path a, Path b) throws Exception {
        check(Files.exists(b), "no existe en la PC: " + b);
        check(sha(a).equals(sha(b)), "contenido distinto: " + b);
    }

    static String sha(Path p) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) d.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(d.digest());
    }

    static Enlace arrancar(String upload, String post, String router) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("php", "-d", "upload_max_filesize=" + upload, "-d", "post_max_size=" + post,
            "-d", "max_execution_time=0", "-S", "127.0.0.1:" + PUERTO, "-t", SRV.toString(),
            router == null ? SRV.resolve("router.php").toString() : RAIZ.resolve(router).toString());
        pb.redirectErrorStream(true).redirectOutput(RAIZ.resolve("php.log").toFile());
        php = pb.start();
        for (int i = 0; i < 50; i++) {
            try (java.net.Socket s = new java.net.Socket("127.0.0.1", PUERTO)) { break; } catch (IOException ex) { dormir(100); }
        }
        // El token se crea en la primera petición; se lee como lo hace el panel.
        try { new java.net.URL("http://127.0.0.1:" + PUERTO + "/api.php?accion=ping&t=x").openConnection().getInputStream().close(); } catch (IOException ex) { /* 401 esperado */ }
        return enlaceActual();
    }

    static Enlace enlaceActual() throws IOException {
        String token;
        try {
            token = new String(Files.readAllBytes(SRV.resolve("datos/token.txt"))).trim();
        } catch (NoSuchFileException ex) {
            throw ex;
        }
        return Enlace.leer("http://127.0.0.1:" + PUERTO + "/movil.php?t=" + token);
    }
}
