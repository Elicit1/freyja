"use strict";
const $ = (id) => document.getElementById(id);
const pages = {
  overview: "概览",
  keys: "API Keys",
  mcp: "MCP 接入",
  security: "安全设置",
};
let currentPage = "overview", signedIn = false, pollTimer, toastTimer, endpoint = location.origin + "/mcp";

function node(tag, className, text) {
  const el = document.createElement(tag);
  if (className) el.className = className;
  if (text !== undefined) el.textContent = text;
  return el;
}
function toast(text) {
  $("toast").textContent = text;
  $("toast").hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { $("toast").hidden = true; }, 4200);
}
async function api(path, options = {}) {
  const response = await fetch(path, {
    credentials: "same-origin", ...options,
    headers: { "X-SIF-Console": "1", ...(options.body ? { "Content-Type": "application/json" } : {}), ...options.headers },
  });
  const body = await response.json();
  if (!response.ok) {
    if (response.status === 401 && path !== "/auth/login") showLogin();
    const codes = { invalid_password: "密码不正确，请重试。", login_rate_limited: "尝试次数过多，请在五分钟后重试。", login_required: "登录已过期，请重新登录。", invalid_parameters: "请检查输入的内容和长度。" };
    throw new Error(codes[body.error?.code] || body.error?.message || "请求失败，请稍后重试。");
  }
  return body;
}
function showLogin(message = "") {
  signedIn = false;
  clearTimeout(pollTimer);
  $("console-view").hidden = true;
  $("login-view").hidden = false;
  $("login-error").textContent = message;
  $("login-password").value = "";
  $("new-key").value = "";
  $("password-form").reset();
  if ($("key-dialog").open) $("key-dialog").close();
  if ($("confirm-dialog").open) $("confirm-dialog").close();
}
async function showConsole() {
  signedIn = true;
  $("login-view").hidden = true;
  $("console-view").hidden = false;
  const page = location.hash.slice(1);
  navigate(pages[page] ? page : "overview");
  await refresh();
}
function navigate(page) {
  if (!pages[page]) return;
  currentPage = page;
  location.hash = page;
  for (const id of Object.keys(pages)) $(id + "-page").hidden = id !== page;
  document.querySelectorAll(".nav-item").forEach((el) => el.classList.toggle("selected", el.dataset.page === page));
  $("page-title").textContent = pages[page];
  $("breadcrumb").textContent = pages[page];
  if (page === "keys" && signedIn) loadKeys().catch((error) => toast(error.message));
}
function date(value) {
  return value ? new Date(value * 1000).toLocaleString("zh-CN", { hour12: false }) : "尚未使用";
}
const states = { queued: "等待中", preparing: "准备中", running: "执行中", succeeded: "已完成", failed: "失败", unknown: "待确认" };
function statusBadge(status) {
  return node("span", "badge " + (status === "succeeded" || status === "running" ? "status" : "subtle"), states[status] || status);
}
function jobTags(job) {
  const tags = node("span", "job-tags");
  tags.append(node("code", "", job.id.slice(0, 8)), node("span", "tier", job.request.mode), node("span", "tier", job.request.tier));
  return tags;
}
function renderQueue(queue) {
  $("active-count").textContent = queue.active;
  $("waiting-count").textContent = queue.waiting;
  $("queue-hint").hidden = !queue.blocked;
  $("queue-hint").textContent = queue.blocked ? "上一任务状态待确认，队列已暂停" : "";
  const list = $("queue-list");
  list.replaceChildren();
  if (!queue.jobs.length) {
    const empty = node("p", "empty");
    empty.textContent = "暂无排队任务";
    list.append(empty);
    return;
  }
  for (const job of queue.jobs) {
    const item = node("div", "queue-item");
    const top = node("div", "job-top");
    top.append(jobTags(job), statusBadge(job.status));
    item.append(top, node("p", "job-prompt", job.request.prompt));
    if (["running", "preparing"].includes(job.status)) item.append(node("div", "active-line"));
    const foot = node("div", "job-foot");
    foot.append(node("span", job.status === "unknown" ? "unknown-label" : "", job.status === "unknown" ? "暂未确认后端任务是否结束，后续任务继续等待。" : job.queue_position ? "等待顺位 " + job.queue_position : "正在等待后端生成结果"));
    const size = job.request.width && job.request.height
      ? job.request.width + " × " + job.request.height
      : job.request.megapixels + " MP";
    foot.append(node("span", "", size));
    item.append(foot);
    if (job.status === "unknown") {
      const resolve = node("button", "text-button unknown-label", "已检查后端，处理此任务 →");
      resolve.addEventListener("click", async () => {
        if (await confirmAction("确认后端任务已结束", "请先在 ComfyUI 中确认此任务已经结束。\n确认后会尝试保存已有结果；没有结果的任务将标记失败，然后继续队列。")) {
          try {
            await api("/admin/jobs/" + job.id + "/resolve", { method: "POST", body: JSON.stringify({ confirmed_backend_finished: true }) });
            toast("任务状态已更新");
            await refresh();
          } catch (error) { toast(error.message); }
        }
      });
      item.append(resolve);
    }
    list.append(item);
  }
}
function renderHistory(jobs) {
  const body = $("history-body");
  body.replaceChildren();
  if (!jobs.length) {
    const cell = node("td", "empty", "暂无任务记录");
    cell.colSpan = 5;
    const row = node("tr"); row.append(cell); body.append(row);
  }
  for (const job of jobs) {
    const row = node("tr"), identity = node("td"), tags = node("td"), status = node("td");
    identity.append(node("code", "", job.id.slice(0, 8)));
    tags.append(node("span", "tier", job.request.mode), document.createTextNode(" "), node("span", "tier", job.request.tier));
    status.append(statusBadge(job.status));
    const prompt = node("td", "prompt-cell", job.request.prompt);
    prompt.title = job.request.prompt;
    row.append(identity, tags, prompt, status, node("td", "", date(job.created)));
    body.append(row);
  }
}
async function loadKeys() {
  const { data } = await api("/admin/keys");
  const body = $("keys-body");
  body.replaceChildren();
  if (!data.length) {
    const cell = node("td", "empty", "暂无 API Key");
    cell.colSpan = 6;
    const row = node("tr"); row.append(cell); body.append(row);
  }
  for (const key of data) {
    const row = node("tr"), prefix = node("td"), state = node("td"), actions = node("td");
    prefix.append(node("code", "", key.prefix));
    state.append(node("span", "badge " + (key.revoked ? "subtle" : "status"), key.revoked ? "已撤销" : "有效"));
    if (!key.revoked) {
      const revoke = node("button", "text-button danger-text", "撤销");
      revoke.addEventListener("click", async () => {
        if (await confirmAction("撤销 API Key", "撤销“" + key.name + "”后，该客户端的新请求将被拒绝。已经排队的任务会继续执行。")) {
          try { await api("/admin/keys/" + key.id, { method: "DELETE" }); await loadKeys(); toast("API Key 已撤销"); }
          catch (error) { toast(error.message); }
        }
      });
      actions.append(revoke);
    } else actions.textContent = "—";
    row.append(node("td", "", key.name), prefix, node("td", "", date(key.created)), node("td", "", date(key.last_used)), state, actions);
    body.append(row);
  }
  $("keys-summary").textContent = "共 " + data.length + " 个 Key · " + data.filter((key) => !key.revoked).length + " 个有效";
}
function configText() {
  return JSON.stringify({ mcpServers: { sif: { url: endpoint, headers: { Authorization: "Bearer YOUR_API_KEY" } } } }, null, 2);
}
async function refresh() {
  clearTimeout(pollTimer);
  if (!signedIn) return;
  try {
    const data = await api("/admin/overview");
    endpoint = data.mcp_url;
    document.querySelectorAll(".mcp-url").forEach((el) => { el.textContent = endpoint; });
    $("mcp-config").textContent = configText();
    $("key-count").textContent = data.active_keys;
    $("password-notice").hidden = !data.account.must_change_password;
    const status = $("service-status");
    status.replaceChildren(node("i", "dot"), document.createTextNode(data.worker_alive ? "服务在线" : "工作线程异常"));
    status.classList.toggle("disconnected", !data.worker_alive);
    renderQueue(data.queue);
    renderHistory(data.recent_jobs);
    $("last-updated").textContent = "最近同步 " + new Date().toLocaleTimeString("zh-CN", { hour12: false });
  } catch (error) {
    if (signedIn) { $("service-status").textContent = "连接中断"; $("service-status").classList.add("disconnected"); $("last-updated").textContent = "同步失败，正在重试"; }
  } finally {
    if (signedIn) pollTimer = setTimeout(refresh, 5000);
  }
}
async function copy(text) {
  try { await navigator.clipboard.writeText(text); toast("已复制"); }
  catch { toast("无法自动复制，请选中文本后手动复制。"); }
}
function confirmAction(title, description) {
  $("confirm-title").textContent = title;
  $("confirm-description").textContent = description;
  const dialog = $("confirm-dialog");
  dialog.showModal();
  return new Promise((resolve) => {
    const done = (value) => {
      $("confirm-accept").onclick = null; $("confirm-cancel").onclick = null; dialog.oncancel = null;
      dialog.close(); resolve(value);
    };
    $("confirm-accept").onclick = () => done(true);
    $("confirm-cancel").onclick = () => done(false);
    dialog.oncancel = (event) => { event.preventDefault(); done(false); };
  });
}
document.querySelectorAll("[data-page]").forEach((el) => el.addEventListener("click", () => navigate(el.dataset.page)));
window.addEventListener("hashchange", () => { const page = location.hash.slice(1); if (pages[page] && page !== currentPage) navigate(page); });
$("login-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const button = event.submitter; button.disabled = true; $("login-error").textContent = "";
  try {
    await api("/auth/login", { method: "POST", body: JSON.stringify({ password: $("login-password").value }) });
    $("login-password").value = "";
    await showConsole();
  } catch (error) { $("login-error").textContent = error.message; }
  finally { button.disabled = false; }
});
$("logout").addEventListener("click", async () => {
  try { await api("/auth/logout", { method: "POST" }); showLogin(); }
  catch (error) { toast(error.message); }
});
document.querySelectorAll(".create-key").forEach((el) => el.addEventListener("click", () => {
  $("key-form").reset(); $("key-error").textContent = ""; $("key-form").hidden = false; $("key-result").hidden = true;
  $("key-dialog").showModal(); $("key-name").focus();
}));
document.querySelectorAll(".close-dialog").forEach((el) => el.addEventListener("click", () => {
  $("new-key").value = "";
  $("key-dialog").close();
}));
$("key-dialog").addEventListener("cancel", () => { $("new-key").value = ""; });
$("key-dialog").addEventListener("close", () => { $("new-key").value = ""; });
$("key-form").addEventListener("submit", async (event) => {
  event.preventDefault(); const button = event.submitter; button.disabled = true;
  try {
    const result = await api("/admin/keys", { method: "POST", body: JSON.stringify({ name: $("key-name").value.trim() }) });
    $("new-key").value = result.key; $("key-form").hidden = true; $("key-result").hidden = false;
    $("copy-key").focus(); await loadKeys();
  } catch (error) { $("key-error").textContent = error.message; }
  finally { button.disabled = false; }
});
$("copy-key").addEventListener("click", () => copy($("new-key").value));
document.querySelectorAll(".copy-endpoint").forEach((el) => el.addEventListener("click", () => copy(endpoint)));
$("copy-config").addEventListener("click", () => copy(configText()));
$("password-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  if ($("new-password").value !== $("confirm-password").value) { toast("两次输入的新密码不一致。"); return; }
  const button = event.submitter; button.disabled = true;
  try {
    await api("/auth/password", { method: "POST", body: JSON.stringify({ current_password: $("current-password").value, new_password: $("new-password").value }) });
    showLogin("密码已更新，请使用新密码登录。");
  } catch (error) { toast(error.message); }
  finally { button.disabled = false; }
});
async function boot() {
  try { await api("/auth/me"); await showConsole(); }
  catch { showLogin(); }
}
boot();
