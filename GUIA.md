# Guía de Respaldo WiFi para Android

Esta app es la misma página del celular (`movil.php`) metida dentro de una app de Android.
Le agrega un **servicio en primer plano**, para que el respaldo **siga con la pantalla apagada**.
El servidor PHP de tu PC **no cambia**: sigues abriendo `iniciar.bat` como siempre.

No necesitas Android Studio. GitHub compila y firma el APK por ti.

---

## 1. Qué hay en este repositorio

| Carpeta / archivo | Qué es |
|---|---|
| `www/` | La interfaz (HTML, CSS, JS). `estilos.css` es **el mismo** de tu proyecto; `app.js` es `movil.js` adaptado. |
| `android/` | El proyecto Android (Capacitor). El código nativo está en `android/app/src/main/java/mx/respaldowifi/app/`. |
| `android/.../motor/` | El **motor de subida** en Java: el mismo protocolo de trozos que `movil.js` (estado → subir → 409/413/401). |
| `servidor-pc/` | Copia **sin cambios** de tu servidor PHP. Se usa en las pruebas automáticas (y para que todo esté en un solo lugar). |
| `pruebas/` | Pruebas automáticas: el motor contra `api.php`, y la app completa en un emulador con la pantalla apagada. |
| `.github/workflows/compilar.yml` | El "robot" de GitHub que prueba, compila y firma el APK. |

---

## 2. Descargar el APK terminado

Cada vez que subes un cambio a la rama `main`, GitHub compila solo. Para descargar:

1. Entra a tu repositorio en github.com y abre la pestaña **Actions**.
2. Toca la ejecución más reciente de **"Compilar APK"** (la de arriba).
3. Espera a que el trabajo **compilar** tenga la palomita verde ✅. Tarda unos 5 minutos.
   El segundo trabajo, **"Prueba en emulador"**, tarda otros 10 a 15 minutos (ver sección 6).
4. Baja hasta **Artifacts** y descarga **`RespaldoWiFi-1.0.N`**. Llega como `.zip`: descomprímelo y adentro está el `.apk`.

Si quieres compilar sin subir cambios: **Actions → Compilar APK → Run workflow → Run workflow**.

### Instalarlo en un celular
1. Pasa el `.apk` al celular (por WhatsApp a ti mismo, cable, Drive, etc.).
2. Ábrelo. Android dirá que la instalación de "apps desconocidas" está bloqueada.
   Toca **Configuración**, activa **Permitir de esta fuente** y regresa.
3. Toca **Instalar**. Si sale "Play Protect no reconoce esta app", toca **Más detalles → Instalar de todas formas**.
   Es normal en apps que no vienen de la Play Store.

---

## 3. Poner tu llave de firma (una sola vez; muy importante)

Android solo deja **actualizar** una app si la nueva versión está firmada con **la misma llave**.
La llave ya está creada. Te la envié en 3 archivos:

- `respaldo-wifi.jks`: la llave.
- `FIRMA_CLAVE.txt`: su contraseña.
- `FIRMA_BASE64.txt`: la misma llave convertida a texto, para pegarla en GitHub.

**Guarda los 3 archivos en un lugar seguro**, por ejemplo en tu Drive y en una USB.
Si pierdes la llave, nunca podrás actualizar la app en los celulares donde ya esté instalada.
**No los subas al repositorio.**

Para que GitHub firme con tu llave:

1. En tu repositorio ve a **Settings → Secrets and variables → Actions**.
2. Toca **New repository secret**:
   - Name: `FIRMA_BASE64`
   - Secret: abre `FIRMA_BASE64.txt`, copia **todo** el texto (es una sola línea larga) y pégalo.
   - Toca **Add secret**.
3. Otra vez **New repository secret**:
   - Name: `FIRMA_CLAVE`
   - Secret: el contenido de `FIRMA_CLAVE.txt`.
   - Toca **Add secret**.
4. Ve a **Actions → Compilar APK → Run workflow** para compilar ya con tu llave.

Mientras no pongas los Secrets, el APK sale firmado con una **llave de prueba**: funciona, pero no se puede
actualizar encima. GitHub te lo avisa con una advertencia amarilla. Si instalaste una versión con
la llave de prueba, desinstálala antes de instalar la firmada con tu llave.

---

## 4. Cómo se usa

1. En la PC abre `iniciar.bat` (igual que siempre). Aparece el QR.
2. En el celular abre **Respaldo WiFi** y toca **Escanear código QR**.
   También puedes pegar el enlace que da el botón "Copiar enlace" del panel de la PC.
3. Elige **Fotos y videos**, **Archivos** o **Carpeta completa**. Se abre el selector de Android.
4. Toca **Iniciar respaldo**. Aparece la notificación **"Respaldando… X/Y archivos"**, con barra,
   velocidad, tiempo restante y botón **Pausar**.
5. **Ya puedes apagar la pantalla.** Al terminar llega el aviso "¡Respaldo completo!".
   Si se pausa o se pierde la PC, el aviso trae el botón **Continuar**.

La app recuerda la PC y la lista. Si se cierra a la mitad, al abrirla y tocar **Continuar respaldo**
sigue donde se quedó. La PC le dice exactamente cuántos bytes ya tiene de cada archivo.

---

## 5. Permisos que pide la app y por qué

**Android sí le pregunta al usuario por estos dos:**

| Permiso | Cuándo aparece | Para qué | Si lo niega |
|---|---|---|---|
| **Cámara** | La primera vez que toca "Escanear código QR". | Leer el QR de la PC. No se toman ni guardan fotos. | Puede pegar el enlace a mano. |
| **Notificaciones** (Android 13 o más nuevo) | Al tocar "Iniciar respaldo" la primera vez. | Mostrar "Respaldando… X/Y" y el aviso final. Android exige que un servicio en primer plano tenga notificación visible. | El respaldo **sí funciona**, pero no se ve el avance en la barra de notificaciones. |

**Se declaran en la app pero Android no pregunta nada** (aparecen en la ficha de la app como información):

| Permiso | Para qué |
|---|---|
| Internet (`INTERNET`) | Enviar los archivos a la PC por la red de la casa. Los archivos **no salen a internet**: van directo a la IP de la PC. |
| Ver conexiones de red y WiFi (`ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`) | Mandar los datos **por el WiFi** aunque Android esté usando datos móviles como red principal. Pasa cuando el módem "no tiene internet". |
| Mantener despierto (`WAKE_LOCK`) | Que la CPU y el WiFi no se duerman mientras sube con la pantalla apagada. Se suelta al terminar o pausar. |
| Servicio en primer plano de sincronización (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`) | El "motor" que sigue subiendo con la pantalla apagada, siempre con su notificación visible. |
| `mx.respaldowifi.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | Lo agrega automáticamente la librería de Android (AndroidX). Es interno: impide que otras apps le manden mensajes a la tuya. |

**Lo que la app NO pide:** acceso a todos los archivos, almacenamiento, fotos, contactos,
ubicación ni micrófono. Solo lee **lo que el usuario elige** en el selector de Android.
Android le da permiso para esos archivos o esa carpeta en particular, y nada más.
Al terminar cada archivo, la app suelta ese permiso. "Vaciar lista" suelta todos.

---

## 6. Las pruebas automáticas (qué significa cada palomita)

En cada ejecución de Actions corren dos trabajos:

**compilar**
- Arranca tu `api.php` real con `php -S` y prueba el motor de subida:
  - todo llega idéntico (comparado con SHA-256) y con su fecha original;
  - no duplica lo que ya estaba;
  - pausa y reanudación sin reenviar lo ya enviado;
  - PC apagada a la mitad;
  - desfase 409, trozo demasiado grande (413), enlace vencido (401);
  - nombres con ñ y acentos, y "foto (2).jpg".
- Luego compila y firma el APK.

**Prueba en emulador (pantalla apagada)**
1. Arranca un celular Android 14 virtual y tu servidor PHP, e instala la app.
2. Crea unos 130 MB de archivos de prueba en el "celular".
3. Inicia el respaldo, lo **pausa** a la mitad y comprueba que en pausa no se envía nada.
4. Continúa, **apaga la pantalla** y fuerza el **modo de ahorro profundo de Android (Doze)**.
5. **Apaga la PC (el servidor) 10 segundos** a la mitad y la vuelve a encender.
6. Comprueba que terminó **completo**, que la pantalla siguió apagada y que todos los archivos llegaron idénticos.

Los resultados y las capturas de pantalla quedan en **Artifacts → `evidencias-prueba`** y en la rama
`evidencias-emulador` del repositorio.

Si **solo** falla "Prueba en emulador", el APK del trabajo "compilar" igual se generó y se puede descargar.

---

## 7. Límites que conviene saber

- **Android 15 o más nuevo** permite este tipo de servicio unas **6 horas al día**. Si un respaldo es más largo,
  la app guarda su avance y avisa: "Android pausó el respaldo". Al tocar **Continuar** sigue donde se quedó.
- **Xiaomi, Huawei, Oppo, Vivo y algunos Samsung** cierran apps para ahorrar batería aunque tengan notificación.
  Si se detiene con la pantalla apagada: en la app toca **Abrir ajustes de batería**, busca Respaldo WiFi y elige
  **Sin restricciones** o **No optimizar**. En Xiaomi también activa **Inicio automático**.
- **Carpeta completa**: desde Android 11, el sistema no deja elegir la raíz de `Download` ni `Android/data`.
  `DCIM`, `Pictures`, `Movies`, `Documents` y `WhatsApp/Media` sí se pueden elegir.
- Si eliges **más de ~500 archivos sueltos** (no como carpeta) y luego cierras la app desde "Recientes",
  Android puede retirar el permiso de los primeros. Para muchas fotos usa **Carpeta completa** (por ejemplo `DCIM/Camera`).
  La app ya evita cerrarse con el botón Atrás.
- La conexión es `http` dentro de tu red local, igual que la página web. Úsala en redes de confianza.

---

## 8. Hacer cambios

- **Diseño o textos de la pantalla:** edita `www/index.html`, `www/estilos.css`, `www/app.css` o `www/app.js`.
- **Nombre de la app:** `android/app/src/main/res/values/strings.xml`.
- **Ícono:** las imágenes `ic_launcher*.png` en `android/app/src/main/res/mipmap-*`.
- **Versión:** es automática. Cada compilación sube el número (1.0.1, 1.0.2…), así Android acepta la actualización.
- Sube el cambio a `main` y descarga el nuevo APK en Actions.

### Diferencias con la página web (por qué)
- **Elegir archivos** abre el selector nativo de Android y no el `<input type="file">`. El servicio en segundo plano
  necesita poder volver a abrir los archivos cuando la pantalla está apagada, y el navegador no lo permite.
- **La red la maneja Java** y no `fetch`/`XMLHttpRequest`. Desde la app, el navegador interno bloquea las peticiones
  a `http://192.168.x.x` por las reglas de CORS y de "contenido mixto". Así **tu PHP no se toca**.
- El token se manda también en la URL de `subir` (`api.php?accion=subir&t=…`). Así, si un trozo excede
  `post_max_size`, `api.php` responde 413 (y la app reduce el trozo) en lugar de 401. Es compatible con tu
  `api.php` actual porque lee `$_REQUEST`.
- Se quitó el botón de Contactos.

### Si algún día la subes a la Play Store
En Play Console tendrás que declarar el uso del servicio en primer plano de tipo "sincronización de datos"
(motivo: "transferencia de archivos iniciada por el usuario"), con un video corto mostrando la notificación.
También tendrás que generar un `.aab`: se agrega `bundleRelease` en `compilar.yml`.
