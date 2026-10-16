<?php
/**
 * Respaldo WiFi - panel de la PC (muestra el QR y los respaldos recibidos).
 */
declare(strict_types=1);
require __DIR__ . '/config.php';

if (!esLocal()) {
    // Desde el celular u otra PC no se muestra el panel (contiene el token).
    http_response_code(403);
    header('Content-Type: text/html; charset=utf-8');
    echo '<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">'
       . '<title>Respaldo WiFi</title><link rel="stylesheet" href="assets/estilos.css">'
       . '<div class="contenedor estrecho"><div class="tarjeta"><h2>Este panel solo se abre en la PC</h2>'
       . '<p class="nota">Para respaldar tu celular, escanea el código QR que aparece en la pantalla de la PC.</p></div></div>';
    exit;
}

$token = obtenerToken();
$puerto = (int) ($_SERVER['SERVER_PORT'] ?? 8000);
$ips = ipsLocales();
$ruta = rtrim(str_replace('\\', '/', dirname($_SERVER['SCRIPT_NAME'] ?? '/')), '/');
if (str_ends_with($ruta, '/router.php')) {
    $ruta = '';
}
?>
<!doctype html>
<html lang="es">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Respaldo WiFi</title>
<link rel="stylesheet" href="assets/estilos.css">
</head>
<body>
<div class="contenedor">
  <header class="titulo">
    <div class="logo" aria-hidden="true">
      <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12.5a10 10 0 0 1 14 0"/><path d="M8.5 16a5 5 0 0 1 7 0"/><circle cx="12" cy="19.5" r="1"/><path d="M1.5 9a15 15 0 0 1 21 0"/></svg>
    </div>
    <div>
      <h1>Respaldo WiFi</h1>
      <p class="subtitulo">Copia los archivos de tu celular a esta PC por la red de tu casa</p>
    </div>
  </header>

  <div class="rejilla">
    <section class="tarjeta">
      <h2>1. Escanea con tu celular</h2>
      <div class="qr-caja" id="qr"></div>
      <div class="url" id="url"></div>
      <div class="botones">
        <button type="button" id="copiar">Copiar enlace</button>
        <button type="button" id="regenerar" title="El QR anterior dejará de funcionar">Nuevo enlace</button>
      </div>
      <div style="margin-top:14px">
        <label class="campo" for="ip">Red de esta PC</label>
        <select id="ip">
          <?php foreach ($ips as $ip): ?>
            <option value="<?= htmlspecialchars($ip) ?>"><?= htmlspecialchars($ip) ?></option>
          <?php endforeach; ?>
          <option value="__manual">Escribir otra IP…</option>
        </select>
        <p class="nota" style="margin:6px 0 0">Si el celular no abre la página, prueba con otra IP de la lista.</p>
      </div>
      <?php if (!$ips): ?>
        <p class="aviso" style="margin-top:12px">No se detectó la IP de la red. Abre una terminal, escribe <b>ipconfig</b> y usa la “Dirección IPv4” del adaptador WiFi.</p>
      <?php endif; ?>
    </section>

    <section class="tarjeta">
      <h2>2. Respaldos recibidos <span class="nota" id="vivo"><span class="punto"></span>en vivo</span></h2>
      <div id="tabla"><p class="vacio">Cargando…</p></div>
      <p class="nota" style="margin:14px 0 4px">Los archivos se guardan en:</p>
      <div class="ruta-carpeta" id="carpeta"><?= htmlspecialchars(DIR_RESPALDOS) ?></div>

      <h2 style="margin-top:22px">Velocidad de la red</h2>
      <div id="velocidad"><p class="nota" style="margin:0">Aún no hay mediciones. En el celular toca <b>Medir velocidad</b>.</p></div>

      <h2 style="margin-top:22px">Cómo usarlo</h2>
      <ol class="pasos">
        <li>Conecta el celular a la <b>misma red WiFi</b> que esta PC.</li>
        <li>Escanea el código QR con la cámara del celular y abre el enlace.</li>
        <li>Elige fotos, videos, archivos o una carpeta completa y toca <b>Iniciar respaldo</b>.</li>
        <li>Deja la pantalla del celular encendida hasta que termine. Si se corta, vuelve a elegir los mismos archivos: continúa donde se quedó y omite los que ya llegaron.</li>
      </ol>
    </section>
  </div>
</div>

<script src="assets/qrcode.js"></script>
<script>
  window.RESPALDO = {
    token: <?= json_encode($token) ?>,
    puerto: <?= $puerto ?>,
    ruta: <?= json_encode($ruta) ?>
  };
</script>
<script src="assets/pc.js"></script>
</body>
</html>
