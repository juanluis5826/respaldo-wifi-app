<?php
// Solo para la prueba en el emulador: hace la subida más lenta (como un WiFi real)
// para que dé tiempo de apagar la pantalla a la mitad.
if (($_GET['accion'] ?? '') === 'subir') {
    usleep(400000);
}
return require getenv('SRV_DIR') . '/router.php';
