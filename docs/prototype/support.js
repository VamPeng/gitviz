/*
 * GitViz 原型运行时。让 *.dc.html 直接在浏览器里打开（file:// 也行）。
 *
 * 支持的写法（就是原型里用到的那些）：
 *   <x-dc>…模板…</x-dc>                    模板根；<helmet> 里的 <style> 会挪进 <head>
 *   {{a.b.c}}                              文本或属性里插值；整个属性只有一个 {{}} 时保留原值（函数、布尔）
 *   <sc-for list="{{xs}}" as="x">          列表
 *   <sc-if value="{{ok}}">                 条件
 *   onClick / onInput / onChange / onDoubleClick / onContextMenu="{{fn}}"
 *   value / checked / disabled             作为 DOM 属性同步，输入时不会丢焦点
 *   <script type="text/x-dc"> class Component extends DCLogic { renderVals() {…} }
 *   this.state / this.setState(patch | fn)，data-props 的 JSON 作为 props
 */
(function () {
  'use strict';

  // 后面的整个 <body> 都解析进一个惰性 <template>：模板不会闪出来，
  // onclick="{{…}}" 不会变成真的内联事件，SVG 的 d="{{…}}" 也不会在控制台报错。
  document.write('<style>x-dc{display:none!important}html{background:#8a8a86}body{margin:0}' +
    '.dc-stage{padding:24px;box-sizing:border-box;min-height:100vh;display:flex;justify-content:center;align-items:flex-start}' +
    '.dc-frame{flex:none;box-shadow:0 12px 40px rgba(0,0,0,.28);border-radius:14px}</style>' +
    '<template id="dc-raw">');

  class DCLogic {
    constructor(props) {
      this.props = props || {};
      this.state = {};
    }
    setState(patch) {
      const next = typeof patch === 'function' ? patch(this.state, this.props) : patch;
      if (next) this.state = Object.assign({}, this.state, next);
      if (this.__schedule) this.__schedule();
    }
    renderVals() { return {}; }
  }
  window.DCLogic = DCLogic;

  const WHOLE = /^\s*\{\{\s*([^{}]+?)\s*\}\}\s*$/;
  const ANY = /\{\{\s*([^{}]+?)\s*\}\}/g;
  const EVENTS = { click: 'click', doubleclick: 'dblclick', contextmenu: 'contextmenu', input: 'input', change: 'change',
    keydown: 'keydown', keyup: 'keyup', focus: 'focus', blur: 'blur', mousedown: 'mousedown', mouseup: 'mouseup',
    mouseenter: 'mouseenter', mouseleave: 'mouseleave', submit: 'submit', scroll: 'scroll' };
  const PROPS = { value: 1, checked: 1, disabled: 1 };

  function lookup(path, scope) {
    const parts = path.split('.');
    let v = scope;
    for (const p of parts) {
      if (v == null) return undefined;
      v = v[p];
    }
    return v;
  }
  const str = (v) => (v == null || v === false ? '' : String(v));
  function interp(text, scope) {
    const m = WHOLE.exec(text);
    if (m) return lookup(m[1], scope);
    return text.replace(ANY, (_, p) => str(lookup(p, scope)));
  }

  // 模板 → 虚拟节点
  function build(nodes, scope, out) {
    for (const n of nodes) {
      if (n.nodeType === 3) {
        out.push({ text: n.data.indexOf('{{') < 0 ? n.data : str(interp(n.data, scope)) });
      } else if (n.nodeType === 1) {
        const tag = n.localName;
        if (tag === 'sc-for') {
          const list = interp(n.getAttribute('list') || '', scope) || [];
          const as = n.getAttribute('as') || 'item';
          Array.prototype.forEach.call(list, (item, i) => {
            const inner = Object.create(scope);
            inner[as] = item;
            inner[as + 'Index'] = i;
            build(n.childNodes, inner, out);
          });
        } else if (tag === 'sc-if') {
          if (interp(n.getAttribute('value') || '', scope)) build(n.childNodes, scope, out);
        } else if (tag === 'helmet') {
          // 已在启动时处理
        } else {
          const v = { tag, ns: n.namespaceURI, attrs: {}, props: {}, on: {}, kids: [] };
          for (const a of n.attributes) {
            const name = a.name;
            const val = a.value.indexOf('{{') < 0 ? a.value : interp(a.value, scope);
            if (name.length > 2 && name.slice(0, 2) === 'on' && EVENTS[name.slice(2)]) {
              if (typeof val === 'function') v.on[EVENTS[name.slice(2)]] = val;
            } else if (PROPS[name] && /\{\{/.test(a.value)) {
              v.props[name] = val;
            } else if (val === false || val == null) {
              // 不输出
            } else {
              v.attrs[name] = val === true ? (name.indexOf('aria-') === 0 ? 'true' : '') : String(val);
            }
          }
          build(n.childNodes, scope, v.kids);
          out.push(v);
        }
      }
    }
    return out;
  }

  // 虚拟节点 → 真实 DOM（按位置原地更新）
  function create(v) {
    if (v.text != null) return document.createTextNode(v.text);
    const el = document.createElementNS(v.ns, v.tag);
    el.__dc = { attrs: {}, on: {} };
    return el;
  }
  function same(el, v) {
    if (v.text != null) return el.nodeType === 3;
    return el.nodeType === 1 && el.localName === v.tag && el.namespaceURI === v.ns && el.__dc;
  }
  function update(el, v) {
    if (v.text != null) {
      if (el.data !== v.text) el.data = v.text;
      return;
    }
    const meta = el.__dc;
    for (const k in meta.attrs) if (!(k in v.attrs)) el.removeAttribute(k);
    for (const k in v.attrs) if (meta.attrs[k] !== v.attrs[k]) el.setAttribute(k, v.attrs[k]);
    meta.attrs = v.attrs;
    for (const evt in v.on) {
      if (!meta.on[evt]) el.addEventListener(evt, (e) => { const f = el.__dc.on[evt]; if (f) f(e); });
    }
    for (const evt in meta.on) if (!v.on[evt]) meta.on[evt] = null;
    Object.assign(meta.on, v.on);
    patchKids(el, v.kids);
    // 子节点（如 <option>）就位后再同步 value
    if ('value' in v.props) { const s = str(v.props.value); if (el.value !== s) el.value = s; }
    if ('checked' in v.props) el.checked = !!v.props.checked;
    if ('disabled' in v.props) el.disabled = !!v.props.disabled;
  }
  function patchKids(parent, kids) {
    for (let i = 0; i < kids.length; i++) {
      const cur = parent.childNodes[i];
      if (cur && same(cur, kids[i])) {
        update(cur, kids[i]);
      } else {
        const el = create(kids[i]);
        update(el, kids[i]);
        if (cur) parent.replaceChild(el, cur); else parent.appendChild(el);
      }
    }
    while (parent.childNodes.length > kids.length) parent.removeChild(parent.lastChild);
  }

  function boot() {
    const raw = document.getElementById('dc-raw');
    const scopeRoot = raw ? raw.content : document;
    const host = scopeRoot.querySelector('x-dc');
    if (!host) return;
    const tpl = document.createElement('template');
    tpl.innerHTML = host.innerHTML;
    const src = scopeRoot.querySelector('script[type="text/x-dc"]');
    if (raw) raw.remove(); else host.remove();
    tpl.content.querySelectorAll('helmet').forEach((h) => {
      Array.from(h.childNodes).forEach((c) => document.head.appendChild(document.importNode(c, true)));
    });

    let props = {};
    try { props = JSON.parse((src && src.getAttribute('data-props')) || '{}'); } catch (e) { /* 用空 props */ }
    const Component = src ? new Function('DCLogic', src.textContent + '\n;return Component;')(DCLogic) : DCLogic;

    const size = props.$preview || {};
    const stage = document.createElement('div');
    stage.className = 'dc-stage';
    const frame = document.createElement('div');
    frame.className = 'dc-frame';
    const root = document.createElement('div');
    root.style.transformOrigin = '0 0';
    frame.appendChild(root);
    stage.appendChild(frame);
    document.body.appendChild(stage);

    const comp = new Component(props);
    let queued = false;
    const render = () => {
      queued = false;
      patchKids(root, build(tpl.content.childNodes, comp.renderVals() || {}, []));
      fit();
    };
    comp.__schedule = () => { if (!queued) { queued = true; Promise.resolve().then(render); } };

    // 窗口比设计稿窄时整体缩小，不放大
    function fit() {
      const w = size.width || root.scrollWidth;
      const k = Math.min(1, (document.documentElement.clientWidth - 48) / w);
      root.style.transform = k < 1 ? 'scale(' + k + ')' : '';
      root.style.width = w + 'px';
      frame.style.width = w * k + 'px';
      frame.style.height = root.scrollHeight * k + 'px';
    }
    addEventListener('resize', fit);
    addEventListener('storage', render); // 别的页签改了配色，这边跟着变
    render();
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
})();
