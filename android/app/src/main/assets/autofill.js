/*
 * Motor de autorrelleno de CitaAsistente.
 * Se inyecta SOLO en páginas de la sede electrónica oficial (administracionelectronica.gob.es).
 * Detecta en qué paso del formulario está la página y lo rellena con los datos del usuario.
 * Se detiene SIEMPRE ante lo que debe hacer una persona: el captcha y el código SMS.
 * No resuelve captchas ni evita ninguna comprobación del sitio.
 * Selectores tomados del flujo real (ids del formulario oficial).
 */
(function () {
  if (window.__citaEngineLoaded) return;
  window.__citaEngineLoaded = true;

  const norm = (s) => (s || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase()
    .replace(/[`´'’‘"“”]/g, '').replace(/\s+/g, ' ').trim();
  const byId = (id) => document.getElementById(id);
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const pause = () => sleep(500 + Math.floor(Math.random() * 500));
  const report = (step, status, detail) => {
    try { CitaBridge.report(JSON.stringify({ step: step, status: status, detail: detail || '' })); } catch (e) { }
  };

  function setValue(el, value) {
    if (!el) return false;
    el.focus();
    const proto = el.tagName === 'SELECT' ? HTMLSelectElement.prototype
      : el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    const d = Object.getOwnPropertyDescriptor(proto, 'value');
    if (d && d.set) d.set.call(el, value); else el.value = value;
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
    el.dispatchEvent(new Event('blur'));
    return true;
  }

  function options(sel) {
    return Array.prototype.map.call(sel.options, (o) => ({ text: (o.text || '').trim(), value: o.value }));
  }

  /** valor exacto → texto exacto → texto contiene (todo normalizado) */
  function matchOption(sel, wanted) {
    const w = norm(wanted);
    const real = options(sel).filter((o) => o.value !== '');
    let hit = real.find((o) => o.value === wanted);
    if (!hit) hit = real.find((o) => norm(o.text) === w);
    if (!hit && w) hit = real.find((o) => norm(o.text).indexOf(w) >= 0);
    return hit ? hit.value : null;
  }

  /** Nacionalidad: valor exacto del portal o texto idéntico (sin tildes/mayúsculas). NUNCA parcial. */
  function matchExact(sel, code, label) {
    const real = options(sel).filter((o) => o.value !== '');
    let hit = real.find((o) => o.value === code);
    if (!hit && label) {
      const n = norm(label);
      const hits = real.filter((o) => norm(o.text) === n);
      if (hits.length === 1) hit = hits[0];
    }
    return hit ? hit.value : null;
  }

  function refreshPicker(sel) {
    try { if (window.jQuery && window.jQuery.fn && window.jQuery.fn.selectpicker) window.jQuery(sel).selectpicker('refresh'); } catch (e) { }
  }

  function matchProvince(sel, p) {
    const real = options(sel).filter((o) => o.value !== '');
    const n = norm(p.name);
    let hit = real.find((o) => norm(o.text) === n);
    if (!hit) {
      const num = String(parseInt(p.code, 10));
      const re = new RegExp('[?&]p=0*' + num + '(?:&|$)');
      hit = real.find((o) => re.test(o.value));
    }
    if (!hit) hit = real.find((o) => norm(o.text).indexOf(n) >= 0);
    return hit ? hit.value : null;
  }

  async function clickEl(el) {
    if (!el) return false;
    el.scrollIntoView({ block: 'center' });
    await pause();
    el.click();
    return true;
  }

  function labelText(radio) {
    let t = '';
    if (radio.id) {
      const l = document.querySelector('label[for="' + radio.id + '"]');
      if (l) t = l.textContent;
    }
    if (!t && radio.parentElement) t = radio.parentElement.textContent;
    return norm((t || radio.value || '').replace(/[.\s]/g, ''));
  }

  function pickDocType(type) {
    const byIdSel = type === 'PASAPORTE' ? '#rdbTipoDocPas' : type === 'DNI' ? '#rdbTipoDocDni' : '#rdbTipoDocNie';
    const direct = document.querySelector(byIdSel);
    if (direct) { if (!direct.checked) direct.click(); return true; }
    const key = type === 'PASAPORTE' ? 'pasaporte' : type === 'DNI' ? 'dni' : 'nie';
    const radios = Array.prototype.slice.call(document.querySelectorAll('input[type=radio]'));
    const hit = radios.find((r) => labelText(r).indexOf(key) >= 0);
    if (hit && !hit.checked) hit.click();
    return !!hit;
  }

  function parseDate(s) {
    const m = /(\d{1,2})\/(\d{1,2})\/(\d{4})/.exec(s || '');
    if (!m) return null;
    const d = new Date(parseInt(m[3], 10), parseInt(m[2], 10) - 1, parseInt(m[1], 10));
    const h = /(\d{1,2}):(\d{2})/.exec(s || '');
    if (h) d.setHours(parseInt(h[1], 10), parseInt(h[2], 10), 0, 0);
    return d;
  }

  function pickSlot(cfg) {
    const nodes = Array.prototype.slice.call(document.querySelectorAll('[id^="cita_"]'));
    // «Fecha mínima»: se acepta la cita MÁS PRÓXIMA a partir de ese día (puede ser diciembre, enero...).
    const min = cfg.minDate ? new Date(cfg.minDate + 'T00:00:00') : new Date(0);
    const all = [];
    nodes.forEach((n) => {
      const m = /^cita_(\d+)$/.exec(n.id);
      const d = parseDate(n.textContent);
      if (m && d) all.push({ idx: m[1], date: d, text: n.textContent.replace(/\s+/g, ' ').trim() });
    });
    all.sort((a, b) => a.date - b.date || parseInt(a.idx, 10) - parseInt(b.idx, 10));
    const ok = all.filter((c) => c.date >= min);
    return { all: all, chosen: ok.length ? ok[0] : null };
  }

  function focusCaptcha() {
    const c = document.querySelector('input[name=txtCaptcha]') || byId('captcha');
    if (c) { c.scrollIntoView({ block: 'center' }); try { c.focus(); } catch (e) { } }
  }

  async function doSlots(cfg) {
    const res = pickSlot(cfg);
    if (!res.all.length) {
      // Variante con calendario: la persona elige día/hora; nos quedamos vigilando a que aparezcan las opciones.
      report('slots', 'manual_calendar', 'Elige día y hora en el calendario; el resto lo relleno yo.');
      const obs = new MutationObserver(() => {
        const r = pickSlot(cfg);
        if (r.all.length) { obs.disconnect(); doSlots(cfg); }
      });
      obs.observe(document.body, { childList: true, subtree: true });
      setTimeout(() => obs.disconnect(), 10 * 60 * 1000);
      return;
    }
    if (!res.chosen) {
      report('slots', 'no_slot_in_range', 'Hay citas, pero la primera (' + res.all[0].text + ') es anterior a tu fecha mínima o no cumple el rango.');
      return;
    }
    const radio = byId('cita' + res.chosen.idx)
      || document.querySelector('input[name=rdbCita][value="' + res.chosen.idx + '"]');
    if (radio) { await pause(); radio.click(); }
    focusCaptcha();
    report('slots', 'captcha_needed', 'Elegida: ' + res.chosen.text + '. Escribe el captcha y pulsa Siguiente.');
  }

  async function dispatch(cfg) {
    const text = (document.body && document.body.innerText) || '';
    const t = norm(text);

    if (t.indexOf('su sesion ha caducado') >= 0) {
      const b = byId('btnAceptar') || Array.prototype.find.call(document.querySelectorAll('input[type=button],input[type=submit],button'),
        (x) => norm(x.value || x.textContent) === 'aceptar');
      report('session', 'session_expired', 'Sesión caducada; reinicio el flujo.');
      if (b) b.click();
      return;
    }
    if (t.indexOf('the requested url was rejected') >= 0) { report('blocked', 'blocked', 'El sitio rechazó la petición. Espera unos minutos antes de reintentar.'); return; }
    if (t.indexOf('se ha producido un error en el sistema') >= 0) { report('system', 'system_error', 'Error del sistema de la sede.'); return; }

    const cookie = byId('cookie_action_close_header');
    if (cookie && cookie.offsetParent !== null) { try { cookie.click(); } catch (e) { } }

    // ---- Estados finales / de espera humana ----
    if (byId('justificanteFinal') || t.indexOf('cita confirmada') >= 0) {
      report('confirmed', 'confirmed', text.replace(/\s+/g, ' ').trim().slice(0, 900));
      return;
    }
    if (byId('txtCodigoVerificacion')) {
      if (cfg.autoConsent) { const c = byId('chkTotal'); if (c && !c.checked) c.click(); }
      const box = byId('txtCodigoVerificacion');
      box.scrollIntoView({ block: 'center' }); try { box.focus(); } catch (e) { }
      report('sms', 'sms_needed', 'Escribe el código SMS que te llegará' + (cfg.autoConsent ? '.' : ', marca la casilla de conformidad') + ' y confirma.');
      return;
    }
    if (document.querySelector('[id^="cita_"]') || document.getElementsByName('rdbCita').length || byId('captcha')) {
      await doSlots(cfg); return;
    }
    if (t.indexOf('en este momento no hay citas disponibles') >= 0 || /si\s+tienen\s+a\s+su\s+disposicion\s+mediante\s+el\s+uso\s+de\s+cl@ve/.test(t)) {
      report('no_slots', 'no_slots', 'Sin citas por ahora.');
      return;
    }

    // ---- Pasos del formulario ----
    if (byId('idSede')) {                                   // 05 Sede
      const sel = byId('idSede');
      let value = null;
      for (let i = 0; i < cfg.offices.length && !value; i++) {
        const w = norm(cfg.offices[i]);
        const real = options(sel).filter((o) => o.value !== '');
        const hit = real.find((o) => norm(o.text) === w) || real.find((o) => norm(o.text).indexOf(w) >= 0);
        if (hit) value = hit.value;
      }
      if (!value && !cfg.offices.length) {
        const first = options(sel).find((o) => o.value !== '');
        value = first ? first.value : null;
      }
      if (!value) { report('office', 'no_office_match', 'Ninguna de tus sedes está disponible ahora. Elige una manualmente.'); return; }
      await pause(); setValue(sel, value);
      report('office', 'filled', sel.options[sel.selectedIndex].text);
      await clickEl(byId('btnSiguiente'));
      return;
    }
    if (byId('txtTelefonoCitado')) {                        // 06 Contacto
      await pause();
      setValue(byId('txtTelefonoCitado'), cfg.phone);
      if (byId('emailUNO')) setValue(byId('emailUNO'), cfg.email);
      if (byId('emailDOS')) setValue(byId('emailDOS'), cfg.email);
      report('contact', 'filled', 'Teléfono y correo');
      await clickEl(byId('btnSiguiente'));
      return;
    }
    if (byId('txtIdCitado')) {                              // 03 Identificación
      await pause();
      pickDocType(cfg.docType);
      setValue(byId('txtIdCitado'), cfg.docNumber);
      if (byId('txtDesCitado')) setValue(byId('txtDesCitado'), cfg.fullName);
      const pais = byId('txtPaisNac');
      if (pais) {
        const v = matchExact(pais, cfg.countryCode, cfg.countryLabel);
        if (!v) { report('identity', 'country_not_found', 'La nacionalidad "' + cfg.countryLabel + '" no coincide con la lista del portal. Elígela tú.'); return; }
        setValue(pais, v);
        refreshPicker(pais);
        if (pais.value !== v) { report('identity', 'country_not_found', 'No se pudo fijar la nacionalidad "' + cfg.countryLabel + '".'); return; }
      }
      if (byId('txtIdCitado').value !== cfg.docNumber) { report('identity', 'fill_failed', 'No pude escribir el documento.'); return; }
      report('identity', 'filled', cfg.docType);
      await clickEl(byId('btnEnviar'));
      return;
    }
    if (byId('btnEntrar')) {                                // 02 Entrar
      report('entrar', 'filled', 'Entrar');
      await clickEl(byId('btnEntrar'));
      return;
    }
    const tram = byId('tramiteGrupo[0]');
    if (tram) {                                             // 01 Trámite
      await pause();
      const sels = Array.prototype.slice.call(document.querySelectorAll('select'));
      const officeSel = sels.find((s) => s !== tram && options(s).some((o) => norm(o.text) === 'cualquier oficina'));
      if (officeSel) setValue(officeSel, options(officeSel).find((o) => norm(o.text) === 'cualquier oficina').value);
      let v = matchOption(tram, cfg.procedureId);
      if (!v) v = matchOption(tram, cfg.procedureName);
      if (!v) { report('tramite', 'procedure_not_found', 'El trámite "' + cfg.procedureName + '" no está en la lista de esta provincia.'); return; }
      setValue(tram, v);
      report('tramite', 'filled', cfg.procedureName);
      await clickEl(byId('btnAceptar'));
      return;
    }
    if (byId('form') && byId('form').tagName === 'SELECT' && byId('btnAceptar')) {   // 00 Provincia
      await pause();
      const v = matchProvince(byId('form'), cfg.province);
      if (!v) { report('province', 'province_not_found', 'Provincia "' + cfg.province.name + '" no encontrada en la lista.'); return; }
      setValue(byId('form'), v);
      report('province', 'filled', cfg.province.name);
      await clickEl(byId('btnAceptar'));
      return;
    }
    if (byId('btnEnviar')) {                                // 04 Confirmación de identidad
      report('identity_confirm', 'filled', 'Confirmando datos');
      await clickEl(byId('btnEnviar'));
      return;
    }
    report('unknown', 'unknown', (document.title || '') + ' — página no reconocida; continúa a mano.');
  }

  window.__citaRun = async function (cfg) {
    if (window.__citaRunning) return;
    window.__citaRunning = true;
    try { await dispatch(cfg); } catch (e) { report('error', 'error', String((e && e.message) || e)); }
  };
})();
