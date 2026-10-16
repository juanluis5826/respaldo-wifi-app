#!/usr/bin/env bash
# Ejecuta un comando y deja sus últimas líneas como "anotación" visible en la página de la
# ejecución de GitHub Actions (así se ve el resultado de las pruebas sin abrir el registro).
# Uso: bash pruebas/anotar.sh "Título" comando args...
titulo="$1"; shift
dir="${GITHUB_WORKSPACE:-.}/evidencias"
mkdir -p "$dir"
salida="$dir/$(echo "$titulo" | tr ' ' '-').txt"
"$@" 2>&1 | tee "$salida"
codigo=${PIPESTATUS[0]}
texto="$(grep -v 'JAVA_TOOL_OPTIONS' "$salida" | tail -n 60 | sed -e 's/%/%25/g' | sed -e ':a;N;$!ba;s/\n/%0A/g' | sed -e 's/\r//g')"
if [ "$codigo" -eq 0 ]; then
  echo "::notice title=$titulo::$texto"
else
  echo "::error title=$titulo (falló)::$texto"
fi
exit "$codigo"
