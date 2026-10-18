export const esc = (value = "") => String(value ?? "").replace(/[&<>'"]/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;","'":"&#39;",'"':"&quot;"}[c]));
export const icon = name => `<i data-lucide="${name}"></i>`;
export function pill(value) { const s=String(value||""); const color=s==='无异常'?"low":/异常|不合格|高/.test(s)?"high":s.includes("中")?"medium":/完成|合格|排除|正常|低/.test(s)?"low":/待|退回/.test(s)?"pending":"neutral";return `<span class="status status-${color}">${esc(s)}</span>`; }
export const button = (label, action, data = "", primary = false) => `<button type="button" class="${primary?"primary-btn":"ghost-btn"}" data-action="${action}" ${data}>${label}</button>`;
export const panel = (title, content, wide = false) => `<section class="panel ${wide?"wide":""}"><div class="panel-title"><h3>${title}</h3></div>${content}</section>`;
export const empty = text => `<div class="empty-state">${icon("inbox")}<p>${esc(text)}</p></div>`;
export const date = value => value ? (Number.isNaN(Date.parse(value)) ? esc(value) : new Date(value).toLocaleString("zh-CN",{hour12:false})) : "尚未记录";
export const options = (values, selected) => values.map(v => {const [key,label]=Array.isArray(v)?v:[v,v];return `<option value="${esc(key)}" ${key===selected?"selected":""}>${esc(label)}</option>`;}).join("");
export function field(name, label, value = "", type = "text", extra = "") { return `<div class="field ${type==="textarea"?"full":""}"><label>${esc(label)}${type==="textarea"?`<textarea name="${name}" ${extra}>${esc(value)}</textarea>`:`<input name="${name}" type="${type}" value="${esc(value)}" ${extra}>`}</label></div>`; }
export const select = (name,label,values,value) => `<div class="field"><label>${esc(label)}<select name="${name}">${options(values,value)}</select></label></div>`;
export const details = values => `<div class="detail-list">${values.map(([label,value])=>`<div class="detail-item"><span>${esc(label)}</span><strong>${value}</strong></div>`).join("")}</div>`;
export const localDate = value => value?new Date(new Date(value).getTime()-new Date(value).getTimezoneOffset()*60000).toISOString().slice(0,16):"";
