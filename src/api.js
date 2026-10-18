import {cachedImage,storeImage,removeCachedImages,cachedImageBytes,imageCacheEpoch} from './image-cache.js';

const BASE = (import.meta.env.VITE_API_BASE_URL || "http://localhost:8081/api").replace(/\/$/, "");
const TOKEN_KEY = "fire-assistant-session";
let inFlight = 0;
export function getToken() { return sessionStorage.getItem(TOKEN_KEY); }
export function setToken(token) { if (token) sessionStorage.setItem(TOKEN_KEY, token); else { sessionStorage.removeItem(TOKEN_KEY);setAttachmentAccess(null); } }
export class ApiError extends Error { constructor(message, status) { super(message); this.status = status; } }
export async function request(path, { method = "GET", body, blob = false, signal, silent = false } = {}) {
  const headers = {};
  const requestToken = getToken();
  if (requestToken) headers.Authorization = `Bearer ${requestToken}`;
  if (body && !(body instanceof FormData)) headers["Content-Type"] = "application/json";
  if (!silent) {
    inFlight++;
    window.dispatchEvent(new CustomEvent("request-state", { detail: { pending: inFlight } }));
  }
  try {
    const response = await fetch(`${BASE}${path}`, { method, headers, signal, body: body instanceof FormData ? body : body ? JSON.stringify(body) : undefined });
    if (!response.ok) {
      const result = await response.json().catch(() => ({}));
      if (response.status === 401 && path !== "/auth/login" && requestToken === getToken()) { setToken(null); window.dispatchEvent(new Event("auth-expired")); }
      throw new ApiError(result.message || `请求失败（${response.status}）`, response.status);
    }
    const result = blob ? await response.blob() : (await response.json()).data;
    if (!silent) window.dispatchEvent(new CustomEvent("request-state", { detail: { pending: inFlight - 1, successAt: new Date().toISOString() } }));
    return result;
  } catch (error) {
    if (!silent) window.dispatchEvent(new CustomEvent("request-state", { detail: { pending: inFlight - 1, failed: true } }));
    if (error instanceof ApiError) throw error;
    throw new ApiError("网络连接失败，请重试", 0);
  } finally { if (!silent) inFlight--; }
}
export async function upload(file, taskId, purpose = "issue") {
  const body = new FormData(); body.append("file", file); body.append("taskId", taskId); body.append("purpose", purpose);
  return request(purpose === "issue" ? "/files/photos" : "/files/attachments", { method: "POST", body });
}
const urls = new Map(), downloads = new Map();
let cacheScope = '', allowedTasks = new Set(), accessLoaded = false, generation = 0;
const changed = () => window.dispatchEvent(new Event('image-cache-change'));
const channel = typeof BroadcastChannel === 'function' ? new BroadcastChannel('fire-assistant-image-cache') : null;
channel?.addEventListener('message', ({data}) => {
  if (data?.scope === cacheScope) { clearAttachmentCache();changed(); }
});

export function setAttachmentAccess(user, tasks = null) {
  const scope = user ? JSON.stringify([new URL(BASE,location.href).href,user.orgId,user.id,user.role]) : '';
  if (scope !== cacheScope) {
    const previous = cacheScope;
    clearAttachmentCache();cacheScope = scope;allowedTasks = new Set();accessLoaded = false;
    if (previous) { channel?.postMessage({scope:previous});void removeCachedImages(previous); }
  }
  if (tasks) {
    const next = new Set(tasks.map(task => task.id));
    if (!accessLoaded || next.size !== allowedTasks.size || [...next].some(id => !allowedTasks.has(id))) {
      allowedTasks = next;accessLoaded = true;
      for (const [id,entry] of urls) if (!next.has(entry.taskId)) {URL.revokeObjectURL(entry.url);urls.delete(id);}
      if (scope) void removeCachedImages(scope,next).then(changed);
    }
  }
}

function trimMemory(incomingSize) {
  let bytes = incomingSize + [...urls.values()].reduce((total,entry) => total + entry.size,0);
  if (bytes <= 128 * 1024 ** 2 && urls.size < 100) return;
  const visible = new Set([...document.querySelectorAll('img[src],iframe[src],a[href]')].map(node => node.src || node.href));
  for (const [id,entry] of urls) {
    if (bytes <= 128 * 1024 ** 2 && urls.size < 100) break;
    if (visible.has(entry.url)) continue;
    URL.revokeObjectURL(entry.url);urls.delete(id);bytes -= entry.size;
  }
}

export async function attachmentURL(id, taskId) {
  if (!cacheScope || !getToken() || !allowedTasks.has(taskId)) throw new ApiError('无权查看此附件',403);
  const cached = urls.get(id);
  if (cached?.taskId === taskId) {
    urls.delete(id);urls.set(id,cached);
    void cachedImage(cacheScope,id,taskId);
    return cached.url;
  }
  if (downloads.has(id)) return downloads.get(id);
  const scope = cacheScope, token = getToken(), revision = generation;
  const valid = () => scope === cacheScope && token === getToken() && revision === generation && allowedTasks.has(taskId);
  const download = (async () => {
    const epoch = await imageCacheEpoch(scope);
    let blob = await cachedImage(scope,id,taskId), persisted = !!blob;
    if (!valid()) throw new ApiError('附件读取已取消',0);
    if (!blob) {
      blob = await request(`/files/photos/${encodeURIComponent(id)}`,{blob:true});
      if (!valid()) throw new ApiError('附件读取已取消',0);
      persisted = await storeImage(scope,id,taskId,blob,valid,epoch);
    }
    if (!valid()) throw new ApiError('附件读取已取消',0);
    const url = URL.createObjectURL(blob);
    trimMemory(blob.size);urls.set(id,{url,taskId,size:blob.size,image:blob.type.startsWith('image/'),persisted});
    changed();return url;
  })();
  downloads.set(id,download);
  try { return await download; }
  finally { if (downloads.get(id) === download) downloads.delete(id); }
}

export function clearAttachmentCache() {
  generation++;downloads.clear();
  for (const entry of urls.values()) URL.revokeObjectURL(entry.url);
  urls.clear();
}

export async function attachmentCacheBytes() {
  const scope = cacheScope;
  const bytes = await cachedImageBytes(scope);
  return scope === cacheScope ? bytes + [...urls.values()].filter(entry => entry.image && !entry.persisted).reduce((sum,entry) => sum + entry.size,0) : 0;
}

export async function clearImageCache() {
  const scope = cacheScope;
  clearAttachmentCache();channel?.postMessage({scope});
  const success = await removeCachedImages(scope);
  changed();
  if (success === false) throw new ApiError('缓存暂时无法清除，请重试',0);
}
