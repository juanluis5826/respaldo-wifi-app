@echo off
chcp 65001 >nul
title Respaldo WiFi
cd /d "%~dp0"

set "PHP="
where php >nul 2>nul && set "PHP=php"
if not defined PHP if exist "C:\xampp\php\php.exe" set "PHP=C:\xampp\php\php.exe"
if not defined PHP if exist "C:\laragon\bin\php" for /d %%D in ("C:\laragon\bin\php\php-*") do set "PHP=%%D\php.exe"

if not defined PHP (
  echo.
  echo  No se encontro PHP en esta PC.
  echo  Instala XAMPP desde https://www.apachefriends.org o agrega php.exe al PATH.
  echo.
  pause
  exit /b 1
)

set "PUERTO=8000"
echo.
echo  ===============================================
echo    Respaldo WiFi
echo  ===============================================
echo   Abriendo el panel en el navegador...
echo   Si Windows pregunta por el Firewall, permite el acceso en "Redes privadas".
echo   Deja esta ventana abierta mientras respaldas. Para detener: cierra la ventana.
echo.

start "" "http://localhost:%PUERTO%/"
"%PHP%" -d upload_max_filesize=64M -d post_max_size=70M -d max_execution_time=0 -d memory_limit=256M -S 0.0.0.0:%PUERTO% router.php

echo.
echo  El servidor se detuvo. Si el puerto %PUERTO% esta ocupado, cambia PUERTO en este archivo.
pause
