(function () {
  'use strict';

  var TOKEN = window.RESPALDO.token;
  var $ = function (id) { return document.getElementById(id); };
  var trozo = 1536 * 1024;        // se ajusta con lo que diga la PC
  var MAX_INTENTOS = 6;
  var MAX_VISIBLES = 150;

  var cola = [];                  // { id, file, ruta, tam, enviados, estado, error }
  var claves = Object.create(null);
  var siguienteId = 1;
  var corriendo = false;
  var pausado = false;
  var xhrActual = null;
  var bloqueoPantalla = null;
  var muestras = [];              // para calcular velocidad
  var pendienteRender = false;

  // ---------------- utilidades ----------------
  function guardar(k, v) { try { localStorage.setItem(k, v); } catch (e) {} }
  function leer(k) { try { return localStorage.getItem(k); } catch (e) { return null; } }

  function formatoBytes(b) {
    var u = ['B', 'KB', 'MB', 'GB', 'TB'], i = 0;
    while (b >= 1024 && i < u.length - 1) { b /= 1024; i++; }
    return (i === 0 ? b : b.toFixed(b < 10 ? 1 : 0)) + ' ' + u[i];
  }
  function formatoTiempo(s) {
    if (!isFinite(s) || s < 0) return '–';
    s = Math.round(s);
    if (s < 60) return s + ' s';
    if (s < 3600) return Math.floor(s / 60) + ' min ' + (s % 60) + ' s';
    return Math.floor(s / 3600) + ' h ' + Math.floor((s % 3600) / 60) + ' min';
  }
  function escapar(s) {
    return String(s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function esperar(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }
  function hoy() {
    var d = new Date();
    return d.getFullYear() + '-' + ('0' + (d.getMonth() + 1)).slice(-2) + '-' + ('0' + d.getDate()).slice(-2);
  }
  function nombreSesion() {
    var n = ($('dispositivo').value || '').trim() || 'Celular';
    return n + ' ' + hoy();
  }
  function mostrarError(msg) {
    var c = $('cajaError');
    if (!msg) { c.classList.add('oculto'); c.textContent = ''; return; }
    c.textContent = msg;
    c.classList.remove('oculto');
  }

  function ErrorHttp(codigo, datos) { this.codigo = codigo; this.datos = datos || {}; }

  // ---------------- comunicación con la PC ----------------
  function apiGet(accion, params) {
    var q = 'api.php?accion=' + accion + '&t=' + encodeURIComponent(TOKEN);
    Object.keys(params || {}).forEach(function (k) { q += '&' + k + '=' + encodeURIComponent(params[k]); });
    return fetch(q, { cache: 'no-store' }).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (d) {
        if (!r.ok) throw new ErrorHttp(r.status, d);
        return d;
      });
    });
  }

  function enviarTrozo(item, offset, blob, alProgreso) {
    return new Promise(function (resolver, rechazar) {
      var fd = new FormData();
      fd.append('t', TOKEN);
      fd.append('sesion', item.sesion);
      fd.append('ruta', item.ruta);
      fd.append('tamano', String(item.tam));
      fd.append('offset', String(offset));
      fd.append('mtime', String(item.file.lastModified || 0));
      fd.append('trozo', blob, 'trozo');

      var xhr = new XMLHttpRequest();
      xhrActual = xhr;
      xhr.open('POST', 'api.php?accion=subir');
      xhr.timeout = 120000;
      xhr.upload.onprogress = function (e) { if (e.lengthComputable) alProgreso(e.loaded); };
      xhr.onload = function () {
        xhrActual = null;
        var d = {};
        try { d = JSON.parse(xhr.responseText); } catch (e) {}
        if (xhr.status >= 200 && xhr.status < 300) resolver(d);
        else rechazar(new ErrorHttp(xhr.status, d));
      };
      xhr.onerror = function () { xhrActual = null; rechazar(new ErrorHttp(0)); };
      xhr.ontimeout = function () { xhrActual = null; rechazar(new ErrorHttp(0)); };
      xhr.onabort = function () { xhrActual = null; rechazar(new ErrorHttp(-1)); };
      xhr.send(fd);
    });
  }

  // ---------------- cola ----------------
  function carpetaPorTipo(f) {
    var t = f.type || '';
    if (t.indexOf('image/') === 0) return 'Fotos';
    if (t.indexOf('video/') === 0) return 'Videos';
    if (t.indexOf('audio/') === 0) return 'Audio';
    if (/\.(pdf|docx?|xlsx?|pptx?|txt|odt|ods|csv|rtf)$/i.test(f.name)) return 'Documentos';
    return 'Otros';
  }

  function agregar(lista, prefijo) {
    var nuevos = 0, repetidos = 0;
    Array.prototype.forEach.call(lista, function (f) {
      var ruta = f.webkitRelativePath ? f.webkitRelativePath : (prefijo || carpetaPorTipo(f)) + '/' + f.name;
      var clave = ruta + '|' + f.size;
      if (claves[clave]) { repetidos++; return; }
      claves[clave] = true;
      cola.push({ id: siguienteId++, file: f, ruta: ruta, tam: f.size, enviados: 0, estado: 'pendiente', error: '' });
      nuevos++;
    });
    if (repetidos && !nuevos) mostrarError('Esos archivos ya estaban en la lista.');
    else mostrarError('');
    render();
  }

  function totales() {
    var t = { total: 0, enviados: 0, n: cola.length, listos: 0, errores: 0, pendientes: 0 };
    cola.forEach(function (it) {
      t.total += it.tam;
      t.enviados += Math.min(it.enviados, it.tam);
      if (it.estado === 'listo' || it.estado === 'omitido') t.listos++;
      else if (it.estado === 'error') t.errores++;
      else t.pendientes++;
    });
    return t;
  }

  function registrarMuestra(bytesTotales) {
    var ahora = Date.now();
    muestras.push([ahora, bytesTotales]);
    while (muestras.length > 2 && ahora - muestras[0][0] > 6000) muestras.shift();
  }
  function velocidad() {
    if (muestras.length < 2) return 0;
    var a = muestras[0], b = muestras[muestras.length - 1];
    var dt = (b[0] - a[0]) / 1000;
    return dt > 0.5 ? (b[1] - a[1]) / dt : 0;
  }

  // ---------------- interfaz ----------------
  var ETIQUETAS = { pendiente: 'En espera', subiendo: 'Enviando', listo: 'Listo', omitido: 'Ya estaba', error: 'Error' };

  function render() {
    if (pendienteRender) return;
    pendienteRender = true;
    requestAnimationFrame(function () {
      pendienteRender = false;
      dibujar();
    });
  }

  function dibujar() {
    var t = totales();
    var pct = t.total ? Math.floor(t.enviados / t.total * 100) : (t.n && t.pendientes === 0 ? 100 : 0);

    if (!t.n) {
      $('resumenCola').textContent = 'Sin archivos seleccionados';
    } else if (t.pendientes === 0) {
      $('resumenCola').textContent = t.errores
        ? 'Terminado con ' + t.errores + ' error' + (t.errores > 1 ? 'es' : '') + ' (' + t.listos + ' de ' + t.n + ' listos)'
        : '¡Respaldo completo! ' + t.n + ' archivo' + (t.n > 1 ? 's' : '') + ' · ' + formatoBytes(t.total);
    } else {
      $('resumenCola').textContent = t.listos + ' de ' + t.n + ' archivos · ' + formatoBytes(t.enviados) + ' de ' + formatoBytes(t.total);
    }

    $('barraTotal').style.width = pct + '%';
    $('cPorcentaje').textContent = pct + '%';
    var v = corriendo ? velocidad() : 0;
    $('cVelocidad').textContent = v > 0 ? formatoBytes(v) + '/s' : '–';
    $('cRestante').textContent = v > 0 ? formatoTiempo((t.total - t.enviados) / v) : '–';

    $('btnIniciar').disabled = corriendo || midiendo || t.pendientes === 0;
    $('btnMedir').disabled = corriendo || midiendo;
    if (!corriendo) mostrarEstimado();
    $('btnIniciar').textContent = corriendo ? 'Respaldando…' : (cola.some(function (i) { return i.enviados > 0 && i.estado === 'pendiente'; }) ? 'Continuar respaldo' : 'Iniciar respaldo');
    $('btnPausar').classList.toggle('oculto', !corriendo);
    $('btnReintentar').classList.toggle('oculto', corriendo || t.errores === 0);
    $('btnLimpiar').classList.toggle('oculto', corriendo || t.n === 0);
    $('avisoPantalla').classList.toggle('oculto', !corriendo);

    // Lista: primero lo que se está enviando, luego errores, pendientes y lo último terminado.
    var orden = { subiendo: 0, error: 1, pendiente: 2, listo: 3, omitido: 3 };
    var visibles = cola.slice().sort(function (a, b) {
      return (orden[a.estado] - orden[b.estado]) || (a.estado === 'listo' || a.estado === 'omitido' ? b.id - a.id : a.id - b.id);
    }).slice(0, MAX_VISIBLES);

    $('lista').innerHTML = visibles.map(function (it) {
      var p = it.tam ? Math.floor(Math.min(it.enviados, it.tam) / it.tam * 100) : (it.estado === 'listo' ? 100 : 0);
      var detalle = formatoBytes(it.tam) + (it.error ? ' · ' + escapar(it.error) : '');
      return '<li><span class="nombre">' + escapar(it.ruta) + '</span>' +
        '<span class="estado est-' + (it.estado === 'omitido' ? 'listo' : it.estado) + '">' +
        ETIQUETAS[it.estado] + (it.estado === 'subiendo' ? ' ' + p + '%' : '') + '</span>' +
        '<span class="detalle">' + detalle + '</span><span></span>' +
        (it.estado === 'subiendo' ? '<div class="mini-barra"><div style="width:' + p + '%"></div></div>' : '') +
        '</li>';
    }).join('');

    var resto = cola.length - visibles.length;
    $('masItems').classList.toggle('oculto', resto <= 0);
    $('masItems').textContent = resto > 0 ? '… y ' + resto + ' archivos más' : '';
  }

  // ---------------- subida ----------------
  async function subirArchivo(it) {
    it.estado = 'subiendo';
    it.error = '';
    it.sesion = it.sesion || nombreSesion();
    render();

    var intentos = 0;
    var offset = null;

    while (true) {
      if (pausado) throw new ErrorHttp(-1);
      try {
        if (offset === null) {
          var st = await apiGet('estado', { sesion: it.sesion, ruta: it.ruta, tamano: it.tam });
          if (st.completo) {
            it.enviados = it.tam;
            it.estado = it.enviados > 0 && it.yaIniciado ? 'listo' : 'omitido';
            return;
          }
          offset = st.recibido || 0;
          it.enviados = offset;
        }

        var fin = Math.min(offset + trozo, it.tam);
        var blob = it.file.slice(offset, fin);
        var base = offset;
        it.yaIniciado = true;
        var r = await enviarTrozo(it, offset, blob, function (cargado) {
          it.enviados = base + Math.min(cargado, fin - base);
          registrarMuestra(totales().enviados);
          render();
        });
        intentos = 0;
        offset = r.recibido;
        it.enviados = offset;
        registrarMuestra(totales().enviados);
        render();
        if (r.completo) { it.estado = 'listo'; return; }
      } catch (e) {
        if (!(e instanceof ErrorHttp)) throw e;
        if (e.codigo === -1) throw e;                       // pausado
        if (e.codigo === 401) throw e;                      // enlace vencido
        if (e.codigo === 409) { offset = e.datos.recibido || 0; continue; }
        if (e.codigo === 413 && trozo > 256 * 1024) { trozo = Math.floor(trozo / 2); continue; }
        if (e.codigo === 400 || e.codigo === 413) {
          it.estado = 'error'; it.error = e.datos.error || 'Rechazado por la PC'; return;
        }
        intentos++;
        if (intentos >= MAX_INTENTOS) {
          it.estado = 'error';
          it.error = e.codigo === 0 ? 'Sin conexión con la PC' : (e.datos.error || 'Error ' + e.codigo);
          return;
        }
        it.error = 'Reintentando (' + intentos + ')…';
        render();
        offset = null;                                      // volver a preguntar desde dónde seguir
        await esperar(1500 * intentos);
      }
    }
  }

  async function iniciar() {
    if (corriendo || midiendo) return;
    var nombre = ($('dispositivo').value || '').trim();
    if (nombre) guardar('respaldo_dispositivo', nombre);
    corriendo = true;
    pausado = false;
    muestras = [];
    mostrarError('');
    pedirPantallaEncendida();
    render();

    try {
      for (var i = 0; i < cola.length; i++) {
        var it = cola[i];
        if (it.estado !== 'pendiente') continue;
        await subirArchivo(it);
        render();
      }
      var t = totales();
      if (t.errores === 0 && t.n) avisar('Respaldo completo');
    } catch (e) {
      cola.forEach(function (it) { if (it.estado === 'subiendo') it.estado = 'pendiente'; });
      if (e instanceof ErrorHttp && e.codigo === 401) {
        mostrarError('El enlace venció. Vuelve a escanear el QR de la PC (tu lista se conserva si no cierras la página).');
      } else if (!(e instanceof ErrorHttp && e.codigo === -1)) {
        mostrarError('Ocurrió un error inesperado: ' + (e && e.message ? e.message : e));
      }
    } finally {
      corriendo = false;
      soltarPantalla();
      render();
    }
  }

  function avisar(texto) {
    if (navigator.vibrate) { try { navigator.vibrate([120, 80, 120]); } catch (e) {} }
    document.title = '✓ ' + texto;
  }

  // ---------------- pantalla encendida ----------------
  function pedirPantallaEncendida() {
    if (!('wakeLock' in navigator)) return;
    navigator.wakeLock.request('screen').then(function (b) { bloqueoPantalla = b; }).catch(function () {});
  }
  function soltarPantalla() {
    if (bloqueoPantalla) { bloqueoPantalla.release().catch(function () {}); bloqueoPantalla = null; }
  }
  document.addEventListener('visibilitychange', function () {
    if (document.visibilityState === 'visible' && corriendo && !bloqueoPantalla) pedirPantallaEncendida();
  });
  window.addEventListener('beforeunload', function (e) {
    if (corriendo) { e.preventDefault(); e.returnValue = ''; }
  });

  // ---------------- contactos ----------------
  function escVcf(s) { return String(s || '').replace(/\\/g, '\\\\').replace(/\n/g, '\\n').replace(/[,;]/g, '\\$&'); }
  async function elegirContactos() {
    try {
      var lista = await navigator.contacts.select(['name', 'tel', 'email'], { multiple: true });
      if (!lista.length) return;
      var vcf = lista.map(function (c) {
        var nombre = (c.name && c.name[0]) || (c.tel && c.tel[0]) || 'Sin nombre';
        var l = ['BEGIN:VCARD', 'VERSION:3.0', 'FN:' + escVcf(nombre)];
        (c.tel || []).forEach(function (t) { l.push('TEL:' + escVcf(t)); });
        (c.email || []).forEach(function (m) { l.push('EMAIL:' + escVcf(m)); });
        l.push('END:VCARD');
        return l.join('\r\n');
      }).join('\r\n');
      var f = new File([vcf], 'contactos-' + hoy() + '-' + lista.length + '.vcf', { type: 'text/vcard', lastModified: Date.now() });
      agregar([f], 'Contactos');
    } catch (e) {
      mostrarError('No se pudieron leer los contactos.');
    }
  }

  // ---------------- medidor de velocidad ----------------
  var midiendo = false;
  var ultimaVelocidad = 0;            // bytes por segundo de la última prueba
  var DURACION_PRUEBA = 8000;
  var CALIFICACIONES = [
    { hasta: 3, clase: 'lenta', texto: 'Lenta', consejo: 'Parece WiFi de 2.4 GHz o el celular está lejos del módem. Acércalo o conéctate a la red de 5 GHz. Para respaldos grandes, el cable USB será más rápido.' },
    { hasta: 10, clase: 'normal', texto: 'Normal', consejo: 'Sirve bien para fotos y documentos. Para muchos videos, prueba la red de 5 GHz o acércate al módem.' },
    { hasta: 25, clase: 'buena', texto: 'Buena', consejo: 'Buena velocidad para respaldar todo por WiFi. Comparable a un cable USB 2.' },
    { hasta: Infinity, clase: 'excelente', texto: 'Excelente', consejo: 'Excelente: tan rápido o más que un cable USB 2. El WiFi es buena opción incluso para videos.' }
  ];

  // Escala no lineal: 0–10 MB/s ocupa media aguja, 10–40 la otra mitad.
  function moverAguja(mbs) {
    var p = mbs <= 10 ? mbs / 10 * 50 : 50 + Math.min(1, (mbs - 10) / 30) * 50;
    $('mArco').setAttribute('stroke-dasharray', Math.max(0, p).toFixed(1) + ' 100');
    $('mVelocidad').textContent = mbs >= 100 ? mbs.toFixed(0) : mbs.toFixed(1);
  }

  function bloqueAleatorio(tam) {
    var datos = new Uint8Array(tam);
    if (window.crypto && crypto.getRandomValues) {
      for (var i = 0; i < tam; i += 65536) crypto.getRandomValues(datos.subarray(i, Math.min(i + 65536, tam)));
    } else {
      for (var j = 0; j < tam; j++) datos[j] = (Math.random() * 256) | 0;
    }
    return new Blob([datos], { type: 'application/octet-stream' });
  }

  function enviarPrueba(blob, alProgreso) {
    return new Promise(function (resolver, rechazar) {
      var xhr = new XMLHttpRequest();
      xhrActual = xhr;
      xhr.open('POST', 'api.php?accion=prueba&t=' + encodeURIComponent(TOKEN));
      xhr.setRequestHeader('Content-Type', 'application/octet-stream');
      xhr.timeout = 30000;
      xhr.upload.onprogress = function (e) { alProgreso(e.loaded); };
      xhr.onload = function () {
        xhrActual = null;
        if (xhr.status >= 200 && xhr.status < 300) resolver();
        else rechazar(new ErrorHttp(xhr.status));
      };
      xhr.onerror = xhr.ontimeout = function () { xhrActual = null; rechazar(new ErrorHttp(0)); };
      xhr.onabort = function () { xhrActual = null; rechazar(new ErrorHttp(-1)); };
      xhr.send(blob);
    });
  }

  async function medirPing() {
    var tiempos = [];
    for (var i = 0; i < 5; i++) {
      var t0 = performance.now();
      await apiGet('ping');
      tiempos.push(performance.now() - t0);
    }
    tiempos.sort(function (a, b) { return a - b; });
    return Math.round(tiempos[2]);
  }

  function mostrarEstimado() {
    if (!ultimaVelocidad) return;
    var t = totales();
    var faltan = t.total - t.enviados;
    if (faltan > 0) {
      $('mEstimadoEtiqueta').textContent = 'para tu lista (' + formatoBytes(faltan) + ')';
      $('mEstimado').textContent = formatoTiempo(faltan / ultimaVelocidad);
    } else {
      $('mEstimadoEtiqueta').textContent = 'para 10 GB';
      $('mEstimado').textContent = formatoTiempo(10 * 1024 * 1024 * 1024 / ultimaVelocidad);
    }
  }

  async function medirVelocidad() {
    if (midiendo || corriendo) return;
    midiendo = true;
    pausado = false;
    var btn = $('btnMedir');
    btn.disabled = true;
    btn.textContent = 'Midiendo…';
    $('mCalificacion').className = 'medidor-calificacion';
    $('mCalificacion').textContent = 'Midiendo respuesta…';
    $('mConsejo').classList.add('oculto');
    mostrarError('');
    render();
    moverAguja(0);

    try {
      var ping = await medirPing();
      $('mPing').textContent = ping + ' ms';
      $('mCalificacion').textContent = 'Enviando datos de prueba…';

      var blob = bloqueAleatorio(Math.min(trozo, 4 * 1024 * 1024));
      var inicio = performance.now();
      var completados = 0;             // bytes de peticiones terminadas
      var enCurso = 0;                 // bytes de la petición actual
      var marcas = [];                 // [tiempo, bytes] para la lectura en vivo
      var primeraMarca = null;         // primera petición completa (descarta el arranque)
      var ultimaMarca = null;

      var reloj = setInterval(function () {
        var ahora = performance.now();
        marcas.push([ahora, completados + enCurso]);
        while (marcas.length > 2 && ahora - marcas[0][0] > 2000) marcas.shift();
        if (marcas.length > 1) {
          var a = marcas[0], b = marcas[marcas.length - 1];
          var dt = (b[0] - a[0]) / 1000;
          if (dt > 0.3) moverAguja((b[1] - a[1]) / dt / 1048576);
        }
        if (ahora - inicio > DURACION_PRUEBA && xhrActual) xhrActual.abort();
      }, 200);

      try {
        while (performance.now() - inicio < DURACION_PRUEBA) {
          enCurso = 0;
          try {
            await enviarPrueba(blob, function (c) { enCurso = c; });
          } catch (e) {
            if (e instanceof ErrorHttp && e.codigo === -1) break;   // se acabó el tiempo
            throw e;
          }
          completados += blob.size;
          enCurso = 0;
          var marca = [performance.now(), completados];
          if (!primeraMarca) primeraMarca = marca; else ultimaMarca = marca;
        }
      } finally {
        clearInterval(reloj);
      }

      var bps;
      if (primeraMarca && ultimaMarca) {
        bps = (ultimaMarca[1] - primeraMarca[1]) / ((ultimaMarca[0] - primeraMarca[0]) / 1000);
      } else if (primeraMarca) {
        bps = primeraMarca[1] / ((primeraMarca[0] - inicio) / 1000);
      } else {
        throw new Error('La red es demasiado lenta para medir en 8 segundos');
      }

      ultimaVelocidad = bps;
      var mbs = bps / 1048576;
      moverAguja(mbs);
      $('mMbits').textContent = (mbs * 8.389).toFixed(0);
      var cal = CALIFICACIONES.filter(function (c) { return mbs < c.hasta; })[0];
      $('mCalificacion').className = 'medidor-calificacion cal-' + cal.clase;
      $('mCalificacion').textContent = cal.texto;
      $('mConsejo').textContent = cal.consejo;
      $('mConsejo').classList.remove('oculto');
      mostrarEstimado();

      apiGet('resultado', { mbps: mbs.toFixed(2), ping: ping, dispositivo: ($('dispositivo').value || '').trim() || 'Celular' }).catch(function () {});
    } catch (e) {
      moverAguja(0);
      $('mVelocidad').textContent = '–';
      $('mCalificacion').className = 'medidor-calificacion cal-lenta';
      $('mCalificacion').textContent = e instanceof ErrorHttp && e.codigo === 401
        ? 'Enlace vencido: vuelve a escanear el QR'
        : (e instanceof ErrorHttp ? 'No se pudo conectar con la PC' : (e.message || 'Error al medir'));
    } finally {
      midiendo = false;
      btn.disabled = false;
      btn.textContent = 'Medir de nuevo';
      render();
    }
  }

  // ---------------- arranque ----------------
  $('btnMedir').addEventListener('click', medirVelocidad);
  $('dispositivo').value = leer('respaldo_dispositivo') || '';

  ['selGaleria', 'selArchivos', 'selCarpeta'].forEach(function (id) {
    $(id).addEventListener('change', function (e) {
      agregar(e.target.files);
      e.target.value = '';
    });
  });

  var esIOS = /iPad|iPhone|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
  if (esIOS || !('webkitdirectory' in document.createElement('input'))) {
    $('btnCarpeta').classList.add('oculto');
  }
  if ('contacts' in navigator && 'select' in navigator.contacts) {
    $('btnContactos').classList.remove('oculto');
    $('btnContactos').addEventListener('click', elegirContactos);
  }

  $('btnIniciar').addEventListener('click', iniciar);
  $('btnPausar').addEventListener('click', function () {
    pausado = true;
    if (xhrActual) xhrActual.abort();
  });
  $('btnReintentar').addEventListener('click', function () {
    cola.forEach(function (it) { if (it.estado === 'error') { it.estado = 'pendiente'; it.error = ''; } });
    iniciar();
  });
  $('btnLimpiar').addEventListener('click', function () {
    cola = []; claves = Object.create(null); mostrarError(''); render();
  });

  apiGet('info').then(function (d) {
    if (d.trozo) trozo = d.trozo;
    $('conexion').textContent = 'Conectado a ' + (d.servidor || 'la PC');
  }).catch(function (e) {
    $('conexion').textContent = 'Sin conexión con la PC';
    mostrarError(e instanceof ErrorHttp && e.codigo === 401
      ? 'Enlace no válido. Vuelve a escanear el QR.'
      : 'No se pudo conectar con la PC. Revisa que ambos estén en la misma red WiFi y que el programa siga abierto en la PC.');
  });

  render();
})();
