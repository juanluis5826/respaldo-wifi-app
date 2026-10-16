# Respaldo WiFi — app para Android

Respalda los archivos del celular en tu PC por la red WiFi de la casa. Con esta app,
**el respaldo sigue aunque la pantalla esté apagada** (servicio en primer plano con notificación).

- La PC sigue usando el mismo servidor PHP (`servidor-pc/`, se abre con `iniciar.bat`).
- La app reutiliza la interfaz web original (`www/`) dentro de Capacitor.
- El APK se compila y firma solo en **GitHub Actions** → pestaña **Actions** → **Artifacts**.

👉 **Lee [GUIA.md](GUIA.md)**: cómo descargar el APK, poner tu llave de firma, los permisos y los límites.
