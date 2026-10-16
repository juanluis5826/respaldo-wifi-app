(function () {
  'use strict';
  var cfg = window.RESPALDO;
  var $ = function (id) { return document.getElementById(id); };
  var selIp = $('ip');
  var ipManual = null;

  function guardar(k, v) { try { localStorage.setItem(k, v); } catch (e) {} }
  function leer(k) { try { return localStorage.getItem(k); } catch (e) { return null; } }

  // Recordar la IP que funcionó la última vez
  var ipGuardada = leer('respaldo_ip');
  if (ipGuardada) {
    var existe = Array.prototype.some.call(selIp.options, function (o) { return o.value === ipGuardada; });
    if (existe) selIp.value = ipGuardada;
  }

  function ipActual() {
    if (selIp.value === '__manual') return ipManual || location.hostname;
    return selIp.value || location.hostname;
  }

  function enlace() {
    var host = ipActual();
    var puerto = (cfg.puerto === 80) ? '' : ':' + cfg.puerto;
    return 'http://' + host + puerto + cfg.ruta + '/movil.php?t=' + encodeURIComponent(cfg.token);
  }

  function dibujarQR() {
    var url = enlace();
    $('url').textContent = url;
    var caja = $('qr');
    if (typeof qrcode !== 'function') {
      caja.innerHTML = '<p class="nota">No se pudo generar el QR. Escribe el enlace en el navegador del celular.</p>';
      return;
    }
    var qr = qrcode(0, 'M');
    qr.addData(url);
    qr.make();
    caja.innerHTML = qr.createSvgTag({ cellSize: 6, margin: 2, scalable: true });
    var svg = caja.querySelector('svg');
    if (svg) { svg.style.width = '240px'; svg.style.height = '240px'; svg.setAttribute('aria-label', 'Código QR del enlace'); }
  }

  selIp.addEventListener('change', function () {
    if (selIp.value === '__manual') {
      var v = prompt('Escribe la IP de esta PC (ejemplo 192.168.1.10):', ipManual || '');
      if (v && /^\d{1,3}(\.\d{1,3}){3}$/.test(v.trim())) {
        ipManual = v.trim();
      } else if (v !== null) {
        alert('IP no válida');
      }
    } else {
      guardar('respaldo_ip', selIp.value);
    }
    dibujarQR();
  });

  $('copiar').addEventListener('click', function () {
    var url = enlace();
    var listo = function () { $('copiar').textContent = '¡Copiado!'; setTimeout(function () { $('copiar').textContent = 'Copiar enlace'; }, 1500); };
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(url).then(listo, function () { prompt('Copia el enlace:', url); });
    } else {
      prompt('Copia el enlace:', url);
    }
  });

  $('regenerar').addEventListener('click', function () {
    if (!confirm('Se creará un enlace nuevo y el código QR anterior dejará de funcionar. ¿Continuar?')) return;
    fetch('api.php?accion=regenerar', { method: 'POST' })
      .then(function (r) { return r.json(); })
      .then(function (d) { if (d.token) { cfg.token = d.token; dibujarQR(); } })
      .catch(function () { alert('No se pudo generar el enlace nuevo'); });
  });

  function escapar(s) {
    return String(s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function hace(segundos) {
    var d = Math.max(0, Math.floor(Date.now() / 1000 - segundos));
    if (d < 10) return 'ahora';
    if (d < 60) return 'hace ' + d + ' s';
    if (d < 3600) return 'hace ' + Math.floor(d / 60) + ' min';
    if (d < 86400) return 'hace ' + Math.floor(d / 3600) + ' h';
    return new Date(segundos * 1000).toLocaleDateString();
  }

  function mostrarVelocidad(v) {
    if (!v || !v.mbps) return;
    var mbs = Number(v.mbps);
    var cal = mbs < 3 ? ['Lenta', 'est-error'] : mbs < 10 ? ['Normal', ''] : mbs < 25 ? ['Buena', 'est-listo'] : ['Excelente', 'est-listo'];
    var seg10gb = 10 * 1024 / mbs;
    var tiempo = seg10gb < 3600 ? Math.round(seg10gb / 60) + ' min' : (seg10gb / 3600).toFixed(1) + ' h';
    $('velocidad').innerHTML =
      '<div class="vel-pc"><b>' + mbs.toFixed(1) + '</b><span>MB/s</span>' +
      '<span class="estado ' + cal[1] + '" style="font-weight:600">' + cal[0] + '</span></div>' +
      '<p class="nota" style="margin:4px 0 0">' + escapar(v.dispositivo) + ' · ' + hace(v.fecha) +
      ' · respuesta ' + Number(v.ping) + ' ms · 10 GB tardarían ~' + tiempo + '</p>';
  }

  function actualizar() {
    fetch('api.php?accion=listar', { cache: 'no-store' })
      .then(function (r) { return r.json(); })
      .then(function (d) {
        $('vivo').style.visibility = 'visible';
        mostrarVelocidad(d.velocidad);
        var lista = d.respaldos || [];
        if (!lista.length) {
          $('tabla').innerHTML = '<p class="vacio">Todavía no llega nada. Escanea el QR con tu celular para empezar.</p>';
          return;
        }
        var html = '<table><thead><tr><th>Respaldo</th><th class="num">Archivos</th><th class="num">Tamaño</th><th class="num">Último cambio</th></tr></thead><tbody>';
        lista.forEach(function (r) {
          html += '<tr><td>' + escapar(r.nombre) +
            (r.pendientes ? ' <span class="nota">(' + r.pendientes + ' en curso)</span>' : '') +
            '</td><td class="num">' + r.archivos + '</td><td class="num">' + escapar(r.tamano) +
            '</td><td class="num">' + hace(r.actualizado) + '</td></tr>';
        });
        $('tabla').innerHTML = html + '</tbody></table>';
      })
      .catch(function () { $('vivo').style.visibility = 'hidden'; });
  }

  dibujarQR();
  actualizar();
  setInterval(actualizar, 4000);
})();
