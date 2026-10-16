#!/usr/bin/env bash
# Prueba el motor de subida (Java) contra el servidor PHP real de servidor-pc/.
# Uso: bash pruebas/motor/correr.sh      (requiere Java 17+ y PHP 8)
set -euo pipefail
cd "$(dirname "$0")"
rm -rf run out
mkdir -p run
cp -r ../../servidor-pc run/srv
cp router_413.php run/
javac -encoding UTF-8 -d out ../../android/app/src/main/java/mx/respaldowifi/app/motor/*.java PruebaMotor.java
LANG=C.UTF-8 LC_ALL=C.UTF-8 java -Dsun.jnu.encoding=UTF-8 -Dfile.encoding=UTF-8 -Draiz="$PWD/run" -cp out PruebaMotor
