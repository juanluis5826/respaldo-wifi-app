<?php
// Solo para pruebas: miente en "info" para forzar trozos más grandes que post_max_size (413).
if (($_GET['accion'] ?? '') === 'info') {
    header('Content-Type: application/json');
    echo json_encode(['trozo' => 4194304, 'servidor' => 'prueba413']);
    return true;
}
return require __DIR__ . '/srv/router.php';
