<?php
/**
 * Respaldo WiFi - API.
 *
 * Acciones desde el celular (requieren token):
 *   info    -> tamaño de trozo a usar
 *   estado  -> cuántos bytes de un archivo ya llegaron (para reanudar)
 *   subir   -> recibe un trozo y lo agrega al archivo
 * Acciones solo desde la PC (127.0.0.1):
 *   listar     -> respaldos recibidos
 *   regenerar  -> crea un enlace nuevo (invalida el QR anterior)
 */
declare(strict_types=1);
require __DIR__ . '/config.php';

@set_time_limit(0);
$accion = $_GET['accion'] ?? '';

// ---------- Acciones de la PC ----------
if ($accion === 'listar' || $accion === 'regenerar') {
    if (!esLocal()) {
        responder(['error' => 'Solo disponible desde la PC'], 403);
    }
    if ($accion === 'regenerar') {
        if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
            responder(['error' => 'Usa POST'], 405);
        }
        responder(['token' => obtenerToken(true)]);
    }
    responder(['carpeta' => DIR_RESPALDOS, 'respaldos' => listarRespaldos(), 'velocidad' => leerVelocidad()]);
}

// ---------- Acciones del celular ----------
$token = (string) ($_REQUEST['t'] ?? '');
if (!hash_equals(obtenerToken(), $token)) {
    responder(['error' => 'Enlace no válido o vencido. Escanea de nuevo el código QR.'], 401);
}

switch ($accion) {
    case 'info':
        responder(['trozo' => tamanoTrozo(), 'servidor' => NOMBRE_PC !== '' ? NOMBRE_PC : (gethostname() ?: 'PC')]);

    case 'estado':
        [$destino, $tamano] = datosArchivo();
        responder(estadoArchivo($destino, $tamano));

    case 'subir':
        if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
            responder(['error' => 'Usa POST'], 405);
        }
        if (empty($_POST) && (int) ($_SERVER['CONTENT_LENGTH'] ?? 0) > 0) {
            responder(['error' => 'El trozo es más grande que post_max_size de PHP'], 413);
        }
        [$destino, $tamano] = datosArchivo();
        recibirTrozo($destino, $tamano);

    case 'ping':
        responder(['ok' => true]);

    case 'prueba':
        // Prueba de velocidad: recibe datos y los descarta (no se guarda nada).
        if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
            responder(['error' => 'Usa POST'], 405);
        }
        $entrada = fopen('php://input', 'rb');
        $bytes = 0;
        while ($entrada && !feof($entrada)) {
            $leido = fread($entrada, 262144);
            if ($leido === false) {
                break;
            }
            $bytes += strlen($leido);
        }
        responder(['bytes' => $bytes]);

    case 'resultado':
        // Guarda el último resultado para mostrarlo en el panel de la PC.
        $mbps = (float) ($_REQUEST['mbps'] ?? 0);
        $ping = (int) ($_REQUEST['ping'] ?? 0);
        if ($mbps <= 0 || $mbps > 10000) {
            responder(['error' => 'Resultado inválido'], 400);
        }
        $datos = [
            'mbps' => round($mbps, 2),
            'ping' => max(0, min($ping, 99999)),
            'dispositivo' => mb_substr(trim((string) ($_REQUEST['dispositivo'] ?? 'Celular')), 0, 40) ?: 'Celular',
            'fecha' => time(),
        ];
        file_put_contents(DIR_DATOS . DIRECTORY_SEPARATOR . 'velocidad.json', json_encode($datos, JSON_UNESCAPED_UNICODE), LOCK_EX);
        responder(['ok' => true]);

    default:
        responder(['error' => 'Acción desconocida'], 400);
}

function leerVelocidad(): ?array
{
    $archivo = DIR_DATOS . DIRECTORY_SEPARATOR . 'velocidad.json';
    if (!is_file($archivo)) {
        return null;
    }
    $d = json_decode((string) file_get_contents($archivo), true);
    return is_array($d) ? $d : null;
}

// ======================================================================

/** Lee sesión, ruta y tamaño de la petición y calcula el destino. */
function datosArchivo(): array
{
    $sesion = limpiarSegmento((string) ($_REQUEST['sesion'] ?? 'Celular'));
    $ruta   = limpiarRuta((string) ($_REQUEST['ruta'] ?? ''));
    $tamano = filter_var($_REQUEST['tamano'] ?? null, FILTER_VALIDATE_INT, ['options' => ['min_range' => 0]]);
    if ($tamano === false) {
        responder(['error' => 'Tamaño inválido'], 400);
    }
    $dirSesion = DIR_RESPALDOS . DIRECTORY_SEPARATOR . $sesion;
    return [resolverDestino($dirSesion, $ruta, $tamano), $tamano];
}

function estadoArchivo(string $destino, int $tamano): array
{
    clearstatcache();
    if (is_file($destino) && filesize($destino) === $tamano) {
        return ['recibido' => $tamano, 'completo' => true];
    }
    $parcial = $destino . '.part';
    $recibido = is_file($parcial) ? (int) filesize($parcial) : 0;
    if ($recibido > $tamano) {          // parcial corrupto: empezar de nuevo
        @unlink($parcial);
        $recibido = 0;
    }
    return ['recibido' => $recibido, 'completo' => false];
}

function recibirTrozo(string $destino, int $tamano): void
{
    $offset = filter_var($_POST['offset'] ?? null, FILTER_VALIDATE_INT, ['options' => ['min_range' => 0]]);
    if ($offset === false) {
        responder(['error' => 'Offset inválido'], 400);
    }

    $estado = estadoArchivo($destino, $tamano);
    if ($estado['completo']) {
        responder($estado);
    }
    if ($offset !== $estado['recibido']) {
        // El celular y la PC no coinciden: decirle desde dónde seguir.
        responder($estado + ['error' => 'desfase'], 409);
    }

    $dir = dirname($destino);
    if (!is_dir($dir) && !mkdir($dir, 0775, true) && !is_dir($dir)) {
        responder(['error' => 'No se pudo crear la carpeta en la PC'], 500);
    }

    $parcial = $destino . '.part';
    $archivoTrozo = $_FILES['trozo'] ?? null;
    $hayDatos = $archivoTrozo && $archivoTrozo['error'] === UPLOAD_ERR_OK && $archivoTrozo['size'] > 0;

    if ($archivoTrozo && !in_array($archivoTrozo['error'], [UPLOAD_ERR_OK, UPLOAD_ERR_NO_FILE], true)) {
        $codigo = $archivoTrozo['error'] === UPLOAD_ERR_INI_SIZE ? 413 : 500;
        responder(['error' => 'Error al recibir el trozo (código ' . $archivoTrozo['error'] . ')'], $codigo);
    }

    if ($hayDatos) {
        if ($offset + (int) $archivoTrozo['size'] > $tamano) {
            responder(['error' => 'El trozo excede el tamaño del archivo'], 400);
        }
        $entrada = fopen($archivoTrozo['tmp_name'], 'rb');
        $salida = fopen($parcial, 'ab');
        if (!$entrada || !$salida || !flock($salida, LOCK_EX)) {
            responder(['error' => 'No se pudo escribir en la PC (¿disco lleno o sin permisos?)'], 500);
        }
        // Verificar dentro del candado que nadie escribió mientras tanto.
        fseek($salida, 0, SEEK_END);
        if (ftell($salida) !== $offset) {
            flock($salida, LOCK_UN);
            fclose($salida);
            responder(estadoArchivo($destino, $tamano) + ['error' => 'desfase'], 409);
        }
        $copiados = stream_copy_to_stream($entrada, $salida);
        fflush($salida);
        flock($salida, LOCK_UN);
        fclose($salida);
        fclose($entrada);
        if ($copiados !== (int) $archivoTrozo['size']) {
            responder(['error' => 'Escritura incompleta (¿disco lleno?)'], 500);
        }
    } elseif ($tamano === 0) {
        touch($parcial);
    }

    clearstatcache();
    $recibido = is_file($parcial) ? (int) filesize($parcial) : 0;
    if ($recibido === $tamano) {
        if (is_file($destino)) {
            @unlink($destino);
        }
        rename($parcial, $destino);
        $mtime = (int) ($_POST['mtime'] ?? 0);
        if ($mtime > 0) {
            @touch($destino, (int) floor($mtime / 1000));   // conservar la fecha original
        }
        responder(['recibido' => $recibido, 'completo' => true]);
    }
    responder(['recibido' => $recibido, 'completo' => false]);
}

function listarRespaldos(): array
{
    if (!is_dir(DIR_RESPALDOS)) {
        return [];
    }
    $lista = [];
    foreach (scandir(DIR_RESPALDOS) ?: [] as $nombre) {
        $ruta = DIR_RESPALDOS . DIRECTORY_SEPARATOR . $nombre;
        if ($nombre[0] === '.' || !is_dir($ruta)) {
            continue;
        }
        $archivos = 0;
        $pendientes = 0;
        $bytes = 0;
        $ultimo = filemtime($ruta) ?: 0;
        $it = new RecursiveIteratorIterator(
            new RecursiveDirectoryIterator($ruta, FilesystemIterator::SKIP_DOTS)
        );
        foreach ($it as $f) {
            /** @var SplFileInfo $f */
            if (!$f->isFile()) {
                continue;
            }
            if (str_ends_with($f->getFilename(), '.part')) {
                $pendientes++;
                $ultimo = max($ultimo, $f->getCTime());
                continue;
            }
            $archivos++;
            $bytes += $f->getSize();
            $ultimo = max($ultimo, $f->getCTime());
        }
        $lista[] = [
            'nombre' => $nombre,
            'archivos' => $archivos,
            'pendientes' => $pendientes,
            'bytes' => $bytes,
            'tamano' => formatoBytes($bytes),
            'actualizado' => $ultimo,
        ];
    }
    usort($lista, static fn($a, $b) => $b['actualizado'] <=> $a['actualizado']);
    return $lista;
}
