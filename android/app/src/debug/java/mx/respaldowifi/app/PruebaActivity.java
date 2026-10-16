package mx.respaldowifi.app;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import java.io.File;
import java.util.Map;

import mx.respaldowifi.app.motor.ClienteApi;
import mx.respaldowifi.app.motor.Enlace;
import mx.respaldowifi.app.motor.Json;

/**
 * Solo para pruebas automáticas (adb):
 *   am start -n mx.respaldowifi.app/.PruebaActivity --es enlace "http://10.0.2.2:8000/movil.php?t=..." --es carpeta prueba
 * Agrega todos los archivos de files/<carpeta> a la lista e inicia el respaldo.
 */
public class PruebaActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String accion = getIntent().getStringExtra("accion");
        if ("pausar".equals(accion)) {
            ServicioRespaldo.pausar(this);
            finish();
            return;
        }
        if ("continuar".equals(accion)) {
            ServicioRespaldo.iniciar(this);
            finish();
            return;
        }
        String enlace = getIntent().getStringExtra("enlace");
        String carpeta = getIntent().getStringExtra("carpeta");
        Almacen a = Almacen.de(this);
        try {
            Enlace e = Enlace.leer(enlace);
            final Map<String, Object>[] info = new Map[1];
            Thread t = new Thread(() -> {
                try { info[0] = new ClienteApi(e, AndroidIO.conectorWifi(this)).info(); } catch (Exception ex) { Log.e("RespaldoWiFi", "PRUEBA info", ex); }
            });
            t.start();
            t.join(15000);
            if (info[0] == null) { Log.e("RespaldoWiFi", "PRUEBA: no hay conexión con la PC"); finish(); return; }
            a.guardarEnlace(e, Json.texto(info[0], "servidor", "PC"), (int) Json.entero(info[0], "trozo", 1536 * 1024));
            a.guardarDispositivo("Emulador");
            a.cola.vaciar();
            File raiz = new File(getFilesDir(), carpeta == null ? "prueba" : carpeta);
            agregar(a, raiz, "Prueba");
            a.guardar();
            Log.i("RespaldoWiFi", "PRUEBA: " + a.cola.tamano() + " archivos en la lista");
            ServicioRespaldo.iniciar(this);
        } catch (Exception ex) {
            Log.e("RespaldoWiFi", "PRUEBA falló", ex);
        }
        finish();
    }

    private void agregar(Almacen a, File dir, String ruta) {
        File[] hijos = dir.listFiles();
        if (hijos == null) return;
        for (File f : hijos) {
            if (f.isDirectory()) agregar(a, f, ruta + "/" + f.getName());
            else a.cola.agregar(Uri.fromFile(f).toString(), ruta + "/" + f.getName(), f.length(), f.lastModified());
        }
    }
}
