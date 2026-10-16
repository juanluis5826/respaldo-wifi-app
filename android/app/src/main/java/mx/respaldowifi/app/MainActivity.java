package mx.respaldowifi.app;

import android.os.Bundle;

import androidx.activity.OnBackPressedCallback;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(RespaldoPlugin.class);
        super.onCreate(savedInstanceState);

        // El botón "Atrás" manda la app al fondo en lugar de cerrarla,
        // para no perder el acceso a los archivos elegidos.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                moveTaskToBack(true);
            }
        });
    }
}
