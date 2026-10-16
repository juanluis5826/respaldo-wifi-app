<?php
/**
 * Respaldo WiFi - configuración y funciones comunes.
 */
declare(strict_types=1);

const DIR_RESPALDOS = __DIR__ . DIRECTORY_SEPARATOR . 'respaldos';
const DIR_DATOS     = __DIR__ . DIRECTORY_SEPARATOR . 'datos';
const ARCHIVO_TOKEN = DIR_DATOS . DIRECTORY_SEPARATOR . 'token.txt';
const TROZO_MAXIMO  = 8 * 1024 * 1024; // 8 MB por petición como máximo

/** Devuelve el token de acceso (lo crea si no existe o si se pide regenerar). */
function obtenerToken(bool $regenerar = false): string
{
    if (!is_dir(DIR_DATOS)) {
        mkdir(DIR_DATOS, 0775, true);
    }
    if (!$regenerar && is_file(ARCHIVO_TOKEN)) {
        $t = trim((string) file_get_contents(ARCHIVO_TOKEN));
        if (strlen($t) >= 16) {
            return $t;
        }
    }
    $t = bin2hex(random_bytes(12));
    file_put_contents(ARCHIVO_TOKEN, $t, LOCK_EX);
    return $t;
}

/** ¿La petición viene de la misma PC? */
function esLocal(): bool
{
    $ip = $_SERVER['REMOTE_ADDR'] ?? '';
    return in_array($ip, ['127.0.0.1', '::1', '::ffff:127.0.0.1'], true);
}

/** Convierte valores de php.ini como "2M" a bytes. */
function iniABytes(string $v): int
{
    $v = trim($v);
    if ($v === '' || $v === '-1' || $v === '0') {
        return PHP_INT_MAX;
    }
    $n = (int) $v;
    switch (strtolower(substr($v, -1))) {
        case 'g': $n *= 1024;
        // no break
        case 'm': $n *= 1024;
        // no break
        case 'k': $n *= 1024;
    }
    return $n;
}

/** Tamaño de trozo seguro según los límites de php.ini. */
function tamanoTrozo(): int
{
    $limite = min(
        iniABytes((string) ini_get('upload_max_filesize')),
        iniABytes((string) ini_get('post_max_size'))
    );
    $trozo = min(TROZO_MAXIMO, $limite - 256 * 1024);
    return max(256 * 1024, $trozo);
}

/** Limpia un nombre de carpeta/archivo para que sea válido en Windows y Linux. */
function limpiarSegmento(string $s): string
{
    $s = preg_replace('/[\x00-\x1F\x7F<>:"\/\\\\|?*]/u', '_', $s) ?? '';
    $s = trim($s, " .\t");
    if ($s === '' || $s === '..') {
        $s = '_';
    }
    if (preg_match('/^(con|prn|aux|nul|com\d|lpt\d)(\..*)?$/i', $s)) {
        $s = '_' . $s;
    }
    return mb_substr($s, 0, 120);
}

/** Limpia una ruta relativa ("DCIM/Camera/foto.jpg") evitando salir de la carpeta. */
function limpiarRuta(string $ruta): string
{
    $partes = preg_split('#[/\\\\]+#', $ruta) ?: [];
    $limpias = [];
    foreach ($partes as $p) {
        if ($p === '' || $p === '.' || $p === '..') {
            continue;
        }
        $limpias[] = limpiarSegmento($p);
    }
    if (!$limpias) {
        $limpias[] = 'archivo';
    }
    return implode(DIRECTORY_SEPARATOR, array_slice($limpias, -8));
}

/**
 * Decide la ruta final de un archivo. Si ya existe un archivo con el mismo
 * nombre pero distinto tamaño, usa "nombre (2).ext", "nombre (3).ext", etc.
 */
function resolverDestino(string $dirSesion, string $ruta, int $tamano): string
{
    $base = $dirSesion . DIRECTORY_SEPARATOR . $ruta;
    $info = pathinfo($base);
    $ext = isset($info['extension']) ? '.' . $info['extension'] : '';
    $sinExt = $info['dirname'] . DIRECTORY_SEPARATOR . $info['filename'];

    for ($i = 1; $i < 1000; $i++) {
        $candidato = $i === 1 ? $base : "{$sinExt} ({$i}){$ext}";
        if (is_file($candidato . '.part')) {
            return $candidato;           // subida pendiente: continuar ahí
        }
        if (!is_file($candidato) || filesize($candidato) === $tamano) {
            return $candidato;           // libre, o es el mismo archivo ya subido
        }
    }
    return $base . '.' . bin2hex(random_bytes(3));
}

/** IPs de la red local (WiFi/Ethernet) de esta PC. */
function ipsLocales(): array
{
    $ips = [];
    $host = gethostname();
    if ($host) {
        foreach (gethostbynamel($host) ?: [] as $ip) {
            $ips[] = $ip;
        }
    }
    if (function_exists('shell_exec')) {
        $salida = (string) @shell_exec(PHP_OS_FAMILY === 'Windows' ? 'ipconfig' : 'ip -4 -o addr 2>/dev/null || ifconfig 2>/dev/null');
        if (preg_match_all('/(?<![\d.])(\d{1,3}(?:\.\d{1,3}){3})(?![\d.])/', $salida, $m)) {
            foreach ($m[1] as $ip) {
                $ips[] = $ip;
            }
        }
    }
    $privadas = array_filter(array_unique($ips), static function (string $ip): bool {
        if (!filter_var($ip, FILTER_VALIDATE_IP, FILTER_FLAG_IPV4)) {
            return false;
        }
        if (str_ends_with($ip, '.255') || str_ends_with($ip, '.0')) {
            return false;
        }
        return (bool) preg_match('/^(192\.168\.|10\.|172\.(1[6-9]|2\d|3[01])\.)/', $ip);
    });
    // Primero las 192.168.x.x (lo típico en casa), al final las de máquinas virtuales.
    usort($privadas, static function (string $a, string $b): int {
        $p = static fn(string $ip): int => str_starts_with($ip, '192.168.56.') ? 3
            : (str_starts_with($ip, '192.168.') ? 0 : (str_starts_with($ip, '10.') ? 1 : 2));
        return $p($a) <=> $p($b);
    });
    return array_values($privadas);
}

/** Envía JSON y termina. */
function responder(array $datos, int $codigo = 200): void
{
    http_response_code($codigo);
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store');
    echo json_encode($datos, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

function formatoBytes(int $b): string
{
    $u = ['B', 'KB', 'MB', 'GB', 'TB'];
    $i = 0;
    $n = (float) $b;
    while ($n >= 1024 && $i < count($u) - 1) {
        $n /= 1024;
        $i++;
    }
    return ($i === 0 ? (string) $b : number_format($n, 1)) . ' ' . $u[$i];
}
