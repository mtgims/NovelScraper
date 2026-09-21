// The JavaScript half of the extension host. Runs inside QuickJS before any
// plugin and gives plugins what LNReader's app gives them: the same `require`
// modules (cheerio, htmlparser2, dayjs, urlencode, @libs/*) and the web APIs they
// use (fetch/Response/Headers/FormData, URL, TextEncoder/TextDecoder, atob/btoa,
// timers, console). Mirrors lnreader/src/plugins/pluginManager.ts and helpers/.
//
// The Kotlin side (PluginHost.kt) provides `globalThis.__native`, whose functions
// all take and return strings (JSON where structured): see NATIVE below.

import { load } from 'cheerio';
import { Parser } from 'htmlparser2';
import dayjs from 'dayjs';
import { gcm } from '@noble/ciphers/aes.js';
import { utf8ToBytes, bytesToUtf8 } from '@noble/ciphers/utils.js';
import { URL, URLSearchParams } from 'whatwg-url-without-unicode';

const g = globalThis;
// NATIVE: log(level, msg), fetch(requestJson) -> Promise<responseJson>,
// sleep(ms) -> Promise, decode(base64, label) -> string, urlencode(json) -> string,
// storageGet(pluginId, key) -> string|"", storageSet(pluginId, key, value),
// body(bodyId) -> base64 of a response's bytes,
// storageDelete(pluginId, key), storageKeys(pluginId) -> JSON array,
// userAgent() -> the User-Agent this device sends.
const N = g.__native;

// --- URL --------------------------------------------------------------------------

// The same polyfill LNReader's app installs (react-native-url-polyfill).
g.URL = URL;
g.URLSearchParams = URLSearchParams;

// --- console, timers ---------------------------------------------------------

const fmt = args => args.map(a => (typeof a === 'string' ? a : safeJson(a))).join(' ');
function safeJson(v) {
  try { return v instanceof Error ? `${v.name}: ${v.message}` : JSON.stringify(v); } catch { return String(v); }
}
g.console = {
  log: (...a) => N.log('info', fmt(a)),
  info: (...a) => N.log('info', fmt(a)),
  debug: (...a) => N.log('debug', fmt(a)),
  warn: (...a) => N.log('warn', fmt(a)),
  error: (...a) => N.log('error', fmt(a)),
};

let timerSeq = 0;
const liveTimers = new Set();
g.setTimeout = (fn, ms, ...args) => {
  const id = ++timerSeq;
  liveTimers.add(id);
  N.sleep(String(Math.max(0, Number(ms) || 0))).then(() => {
    if (liveTimers.delete(id) && typeof fn === 'function') fn(...args);
  });
  return id;
};
g.clearTimeout = id => { liveTimers.delete(id); };

// --- base64, text encoding ------------------------------------------------------

const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
function bytesToB64(bytes) {
  let out = '';
  for (let i = 0; i < bytes.length; i += 3) {
    const n = (bytes[i] << 16) | ((bytes[i + 1] ?? 0) << 8) | (bytes[i + 2] ?? 0);
    out += B64[(n >> 18) & 63] + B64[(n >> 12) & 63] +
      (i + 1 < bytes.length ? B64[(n >> 6) & 63] : '=') +
      (i + 2 < bytes.length ? B64[n & 63] : '=');
  }
  return out;
}
function b64ToBytes(s) {
  const clean = String(s).replace(/[^A-Za-z0-9+/]/g, '');
  const out = new Uint8Array(Math.floor((clean.length * 3) / 4));
  let o = 0;
  for (let i = 0; i < clean.length; i += 4) {
    const n = (B64.indexOf(clean[i]) << 18) | (B64.indexOf(clean[i + 1]) << 12) |
      ((B64.indexOf(clean[i + 2]) & 63) << 6) | (B64.indexOf(clean[i + 3]) & 63);
    out[o++] = (n >> 16) & 255;
    if (i + 2 < clean.length) out[o++] = (n >> 8) & 255;
    if (i + 3 < clean.length) out[o++] = n & 255;
  }
  return out.subarray(0, o);
}
// atob/btoa work on "binary strings" (one char per byte), as in browsers.
g.btoa = s => bytesToB64(Uint8Array.from(String(s), c => {
  const code = c.charCodeAt(0);
  if (code > 255) throw new Error('btoa: character out of range');
  return code;
}));
g.atob = s => Array.from(b64ToBytes(s), b => String.fromCharCode(b)).join('');

function toBytes(input) {
  if (input == null) return new Uint8Array(0);
  if (input instanceof Uint8Array) return input;
  if (input instanceof ArrayBuffer) return new Uint8Array(input);
  if (ArrayBuffer.isView(input)) return new Uint8Array(input.buffer, input.byteOffset, input.byteLength);
  throw new TypeError('expected bytes');
}
g.TextEncoder = class TextEncoder {
  get encoding() { return 'utf-8'; }
  encode(s = '') { return utf8ToBytes(String(s)); }
};
g.TextDecoder = class TextDecoder {
  constructor(label = 'utf-8') { this.encoding = String(label).toLowerCase(); }
  decode(input) {
    const bytes = toBytes(input);
    if (this.encoding === 'utf-8' || this.encoding === 'utf8') return bytesToUtf8(bytes);
    return N.decode(bytesToB64(bytes), this.encoding);
  }
};

// --- fetch -------------------------------------------------------------------------

class Headers {
  constructor(init) {
    this._m = new Map();
    if (init instanceof Headers) init.forEach((v, k) => this.append(k, v));
    else if (Array.isArray(init)) init.forEach(([k, v]) => this.append(k, v));
    else if (init) Object.keys(init).forEach(k => this.append(k, init[k]));
  }
  append(k, v) {
    const key = String(k).toLowerCase();
    const cur = this._m.get(key);
    this._m.set(key, cur == null ? String(v) : `${cur}, ${v}`);
  }
  set(k, v) { this._m.set(String(k).toLowerCase(), String(v)); }
  get(k) { const v = this._m.get(String(k).toLowerCase()); return v == null ? null : v; }
  has(k) { return this._m.has(String(k).toLowerCase()); }
  delete(k) { this._m.delete(String(k).toLowerCase()); }
  forEach(fn) { this._m.forEach((v, k) => fn(v, k, this)); }
  entries() { return this._m.entries(); }
  keys() { return this._m.keys(); }
  values() { return this._m.values(); }
  [Symbol.iterator]() { return this._m.entries(); }
  toObject() { const o = {}; this._m.forEach((v, k) => { o[k] = v; }); return o; }
}
g.Headers = Headers;

class FormData {
  constructor() { this._e = []; }
  append(k, v) { this._e.push([String(k), String(v)]); }
  set(k, v) { this.delete(k); this.append(k, v); }
  get(k) { const e = this._e.find(x => x[0] === String(k)); return e ? e[1] : null; }
  getAll(k) { return this._e.filter(x => x[0] === String(k)).map(x => x[1]); }
  has(k) { return this._e.some(x => x[0] === String(k)); }
  delete(k) { this._e = this._e.filter(x => x[0] !== String(k)); }
  entries() { return this._e[Symbol.iterator](); }
  forEach(fn) { this._e.forEach(([k, v]) => fn(v, k, this)); }
  [Symbol.iterator]() { return this._e[Symbol.iterator](); }
}
g.FormData = FormData;

class Response {
  constructor(r) {
    this.status = r.status;
    this.statusText = r.statusText || '';
    this.ok = r.status >= 200 && r.status < 300;
    this.url = r.url;
    this.redirected = !!r.redirected;
    this.headers = new Headers(r.headers || {});
    this._text = r.text;
    this._bodyId = r.bodyId;
    this.bodyUsed = false;
  }
  _use() { this.bodyUsed = true; }
  text() { this._use(); return Promise.resolve(this._text); }
  json() { this._use(); return Promise.resolve().then(() => JSON.parse(this._text)); }
  // The raw bytes stay in the host until asked for.
  arrayBuffer() { this._use(); return Promise.resolve(b64ToBytes(N.body(this._bodyId)).slice().buffer); }
  blob() { return this.arrayBuffer().then(b => ({ size: b.byteLength, arrayBuffer: () => Promise.resolve(b), text: () => Promise.resolve(this._text) })); }
  clone() { return new Response({ status: this.status, statusText: this.statusText, url: this.url, headers: this.headers.toObject(), text: this._text, bodyId: this._bodyId }); }
}
g.Response = Response;

function encodeBody(body) {
  if (body == null) return null;
  if (typeof body === 'string') return { kind: 'text', value: body };
  if (body instanceof URLSearchParams) return { kind: 'form', value: body.toString() };
  if (body instanceof FormData) return { kind: 'multipart', value: body._e };
  if (body instanceof ArrayBuffer || ArrayBuffer.isView(body)) return { kind: 'bytes', value: bytesToB64(toBytes(body)) };
  return { kind: 'text', value: String(body) };
}

// `encoding` (not part of fetch) is what fetchText passes to decode the body with.
async function rawFetch(input, init = {}, encoding) {
  const url = typeof input === 'string' ? input : (input instanceof URL ? input.href : input.url);
  const headers = init.headers instanceof Headers ? init.headers.toObject() : (init.headers || {});
  const request = {
    url: String(url),
    method: (init.method || 'GET').toUpperCase(),
    headers,
    body: encodeBody(init.body),
    encoding: encoding || null,
  };
  const res = JSON.parse(await N.fetch(JSON.stringify(request)));
  if (res.error) throw new TypeError(`Network request failed: ${res.error}`);
  return new Response(res);
}
g.fetch = (input, init) => rawFetch(input, init);

// lnreader/src/plugins/helpers/fetch.ts
const makeInit = init => {
  const defaultHeaders = {
    'Connection': 'keep-alive',
    'Accept': '*/*',
    'Accept-Language': '*',
    'Sec-Fetch-Mode': 'cors',
    'Accept-Encoding': 'gzip, deflate',
    'Cache-Control': 'max-age=0',
    'User-Agent': N.userAgent(),
  };
  if (init && init.headers) {
    if (init.headers instanceof Headers) {
      if (!init.headers.get('User-Agent')) init.headers.set('User-Agent', defaultHeaders['User-Agent']);
    } else {
      init.headers = { ...defaultHeaders, ...init.headers };
    }
  } else {
    init = { ...init, headers: defaultHeaders };
  }
  return init;
};
const fetchApi = async (url, init) => rawFetch(url, makeInit(init));
const fetchText = async (url, init, encoding) => {
  try {
    const res = await rawFetch(url, makeInit(init), encoding || 'utf-8');
    if (!res.ok) throw new Error();
    return await res.text();
  } catch {
    return '';
  }
};
const fetchProto = async () => { throw new Error('fetchProto is not supported yet'); };

// --- @libs modules --------------------------------------------------------------

const NovelStatus = {
  Unknown: 'Unknown',
  Ongoing: 'Ongoing',
  Completed: 'Completed',
  Licensed: 'Licensed',
  PublishingFinished: 'Publishing Finished',
  Cancelled: 'Cancelled',
  OnHiatus: 'On Hiatus',
  STUB: 'STUB',
  Inactive: 'Inactive',
};
const FilterTypes = {
  TextInput: 'Text',
  Picker: 'Picker',
  CheckboxGroup: 'Checkbox',
  Switch: 'Switch',
  ExcludableCheckboxGroup: 'XCheckbox',
};
const defaultCover =
  'https://github.com/lnreader/lnreader-plugins/blob/master/public/static/coverNotAvailable.webp?raw=true';

const isUrlAbsolute = url => {
  if (url) {
    if (url.indexOf('//') === 0) return true;
    if (url.indexOf('://') === -1) return false;
    if (url.indexOf('.') === -1) return false;
    if (url.indexOf('/') === -1) return false;
    if (url.indexOf(':') > url.indexOf('/')) return false;
    if (url.indexOf('://') < url.indexOf('.')) return true;
  }
  return false;
};

// urlencode: UTF-8 in JS, other charsets (e.g. gbk) by the host.
const isUtf8 = cs => !cs || /^utf-?8$/i.test(cs);
const urlencode = {
  encode: (s, charset) => (isUtf8(charset) ? encodeURIComponent(s) : N.urlencode(JSON.stringify({ op: 'encode', s: String(s), charset }))),
  decode: (s, charset) => (isUtf8(charset) ? decodeURIComponent(s) : N.urlencode(JSON.stringify({ op: 'decode', s: String(s), charset }))),
};

// lnreader/src/plugins/helpers/storage.ts, backed by the host's per-plugin store.
class Storage {
  constructor(pluginId) { this._id = pluginId; }
  set(key, value, expires) {
    const item = { created: new Date(), value, expires: expires instanceof Date ? expires.getTime() : expires };
    N.storageSet(this._id, String(key), JSON.stringify(item));
  }
  get(key, raw) {
    const stored = N.storageGet(this._id, String(key));
    if (!stored) return undefined;
    const item = JSON.parse(stored);
    if (item.expires) {
      if (Date.now() > item.expires) { this.delete(key); return undefined; }
      if (raw) item.expires = new Date(item.expires).getTime();
    }
    return raw ? item : item.value;
  }
  delete(key) { N.storageDelete(this._id, String(key)); }
  clearAll() { this.getAllKeys().forEach(k => this.delete(k)); }
  getAllKeys() { return JSON.parse(N.storageKeys(this._id)); }
}
// The webview's localStorage/sessionStorage (only filled on Android's WebView
// login flow); empty here.
class LocalStorage { constructor(id) { this._id = id; } get() { return undefined; } }
class SessionStorage { constructor(id) { this._id = id; } get() { return undefined; } }

const packages = {
  'htmlparser2': { Parser },
  'cheerio': { load },
  'dayjs': dayjs,
  'urlencode': urlencode,
  '@libs/novelStatus': { NovelStatus },
  '@libs/fetch': { fetchApi, fetchText, fetchProto },
  '@libs/isAbsoluteUrl': { isUrlAbsolute },
  '@libs/filterInputs': { FilterTypes },
  '@libs/defaultCover': { defaultCover },
  '@libs/aes': { gcm },
  '@libs/utils': { utf8ToBytes, bytesToUtf8 },
  // lnreader-plugins' own src/types/constants.ts, which a few plugins import
  // directly (e.g. novelfire for its fallback cover).
  '@/types/constants': {
    NovelStatus,
    defaultCover: 'https://github.com/LNReader/lnreader-plugins/blob/main/icons/src/coverNotAvailable.jpg?raw=true',
  },
};

// --- plugins -----------------------------------------------------------------------

const plugins = {};

// Same wrapper as LNReader's initPlugin: CommonJS-style code whose default export
// is the plugin instance.
g.__loadPlugin = (pluginId, rawCode) => {
  const _require = name => {
    if (name === '@libs/storage') {
      return { storage: new Storage(pluginId), localStorage: new LocalStorage(pluginId), sessionStorage: new SessionStorage(pluginId) };
    }
    const mod = packages[name];
    if (mod === undefined) throw new Error(`Module not found: ${name}`);
    return mod;
  };
  // eslint-disable-next-line no-new-func
  const plugin = Function('require', 'module', `const exports = module.exports = {};\n${rawCode};\nreturn exports.default`)(_require, {});
  if (!plugin) throw new Error('The plugin has no default export');
  if (!plugin.imageRequestInit) plugin.imageRequestInit = { headers: {} };
  if (!plugin.imageRequestInit.headers) plugin.imageRequestInit.headers = {};
  if (!Object.keys(plugin.imageRequestInit.headers).some(h => h.toLowerCase() === 'user-agent')) {
    plugin.imageRequestInit.headers['User-Agent'] = N.userAgent();
  }
  plugins[pluginId] = plugin;
  return JSON.stringify({
    id: plugin.id, name: plugin.name, version: plugin.version, site: plugin.site,
    lang: plugin.lang, icon: plugin.icon, hasFilters: !!plugin.filters,
    hasParsePage: typeof plugin.parsePage === 'function',
    hasResolveUrl: typeof plugin.resolveUrl === 'function',
    imageRequestInit: plugin.imageRequestInit,
  });
};

// Calls from the host. The engine runs a call's jobs (including async host
// functions like fetch) to completion but hands back the promise rather than its
// value, so a call parks its outcome here and the host collects it with __take.
let settled = null;
g.__call = fn => {
  settled = null;
  Promise.resolve().then(fn).then(
    v => { settled = { ok: true, v: typeof v === 'string' ? v : JSON.stringify(v ?? null) }; },
    e => { settled = { ok: false, v: e && e.stack ? `${e}\n${e.stack}` : String(e) }; },
  );
};
g.__take = () => {
  const r = settled;
  settled = null;
  return JSON.stringify(r || { ok: false, v: 'the call did not finish' });
};

const need = id => { const p = plugins[id]; if (!p) throw new Error(`Plugin not loaded: ${id}`); return p; };

// Default filter values: the plugin's filter definitions as they come.
g.__popular = async (id, page, latest) => {
  const p = need(id);
  return JSON.stringify(await p.popularNovels(page, { showLatestNovels: !!latest, filters: p.filters }));
};
g.__search = async (id, term, page) => JSON.stringify(await need(id).searchNovels(term, page));
g.__novel = async (id, path) => JSON.stringify(await need(id).parseNovel(path));
g.__page = async (id, path, page) => JSON.stringify(await need(id).parsePage(path, String(page)));
g.__chapter = async (id, path) => {
  const html = await need(id).parseChapter(path);
  return typeof html === 'string' ? html : String(html ?? '');
};
g.__resolveUrl = (id, path, isNovel) => {
  const p = need(id);
  return typeof p.resolveUrl === 'function' ? String(p.resolveUrl(path, !!isNovel)) : '';
};
