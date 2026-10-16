#!/usr/bin/env bash
# Prueba completa en un emulador Android:
#   1. Arranca el servidor PHP de servidor-pc/ (igual que iniciar.bat).
#   2. Instala la app, crea archivos de prueba en el "celular".
#   3. Fase A (pantalla encendida): inicia, PAUSA a la mitad y comprueba que se pausó.
#   4. Fase B: continúa, APAGA LA PANTALLA, fuerza el modo de ahorro profundo (Doze)
#      y apaga la PC (servidor) 10 segundos a la mitad.
#   5. Comprueba que todo llegó a la PC idéntico (SHA-256) y que el avance ocurrió
#      con la pantalla apagada.
set -uo pipefail

APK="$1"
PKG=mx.respaldowifi.app
RAIZ="$(cd "$(dirname "$0")/../.." && pwd)"
EVID="$RAIZ/evidencias"
SRV="$(mktemp -d)/srv"
mkdir -p "$EVID"
cp -r "$RAIZ/servidor-pc" "$SRV"
export SRV_DIR="$SRV"
FALLO=0

log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$EVID/resumen.txt"; }
fallar() { log "FALLA: $*"; FALLO=1; }

arrancar_php() {
  php -d upload_max_filesize=2M -d post_max_size=2M -d max_execution_time=0 \
      -S 0.0.0.0:8000 -t "$SRV" "$RAIZ/pruebas/emulador/router_lento.php" >> "$EVID/php.log" 2>&1 &
  PHP_PID=$!
  for _ in $(seq 1 30); do curl -s -o /dev/null "http://127.0.0.1:8000/" && return 0; sleep 0.3; done
}
detener_php() { kill "$PHP_PID" 2>/dev/null; wait "$PHP_PID" 2>/dev/null; }
bytes_pc() { du -sb "$SRV/respaldos" 2>/dev/null | cut -f1 || echo 0; }
pantalla() { adb shell dumpsys power | grep -o 'mWakefulness=[A-Za-z]*' | head -1 | cut -d= -f2; }
esperar_log() {   # esperar_log "texto" segundos
  local fin=$((SECONDS + $2))
  while [ $SECONDS -lt $fin ]; do
    adb logcat -d -s RespaldoWiFi:I | grep -q "$1" && return 0
    sleep 2
  done
  return 1
}

arrancar_php
curl -s "http://127.0.0.1:8000/api.php?accion=ping&t=x" > /dev/null
TOKEN=$(cat "$SRV/datos/token.txt")
ENLACE="http://10.0.2.2:8000/movil.php?t=$TOKEN"
log "Servidor PHP listo. Enlace: http://10.0.2.2:8000/movil.php?t=***"

adb install -r -g "$APK" > /dev/null || { fallar "no se pudo instalar"; exit 1; }
adb shell dumpsys battery unplug
log "App instalada."

# ---- archivos de prueba dentro de la app (como si el usuario los hubiera elegido)
adb shell "run-as $PKG sh -c 'rm -rf files/prueba && mkdir -p files/prueba/DCIM/Camera files/prueba/Documentos'"
for i in 1 2 3 4 5; do
  adb shell "run-as $PKG sh -c 'head -c 25000000 /dev/urandom > files/prueba/DCIM/Camera/VID_000$i.mp4'"
done
for i in 1 2 3 4 5 6 7 8; do
  adb shell "run-as $PKG sh -c 'head -c $((i * 123457)) /dev/urandom > files/prueba/DCIM/Camera/IMG_000$i.jpg'"
done
adb shell "run-as $PKG sh -c 'echo hola > \"files/prueba/Documentos/nota con espacios.txt\" && : > files/prueba/Documentos/vacio.txt'"
adb shell "run-as $PKG sh -c 'cd files/prueba && find . -type f -exec sha256sum {} \;'" | tr -d '\r' | sort -k2 > "$EVID/sha-celular.txt"
TOTAL=$(adb shell "run-as $PKG sh -c 'du -sb files/prueba'" | tr -d '\r' | cut -f1)
log "Archivos de prueba: $(wc -l < "$EVID/sha-celular.txt") archivos, $TOTAL bytes."

# ---- abrir la app (captura de la pantalla principal)
adb logcat -c
adb shell am start -n $PKG/.MainActivity > /dev/null
sleep 6
adb exec-out screencap -p > "$EVID/1-app-abierta.png"

# ===================== FASE A: pausar y continuar =====================
adb shell am start -n $PKG/.PruebaActivity --es enlace "$ENLACE" --es carpeta prueba > /dev/null
esperar_log "PRUEBA: " 30 || fallar "la app no recibió la lista de prueba"
adb logcat -d -s RespaldoWiFi:I | grep "PRUEBA" | tee -a "$EVID/resumen.txt"
for _ in $(seq 1 60); do [ "$(bytes_pc)" -gt 20000000 ] && break; sleep 1; done
sleep 1
adb shell cmd statusbar expand-notifications; sleep 2
adb exec-out screencap -p > "$EVID/2-notificacion.png"
adb shell cmd statusbar collapse
adb shell am start -n $PKG/.PruebaActivity --es accion pausar > /dev/null
if esperar_log "FIN PAUSADO" 30; then log "OK: se pausó con $(bytes_pc) bytes en la PC."; else fallar "no se pausó"; fi
ANTES=$(bytes_pc); sleep 5
[ "$(bytes_pc)" -eq "$ANTES" ] && log "OK: en pausa no se envía nada." || fallar "siguió enviando en pausa"
ls -R "$SRV/respaldos" | grep -c '\.part$' | xargs -I{} log "Archivos a medias (.part) en la PC: {}"

# ===================== FASE B: pantalla apagada + Doze + corte =====================
adb logcat -c
adb shell am start -n $PKG/.PruebaActivity --es accion continuar > /dev/null
sleep 2
adb shell input keyevent KEYCODE_SLEEP
sleep 2
adb shell dumpsys deviceidle force-idle > /dev/null
log "Pantalla: $(pantalla). Doze: $(adb shell dumpsys deviceidle get deep | tr -d '\r')."

echo "segundo,bytes_en_pc,pantalla" > "$EVID/avance-pantalla-apagada.csv"
APAGADA_INI=$(bytes_pc); CORTADO=0; t0=$SECONDS
while [ $((SECONDS - t0)) -lt 600 ]; do
  b=$(bytes_pc); p=$(pantalla)
  echo "$((SECONDS - t0)),$b,$p" >> "$EVID/avance-pantalla-apagada.csv"
  if [ $CORTADO -eq 0 ] && [ "$b" -gt $((TOTAL / 2)) ]; then
    log "Apagando la PC (servidor) 10 s a la mitad del respaldo…"
    detener_php; sleep 10; arrancar_php; CORTADO=1
    log "PC encendida de nuevo."
  fi
  adb logcat -d -s RespaldoWiFi:I | grep -q "FIN " && break
  sleep 3
done
P_FIN=$(pantalla)
APAGADA_FIN=$(bytes_pc)
adb logcat -d -s RespaldoWiFi:I | grep "FIN \|PROGRESO" | tail -5 | tee -a "$EVID/resumen.txt"
log "Con la pantalla apagada llegaron $((APAGADA_FIN - APAGADA_INI)) bytes. Pantalla al final: $P_FIN."
adb logcat -d -s RespaldoWiFi:I | grep -q "FIN COMPLETO" && log "OK: el respaldo terminó COMPLETO." || fallar "no terminó completo"
[ "$P_FIN" = "Asleep" ] || [ "$P_FIN" = "Dozing" ] && log "OK: la pantalla siguió apagada todo el tiempo." || fallar "la pantalla se encendió ($P_FIN)"
[ $((APAGADA_FIN - APAGADA_INI)) -gt $((TOTAL / 3)) ] || fallar "casi no avanzó con la pantalla apagada"
[ $CORTADO -eq 1 ] || fallar "no se llegó a probar el corte de la PC"

# ---- comparar lo que llegó a la PC
SES=$(ls "$SRV/respaldos" | head -1)
log "Carpeta creada en la PC: respaldos/$SES"
(cd "$SRV/respaldos/$SES/Prueba" && find . -type f -exec sha256sum {} \;) | sort -k2 > "$EVID/sha-pc.txt"
if diff "$EVID/sha-celular.txt" "$EVID/sha-pc.txt" > "$EVID/diferencias.txt"; then
  log "OK: los $(wc -l < "$EVID/sha-pc.txt") archivos llegaron idénticos (SHA-256)."
else
  fallar "hay archivos distintos o faltantes (ver diferencias.txt)"
fi
[ -z "$(find "$SRV/respaldos" -name '*.part')" ] && log "OK: no quedó ningún archivo a medias." || fallar "quedaron archivos .part"

# ---- capturas finales
adb shell input keyevent KEYCODE_WAKEUP; sleep 1
adb shell wm dismiss-keyguard; sleep 1
adb shell cmd statusbar expand-notifications; sleep 2
adb exec-out screencap -p > "$EVID/3-aviso-final.png"
adb shell cmd statusbar collapse
adb shell am start -n $PKG/.MainActivity > /dev/null; sleep 3
adb exec-out screencap -p > "$EVID/4-app-al-terminar.png"
adb logcat -d > "$EVID/logcat-completo.txt"
detener_php

if [ $FALLO -eq 0 ]; then log "=== PRUEBA EN EMULADOR: TODO BIEN ==="; else log "=== PRUEBA EN EMULADOR: HUBO FALLAS ==="; fi
exit $FALLO
