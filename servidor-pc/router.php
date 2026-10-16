<?php
/**
 * Router para el servidor integrado de PHP (php -S).
 * Impide que desde la red se descarguen el token o los respaldos.
 */
$ruta = rawurldecode(parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH) ?: '/');
$ruta = str_replace('\\', '/', $ruta);

if (preg_match('#(^|/)(datos|respaldos)(/|$)#i', $ruta)
    || preg_match('#(^|/)(config|router)\.php$#i', $ruta)
    || preg_match('#(^|/)\.#', $ruta)) {
    http_response_code(403);
    echo 'Prohibido';
    return true;
}

if ($ruta === '/' || $ruta === '') {
    require __DIR__ . '/index.php';
    return true;
}

return false; // servir el archivo normalmente
