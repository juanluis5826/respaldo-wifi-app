#!/usr/bin/env sh
# Respaldo WiFi - iniciar en Linux o macOS
cd "$(dirname "$0")" || exit 1
PUERTO="${PUERTO:-8000}"

if ! command -v php >/dev/null 2>&1; then
  echo "No se encontró PHP. Instálalo (ej. sudo apt install php-cli) y vuelve a intentar."
  exit 1
fi

echo "Respaldo WiFi: abre http://localhost:$PUERTO en el navegador de esta PC."
echo "Deja esta terminal abierta mientras respaldas. Ctrl+C para detener."
( sleep 1; (xdg-open "http://localhost:$PUERTO/" || open "http://localhost:$PUERTO/") >/dev/null 2>&1 ) &

exec php -d upload_max_filesize=64M -d post_max_size=70M -d max_execution_time=0 -d memory_limit=256M \
  -S "0.0.0.0:$PUERTO" router.php
