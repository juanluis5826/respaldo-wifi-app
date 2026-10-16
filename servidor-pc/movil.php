<?php
/**
 * Respaldo WiFi - página que se abre en el celular.
 */
declare(strict_types=1);
require __DIR__ . '/config.php';

$token = (string) ($_GET['t'] ?? '');
$valido = hash_equals(obtenerToken(), $token);
header('Cache-Control: no-store');
?>
<!doctype html>
<html lang="es">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="theme-color" content="#0f7b5f">
<title>Respaldo WiFi</title>
<link rel="stylesheet" href="assets/estilos.css">
</head>
<body>
<div class="contenedor estrecho">
  <header class="titulo">
    <div class="logo" aria-hidden="true">
      <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12.5a10 10 0 0 1 14 0"/><path d="M8.5 16a5 5 0 0 1 7 0"/><circle cx="12" cy="19.5" r="1"/><path d="M1.5 9a15 15 0 0 1 21 0"/></svg>
    </div>
    <div>
      <h1>Respaldo WiFi</h1>
      <p class="subtitulo" id="conexion">Conectando con la PC…</p>
    </div>
  </header>

<?php if (!$valido): ?>
  <div class="tarjeta">
    <h2>Enlace no válido</h2>
    <p class="nota">Este enlace venció o está incompleto. Vuelve a escanear el código QR que aparece en la pantalla de la PC.</p>
  </div>
<?php else: ?>
  <section class="tarjeta">
    <label class="campo" for="dispositivo">Nombre de este celular</label>
    <input type="text" id="dispositivo" maxlength="40" placeholder="Ej. Celular de Juan" autocomplete="off">
    <p class="nota" style="margin:6px 0 0">En la PC se crea una carpeta con este nombre y la fecha de hoy.</p>
  </section>

  <section class="tarjeta">
    <h2>¿Qué quieres respaldar?</h2>
    <div class="selectores">
      <label class="boton">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="18" height="18" rx="3"/><circle cx="9" cy="9" r="2"/><path d="m21 15-5-5L5 21"/></svg>
        Fotos y videos
        <input type="file" id="selGaleria" class="oculto" accept="image/*,video/*" multiple>
      </label>
      <label class="boton">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5"/></svg>
        Archivos
        <input type="file" id="selArchivos" class="oculto" multiple>
      </label>
      <label class="boton ancho" id="btnCarpeta">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/></svg>
        Carpeta completa (ej. DCIM, Download, WhatsApp Media)
        <input type="file" id="selCarpeta" class="oculto" webkitdirectory directory multiple>
      </label>
      <button type="button" class="boton ancho oculto" id="btnContactos">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/></svg>
        Contactos
      </button>
    </div>
    <p class="nota" style="margin:10px 0 0">Puedes combinar: elige fotos, luego archivos, y todo se agrega a la lista.</p>
  </section>

  <section class="tarjeta" id="panelVelocidad">
    <h2>Velocidad de tu WiFi</h2>
    <div class="medidor">
      <svg viewBox="0 0 240 132" role="img" aria-labelledby="mVelocidad">
        <path class="medidor-fondo" d="M20 120 A100 100 0 0 1 220 120" pathLength="100"/>
        <path class="medidor-valor" id="mArco" d="M20 120 A100 100 0 0 1 220 120" pathLength="100" stroke-dasharray="0 100"/>
        <text x="20" y="131" class="medidor-escala" text-anchor="middle">0</text>
        <text x="120" y="12" class="medidor-escala" text-anchor="middle">10</text>
        <text x="220" y="131" class="medidor-escala" text-anchor="middle">40+</text>
      </svg>
      <div class="medidor-lectura">
        <b id="mVelocidad">–</b>
        <span>MB/s</span>
      </div>
    </div>
    <p class="medidor-calificacion" id="mCalificacion">Mide la velocidad real entre este celular y la PC.</p>
    <div class="cifras" id="mDetalles">
      <div><b id="mPing">–</b><span>respuesta</span></div>
      <div><b id="mMbits">–</b><span>megabits/s</span></div>
      <div><b id="mEstimado">–</b><span id="mEstimadoEtiqueta">para 10 GB</span></div>
    </div>
    <button type="button" class="grande" id="btnMedir">Medir velocidad (8 s)</button>
    <p class="nota oculto" id="mConsejo" style="margin:10px 0 0"></p>
  </section>

  <section class="tarjeta" id="panelCola">
    <h2 id="resumenCola">Sin archivos seleccionados</h2>
    <div class="barra" aria-hidden="true"><div id="barraTotal"></div></div>
    <div class="cifras">
      <div><b id="cPorcentaje">0%</b><span>avance</span></div>
      <div><b id="cVelocidad">–</b><span>velocidad</span></div>
      <div><b id="cRestante">–</b><span>restante</span></div>
    </div>
    <div class="botones" style="margin-bottom:12px">
      <button type="button" class="primario grande" id="btnIniciar" disabled>Iniciar respaldo</button>
      <button type="button" id="btnPausar" class="oculto">Pausar</button>
      <button type="button" id="btnReintentar" class="oculto">Reintentar fallidos</button>
      <button type="button" id="btnLimpiar" class="oculto">Vaciar lista</button>
    </div>
    <div class="aviso oculto" id="avisoPantalla">Mantén la pantalla encendida y esta página abierta hasta que termine. Si se bloquea, vuelve a abrirla y selecciona los mismos archivos: continuará donde se quedó.</div>
    <div class="error-caja oculto" id="cajaError" style="margin-top:10px"></div>
    <ul class="lista" id="lista" style="margin-top:10px"></ul>
    <p class="nota oculto" id="masItems"></p>
  </section>

  <p class="nota">Solo se envía lo que tú eliges. Los archivos viajan por tu red WiFi directo a la PC; no pasan por internet.</p>

  <script>window.RESPALDO = { token: <?= json_encode($token) ?> };</script>
  <script src="assets/movil.js"></script>
<?php endif; ?>
</div>
</body>
</html>
