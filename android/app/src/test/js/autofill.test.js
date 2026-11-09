// Prueba del motor de autorrelleno con un DOM simulado (sin navegador): node android/app/src/test/js/autofill.test.js
const fs = require('fs'); const path = require('path'); const assert = require('assert');
const src = fs.readFileSync(path.join(__dirname, '../../main/assets/autofill.js'), 'utf8');
class Base { constructor(o){ Object.assign(this,{id:'',value:'',checked:false,textContent:'',offsetParent:{},options:[],clicked:0,parentElement:null},o); }
  focus(){} scrollIntoView(){} dispatchEvent(){return true;} click(){ this.clicked++; if(this.type==='radio') this.checked=true; if(this.onclick) this.onclick(); } }
globalThis.HTMLInputElement = class extends Base {}; globalThis.HTMLSelectElement = class extends Base {}; globalThis.HTMLTextAreaElement = class extends Base {};
function mk(cls, o){ const e = new cls(o); e.tagName = cls===HTMLSelectElement?'SELECT':'INPUT'; return e; }
async function run(elements, cfg, bodyText='') {
  const byId = {}; elements.forEach(e => e.id && (byId[e.id] = e));
  const reports = [];
  globalThis.window = { __citaEngineLoaded: false, jQuery: undefined };
  globalThis.CitaBridge = { report: (j) => reports.push(JSON.parse(j)) };
  globalThis.MutationObserver = class { observe(){} disconnect(){} };
  globalThis.document = {
    title: 'x', body: { innerText: bodyText },
    getElementById: (id) => byId[id] || null,
    getElementsByName: (n) => elements.filter(e => e.name === n),
    querySelector: (s) => {
      let m;
      if ((m = /^#(\w+)$/.exec(s))) return byId[m[1]] || null;
      if ((m = /^input\[name=(\w+)\]$/.exec(s))) return elements.find(e => e.name === m[1]) || null;
      if ((m = /^input\[name=rdbCita\]\[value="(\d+)"\]$/.exec(s))) return elements.find(e => e.name === 'rdbCita' && e.value === m[1]) || null;
      if ((m = /^label\[for="(\w+)"\]$/.exec(s))) return elements.find(e => e.forId === m[1]) || null;
      if (s === '[id^="cita_"]') return elements.find(e => /^cita_/.test(e.id)) || null;
      return null; },
    querySelectorAll: (s) => s === '[id^="cita_"]' ? elements.filter(e => /^cita_/.test(e.id)) : s === 'input[type=radio]' ? elements.filter(e => e.type === 'radio') : s === 'select' ? elements.filter(e => e.tagName === 'SELECT') : [],
  };
  eval(src);
  await window.__citaRun(cfg);
  return reports;
}
(async () => {
  const sel = mk(HTMLSelectElement, { id: 'txtPaisNac', options: [ {text:'Seleccione',value:''}, {text:'GUINEA ECUATORIAL',value:'1'}, {text:'GUINEA REPUBLICA',value:'2'}, {text:'PERU',value:'242'} ] });
  Object.defineProperty(sel, 'selectedIndex', { get(){ return this.options.findIndex(o => o.value === this.value); } });
  const id = () => [ mk(HTMLInputElement,{id:'txtIdCitado'}), mk(HTMLInputElement,{id:'txtDesCitado'}), sel, mk(HTMLInputElement,{id:'rdbTipoDocNie',type:'radio'}), mk(HTMLInputElement,{id:'btnEnviar'}) ];
  const base = { docType:'NIE', docNumber:'X1234567L', fullName:'Ana Prueba', countryCode:'242', countryLabel:'PERU', offices:[] };
  let els = id(); let r = await run(els, base);
  assert.strictEqual(r[0].status, 'filled'); assert.strictEqual(sel.value, '242'); assert.strictEqual(els[4].clicked, 1);   // nacionalidad por código
  sel.value=''; els = id(); r = await run(els, { ...base, countryCode:'', countryLabel:'GUINEA' });
  assert.strictEqual(r[0].status, 'country_not_found'); assert.strictEqual(els[4].clicked, 0);                                // ambigua: NO adivina ni envía
  sel.value=''; els = id(); r = await run(els, { ...base, countryCode:'', countryLabel:'Perú' });
  assert.strictEqual(r[0].status, 'filled'); assert.strictEqual(sel.value, '242');                                           // texto exacto sin tildes
  const slot = (n,d,h) => [ {id:'cita_'+n, textContent:`CITA ${n} Día: ${d} Hora: ${h}`}, {id:'cita'+n, name:'rdbCita', value:String(n), type:'radio'} ];
  const slots = () => [ ...slot(1,'17/02/2026','09:50'), ...slot(2,'18/02/2026','09:30'), ...slot(3,'19/02/2026','09:30'), {name:'txtCaptcha'} ].map(o => mk(HTMLInputElement,o));
  els = slots(); r = await run(els, { minDate:'2026-02-18', offices:[] });
  assert.strictEqual(r[0].status, 'captcha_needed'); assert.ok(r[0].detail.includes('18/02/2026'));
  assert.strictEqual(els.find(e=>e.id==='cita2').clicked, 1); assert.strictEqual(els.find(e=>e.id==='cita1').clicked, 0);       // la más próxima >= fecha mínima
  els = slots(); r = await run(els, { minDate:'2026-03-01', offices:[] });
  assert.strictEqual(r[0].status, 'no_slot_in_range');
  els = slots(); r = await run(els, { minDate:'2026-01-01', offices:[] });
  assert.ok(r[0].detail.includes('17/02/2026'));
  els = []; r = await run(els, {offices:[]}, 'En este momento no hay citas disponibles.'); assert.strictEqual(r[0].status, 'no_slots');
  els = []; r = await run(els, {offices:[]}, 'The requested URL was rejected. Please consult with your administrator.'); assert.strictEqual(r[0].status, 'blocked');
  els = []; r = await run(els, {offices:[]}, 'Información En este momento no hay citas disponibles. En breve, la Oficina pondrá a su disposición nuevas citas. Cod. Oper.: 3967F050C6C042E5D10E09DC681B03E5mol3 27/10/2026 11:25:27');
  assert.strictEqual(r[0].status, 'nie_blocked'); assert.ok(r[0].detail.includes('3967F050'));                                  // Fig. 4: NIE bloqueado
  els = []; r = await run(els, {offices:[]}, 'Información En este momento no hay citas disponibles para la reserva sin cl@ve. SÍ TIENEN A SU DISPOSICIÓN MEDIANTE EL USO DE CL@VE, CITAS DISPONIBLES PARA SU RESERVA.');
  assert.strictEqual(r[0].status, 'no_slots'); assert.ok(r[0].detail.toLowerCase().includes('cl@ve'));                         // Fig. 1
  els = []; r = await run(els, {offices:[]}, 'Tu cita ha sido confirmada. Deberás aportar este justificante el día de la cita.');
  assert.strictEqual(r[0].status, 'confirmed');                                                                              // confirmación final
  console.log('autofill.js: 11 comprobaciones OK');
  process.exit(0);
})().catch((e) => { console.error(e); process.exit(1); });
