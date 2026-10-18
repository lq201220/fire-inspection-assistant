import { request, setToken, setAttachmentAccess } from "./api.js";
export const ROLE_NAMES = { INSPECTOR: "监督检查员", REVIEWER: "审核人员", RECHECKER: "复查人员", ADMIN: "系统管理员" };
export async function login(username, password) { const result = await request("/auth/login", { method: "POST", body: { username, password } }); setToken(result.token); setAttachmentAccess(result.user); return result.user; }
export async function logout() { await request("/auth/logout", { method: "POST" }); setToken(null); }
export async function me() { return request("/auth/me"); }
