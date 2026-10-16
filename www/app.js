(function () {
  'use strict';

  // Puente con la parte nativa (android/app/src/main/java/mx/respaldowifi/app/RespaldoPlugin.java).
  var C = window.Capacitor;
  var R = C && C.registerPlugin ? C.registerPlugin('Respaldo') : (C && C.Plugins && C.Plugins.Respaldo);

  var $ = function (id) { return document.getElementById(id); };
  var MAX_VISIBLES = 150;

  var archivos = [];              // { id, ruta, tam, estado, enviados, error }
  var porId = Object.create(null);
  var totales = { total: 0, enviados: 0, n: 0, listos: 0, errores: 0, pendientes: 0 };
  var corriendo = false;
  var velocidadActual = 0;
  var avanceParcial = false;
  var midiendo = false;
  var eligiendo = false;
  var ultimaVelocidad = 0;
  var pendienteRender = false;
  var conectado = false;

  // ---------------- utilidades (iguales a movil.js) ----------------
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
  function textoError(e) { return (e && (e.message || e.errorMessage)) || String(e || 'Error'); }
  function mostrarError(msg) {
    var c = $('cajaError');
    if (!msg) { c.classList.add('oculto'); c.textContent = ''; return; }
    c.textContent = msg;
    c.classList.remove('oculto');
  }
  function nombreDispositivo() { return ($('dispositivo').value || '').trim(); }

  // ---------------- datos que llegan de la parte nativa ----------------
  function aplicarResumen(d) {
    if (d.totales) totales = d.totales;
    corriendo = !!d.corriendo;
    velocidadActual = d.velocidad || 0;
    avanceParcial = !!d.avanceParcial;
    if (d.archivos) {
      archivos = d.archivos;
      porId = Object.create(null);
      archivos.forEach(function (a) { porId[a.id] = a; });
    }
    (d.cambios || []).forEach(function (c) {
      var a = porId[c.id];
      if (a) { a.estado = c.estado; a.enviados = c.enviados; a.error = c.error; }
    });
    $('panelBateria').classList.toggle('oculto', !!d.bateriaLibre);
    render();
  }

  function recargar() {
    return R.obtenerEstado().then(function (d) {
      mostrarConexion(d);
      if (!$('dispositivo').value) $('dispositivo').value = d.dispositivo || '';
      $('dispositivo').placeholder = 'Ej. ' + (d.modelo || 'Celular de Juan');
      aplicarResumen(d);
      if (d.mensaje && !corriendo && totales.pendientes > 0) mostrarError(d.mensaje);
      return d;
    });
  }

  function mostrarConexion(d) {
    conectado = !!d.conexion;
    $('pantallaConectar').classList.toggle('oculto', conectado);
    $('pantallaPrincipal').classList.toggle('oculto', !conectado);
    $('btnCambiarPc').classList.toggle('oculto', !conectado);
    $('conexion').textContent = conectado
      ? 'Conectado a ' + (d.conexion.servidor || 'la PC') + ' (' + d.conexion.host + ')'
      : 'Sin conectar';
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
    var t = totales;
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
    var v = corriendo ? velocidadActual : 0;
    $('cVelocidad').textContent = v > 0 ? formatoBytes(v) + '/s' : '–';
    $('cRestante').textContent = v > 0 ? formatoTiempo((t.total - t.enviados) / v) : '–';

    $('btnIniciar').disabled = corriendo || midiendo || eligiendo || t.pendientes === 0;
    $('btnMedir').disabled = corriendo || midiendo;
    if (!corriendo) mostrarEstimado();
    $('btnIniciar').textContent = corriendo ? 'Respaldando…' : (avanceParcial ? 'Continuar respaldo' : 'Iniciar respaldo');
    $('btnPausar').classList.toggle('oculto', !corriendo);
    $('btnReintentar').classList.toggle('oculto', corriendo || t.errores === 0);
    $('btnLimpiar').classList.toggle('oculto', corriendo || t.n === 0);
    $('avisoPantalla').classList.toggle('oculto', !corriendo);
    Array.prototype.forEach.call(document.querySelectorAll('.selectores .boton'), function (b) { b.disabled = eligiendo; });

    // Lista: primero lo que se está enviando, luego errores, pendientes y lo último terminado.
    var orden = { subiendo: 0, error: 1, pendiente: 2, listo: 3, omitido: 3 };
    var visibles = archivos.slice().sort(function (a, b) {
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

    var resto = archivos.length - visibles.length;
    $('masItems').classList.toggle('oculto', resto <= 0);
    $('masItems').textContent = resto > 0 ? '… y ' + resto + ' archivos más' : '';
  }

  // ---------------- elegir archivos (selector nativo de Android) ----------------
  function elegir(tipo) {
    if (eligiendo) return;
    eligiendo = true;
    mostrarError('');
    render();
    R.elegir({ tipo: tipo }).then(function (r) {
      if (r.cancelado) return;
      if (r.repetidos && !r.nuevos) mostrarError('Esos archivos ya estaban en la lista.');
      else if (!r.nuevos) mostrarError(tipo === 'carpeta' ? 'Esa carpeta no tiene archivos (o Android no deja leerla).' : 'No se agregó ningún archivo.');
    }).catch(function (e) {
      mostrarError(textoError(e));
    }).then(function () {
      eligiendo = false;
      $('notaSeleccion').textContent = 'Puedes combinar: elige fotos, luego archivos, y todo se agrega a la lista.';
      return recargar();
    });
  }

  // ---------------- respaldo ----------------
  function iniciar() {
    if (corriendo || midiendo) return;
    mostrarError('');
    R.pedirNotificaciones().catch(function () {}).then(function () {
      return R.iniciar({ dispositivo: nombreDispositivo() });
    }).then(function () {
      corriendo = true;
      render();
    }).catch(function (e) { mostrarError(textoError(e)); });
  }

  // ---------------- conectar ----------------
  function conectar(enlace) {
    $('errorConectar').classList.add('oculto');
    $('btnConectar').disabled = true;
    $('btnConectar').textContent = 'Conectando…';
    return R.conectar({ enlace: enlace }).then(function () {
      $('enlaceManual').value = '';
      return recargar();
    }).catch(function (e) {
      $('errorConectar').textContent = textoError(e);
      $('errorConectar').classList.remove('oculto');
    }).then(function () {
      $('btnConectar').disabled = false;
      $('btnConectar').textContent = 'Conectar';
    });
  }

  // ---------------- escáner de QR (cámara) ----------------
  var flujo = null, buscando = false;
  function abrirEscaner() {
    $('errorConectar').classList.add('oculto');
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
      $('errorConectar').textContent = 'Este celular no permite usar la cámara aquí. Pega el enlace.';
      $('errorConectar').classList.remove('oculto');
      return;
    }
    navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' }, audio: false }).then(function (s) {
      flujo = s;
      var v = $('video');
      v.srcObject = s;
      v.play();
      $('escaner').classList.remove('oculto');
      $('escanerTexto').textContent = 'Apunta al código QR de la pantalla de la PC';
      buscando = true;
      requestAnimationFrame(leerCuadro);
    }).catch(function () {
      $('errorConectar').textContent = 'No se pudo usar la cámara. Revisa que la app tenga permiso de Cámara, o pega el enlace.';
      $('errorConectar').classList.remove('oculto');
    });
  }
  function cerrarEscaner() {
    buscando = false;
    if (flujo) { flujo.getTracks().forEach(function (t) { t.stop(); }); flujo = null; }
    $('escaner').classList.add('oculto');
  }
  var ultimoCuadro = 0;
  function leerCuadro(ts) {
    if (!buscando) return;
    var v = $('video');
    if (v.readyState >= 2 && ts - ultimoCuadro > 120) {
      ultimoCuadro = ts;
      var lienzo = $('lienzo');
      var escala = Math.min(1, 720 / Math.max(v.videoWidth, v.videoHeight));
      lienzo.width = Math.round(v.videoWidth * escala);
      lienzo.height = Math.round(v.videoHeight * escala);
      var ctx = lienzo.getContext('2d', { willReadFrequently: true });
      ctx.drawImage(v, 0, 0, lienzo.width, lienzo.height);
      var img = ctx.getImageData(0, 0, lienzo.width, lienzo.height);
      var codigo = window.jsQR(img.data, img.width, img.height, { inversionAttempts: 'dontInvert' });
      if (codigo && codigo.data) {
        if (/^https?:\/\/.+[?&]t=/i.test(codigo.data)) {
          cerrarEscaner();
          if (navigator.vibrate) { try { navigator.vibrate(60); } catch (e) {} }
          conectar(codigo.data);
          return;
        }
        $('escanerTexto').textContent = 'Ese código no es de Respaldo WiFi. Escanea el QR del panel de la PC.';
      }
    }
    requestAnimationFrame(leerCuadro);
  }

  // ---------------- medidor de velocidad (la medición la hace la parte nativa) ----------------
  var CALIFICACIONES = [
    { hasta: 3, clase: 'lenta', texto: 'Lenta', consejo: 'Parece WiFi de 2.4 GHz o el celular está lejos del módem. Acércalo o conéctate a la red de 5 GHz. Para respaldos grandes, el cable USB será más rápido.' },
    { hasta: 10, clase: 'normal', texto: 'Normal', consejo: 'Sirve bien para fotos y documentos. Para muchos videos, prueba la red de 5 GHz o acércate al módem.' },
    { hasta: 25, clase: 'buena', texto: 'Buena', consejo: 'Buena velocidad para respaldar todo por WiFi. Comparable a un cable USB 2.' },
    { hasta: Infinity, clase: 'excelente', texto: 'Excelente', consejo: 'Excelente: tan rápido o más que un cable USB 2. El WiFi es buena opción incluso para videos.' }
  ];

  function moverAguja(mbs) {
    var p = mbs <= 10 ? mbs / 10 * 50 : 50 + Math.min(1, (mbs - 10) / 30) * 50;
    $('mArco').setAttribute('stroke-dasharray', Math.max(0, p).toFixed(1) + ' 100');
    $('mVelocidad').textContent = mbs >= 100 ? mbs.toFixed(0) : mbs.toFixed(1);
  }

  function mostrarEstimado() {
    if (!ultimaVelocidad) return;
    var faltan = totales.total - totales.enviados;
    if (faltan > 0) {
      $('mEstimadoEtiqueta').textContent = 'para tu lista (' + formatoBytes(faltan) + ')';
      $('mEstimado').textContent = formatoTiempo(faltan / ultimaVelocidad);
    } else {
      $('mEstimadoEtiqueta').textContent = 'para 10 GB';
      $('mEstimado').textContent = formatoTiempo(10 * 1024 * 1024 * 1024 / ultimaVelocidad);
    }
  }

  function medirVelocidad() {
    if (midiendo || corriendo) return;
    midiendo = true;
    var btn = $('btnMedir');
    btn.textContent = 'Midiendo…';
    $('mCalificacion').className = 'medidor-calificacion';
    $('mCalificacion').textContent = 'Midiendo respuesta…';
    $('mConsejo').classList.add('oculto');
    mostrarError('');
    render();
    moverAguja(0);

    R.medirVelocidad({ dispositivo: nombreDispositivo() || 'Celular' }).then(function (r) {
      var mbs = r.mbs;
      ultimaVelocidad = mbs * 1048576;
      moverAguja(mbs);
      $('mPing').textContent = r.ping + ' ms';
      $('mMbits').textContent = (mbs * 8.389).toFixed(0);
      var cal = CALIFICACIONES.filter(function (c) { return mbs < c.hasta; })[0];
      $('mCalificacion').className = 'medidor-calificacion cal-' + cal.clase;
      $('mCalificacion').textContent = cal.texto;
      $('mConsejo').textContent = cal.consejo;
      $('mConsejo').classList.remove('oculto');
      mostrarEstimado();
    }).catch(function (e) {
      moverAguja(0);
      $('mVelocidad').textContent = '–';
      $('mCalificacion').className = 'medidor-calificacion cal-lenta';
      $('mCalificacion').textContent = textoError(e);
    }).then(function () {
      midiendo = false;
      btn.textContent = 'Medir de nuevo';
      render();
    });
  }

  // ---------------- arranque ----------------
  if (!R) {
    document.body.innerHTML = '<div class="contenedor estrecho"><div class="tarjeta"><h2>Abre esta página desde la app</h2><p class="nota">Esta interfaz es parte de la app de Android Respaldo WiFi.</p></div></div>';
    return;
  }

  R.addListener('progreso', aplicarResumen);
  R.addListener('cola', function () { recargar(); });
  R.addListener('leyendo', function (d) {
    $('notaSeleccion').textContent = 'Leyendo carpeta… ' + d.encontrados + ' archivos encontrados';
  });
  R.addListener('fin', function (d) {
    recargar().then(function () {
      if (d.motivo === 'COMPLETO') {
        mostrarError('');
        if (navigator.vibrate) { try { navigator.vibrate([120, 80, 120]); } catch (e) {} }
      } else if (d.motivo !== 'PAUSADO' && d.motivo !== 'CON_ERRORES') {
        mostrarError(d.mensaje);
      }
    });
  });
  R.addListener('medicion', function (d) {
    if (d.fase === 'enviando' && d.ping != null) {
      $('mPing').textContent = d.ping + ' ms';
      $('mCalificacion').textContent = 'Enviando datos de prueba…';
    }
    if (d.mbs != null) moverAguja(d.mbs);
  });

  $('btnEscanear').addEventListener('click', abrirEscaner);
  $('btnCerrarEscaner').addEventListener('click', cerrarEscaner);
  $('btnConectar').addEventListener('click', function () { conectar($('enlaceManual').value); });
  $('enlaceManual').addEventListener('keydown', function (e) { if (e.key === 'Enter') conectar($('enlaceManual').value); });
  $('btnCambiarPc').addEventListener('click', function () {
    R.olvidarPc().then(recargar).catch(function (e) { mostrarError(textoError(e)); });
  });

  Array.prototype.forEach.call(document.querySelectorAll('.selectores .boton'), function (b) {
    b.addEventListener('click', function () { elegir(b.getAttribute('data-tipo')); });
  });
  $('dispositivo').addEventListener('change', function () { R.guardarDispositivo({ dispositivo: nombreDispositivo() }); });
  $('btnMedir').addEventListener('click', medirVelocidad);
  $('btnIniciar').addEventListener('click', iniciar);
  $('btnPausar').addEventListener('click', function () { R.pausar(); });
  $('btnReintentar').addEventListener('click', function () {
    mostrarError('');
    R.reintentarFallidos({ dispositivo: nombreDispositivo() }).catch(function (e) { mostrarError(textoError(e)); });
  });
  $('btnLimpiar').addEventListener('click', function () {
    R.vaciar().then(function () { mostrarError(''); return recargar(); }).catch(function (e) { mostrarError(textoError(e)); });
  });
  $('btnBateria').addEventListener('click', function () { R.abrirAjustesBateria(); });

  // Al volver a la app, refrescar todo (pudo avanzar mucho con la pantalla apagada).
  document.addEventListener('visibilitychange', function () {
    if (document.visibilityState === 'visible') recargar();
  });

  recargar().catch(function (e) { $('conexion').textContent = 'Error: ' + textoError(e); });
})();
