# Respaldo WiFi

Respalda los archivos de tu celular en tu PC usando la red WiFi de tu casa.
La PC muestra un código QR; lo escaneas con el celular, eliges qué respaldar y los archivos
viajan directo a la PC (no pasan por internet).

## Requisitos

- Una PC con PHP 8 (XAMPP sirve: https://www.apachefriends.org).
- PC y celular conectados a la **misma red WiFi**.
- En el celular: Chrome (Android) o Safari (iPhone).

## Uso rápido (Windows)

1. Descomprime la carpeta `respaldo-wifi` donde quieras (ej. `Documentos`).
2. Haz doble clic en **`iniciar.bat`**.
   - Si Windows pregunta por el Firewall, marca **Redes privadas** y acepta.
3. Se abre el panel en el navegador de la PC con un código QR.
4. Escanea el QR con la cámara del celular y abre el enlace.
5. Escribe un nombre para el celular, elige **Fotos y videos**, **Archivos** o **Carpeta completa**
   y toca **Iniciar respaldo**.
6. Los archivos quedan en `respaldo-wifi\respaldos\<nombre del celular> <fecha>\`.

En Linux/macOS: `sh iniciar.sh`.

### Con XAMPP (Apache) en lugar de `iniciar.bat`

Copia la carpeta a `C:\xampp\htdocs\respaldo-wifi`, inicia Apache y abre
`http://localhost/respaldo-wifi/` **en la PC**. El QR ya incluye la ruta correcta.

## Qué hace

- **Subida por trozos y reanudable**: si se corta el WiFi o se bloquea el celular,
  vuelve a abrir el enlace, elige los mismos archivos y continúa donde se quedó.
- **No duplica**: los archivos que ya llegaron completos se marcan como “Ya estaba”.
- Si llega un archivo con el mismo nombre pero distinto contenido, se guarda como `foto (2).jpg`.
- Conserva la **fecha original** de cada archivo.
- Ordena lo suelto en `Fotos`, `Videos`, `Audio`, `Documentos` y `Otros`;
  las carpetas completas conservan su estructura (ej. `DCIM/Camera/...`).
- Muestra avance, velocidad y tiempo restante; permite pausar y reintentar fallidos.
- Funciona sin internet (el generador de QR viene incluido).
- **Medidor de velocidad**: en el celular toca “Medir velocidad” (8 segundos). Envía datos de
  prueba que no se guardan y muestra MB/s, tiempo de respuesta y cuánto tardaría tu lista
  (o 10 GB). El último resultado aparece también en el panel de la PC.
  Referencia: menos de 3 MB/s lenta · 3–10 normal · 10–25 buena (como cable USB 2) · más de 25 excelente.

## Seguridad

- El enlace lleva un **token secreto**; sin él nadie en tu red puede enviar archivos.
- El panel con el QR **solo se abre desde la misma PC** (localhost).
- Los respaldos y el token no se pueden descargar desde la red.
- Botón **Nuevo enlace**: invalida el QR anterior (úsalo si compartiste el enlace por error).
- Usa esto solo en redes de confianza (tu casa), no en WiFi públicas: la conexión es `http`.

## Limitaciones (del navegador, no del programa)

- El navegador **no permite leer todo el celular automáticamente**: tú eliges qué enviar.
- No se pueden respaldar datos internos de apps (chats de WhatsApp, juegos), SMS ni llamadas.
  Para WhatsApp usa su propia copia de seguridad; sus fotos y videos sí puedes enviarlos
  eligiendo la carpeta `Android/media/com.whatsapp/WhatsApp/Media` (si tu Android la muestra).
- **Carpeta completa** funciona en Chrome para Android; en iPhone se oculta (Safari no lo permite).
  Algunas versiones de Android no dejan elegir `DCIM` o `Download` completas: en ese caso usa
  “Fotos y videos” o “Archivos” y selecciona varios.
- **Contactos** solo aparece si el navegador lo permite; Chrome lo exige en conexiones `https`,
  así que por `http` normalmente no se mostrará. Alternativa: en la app Contactos del celular usa
  “Exportar” a un archivo `.vcf` y envíalo con “Archivos”.
- Mantén la pantalla encendida durante el respaldo: con la pantalla apagada el navegador
  pausa la subida.

## Solución de problemas

| Problema | Solución |
|---|---|
| El celular no abre la página | Revisa que estén en la misma red. En el panel cambia la IP en “Red de esta PC”. Permite PHP en el Firewall (Panel de control → Firewall → Permitir una aplicación → `php.exe`, redes privadas). |
| “Enlace no válido” | Escanea de nuevo el QR (quizá se presionó “Nuevo enlace”). |
| “No se encontró PHP” | Instala XAMPP o edita `iniciar.bat` con la ruta de tu `php.exe`. |
| Puerto ocupado | Cambia `PUERTO=8000` en `iniciar.bat`. |
| Va lento | Acerca el celular al módem; las redes de 5 GHz son más rápidas. |

## Archivos del proyecto

```
iniciar.bat / iniciar.sh   Arranca el servidor y abre el panel
router.php                 Bloquea el acceso a datos internos (servidor de PHP)
.htaccess                  Lo mismo para Apache/XAMPP
index.php                  Panel de la PC (QR + respaldos recibidos)
movil.php                  Página del celular
api.php                    Recibe los trozos, reanuda y lista respaldos
config.php                 Funciones comunes (token, rutas seguras, IPs)
assets/                    Estilos, scripts y generador de QR (MIT, Kazuhiko Arase)
respaldos/                 Aquí se guardan los archivos (se crea solo)
datos/                     Token del enlace (se crea solo)
```
