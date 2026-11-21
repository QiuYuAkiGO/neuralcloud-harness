'use strict';

// 对话记录和审计链都从服务端审计接口读取，页面不另存一份对话历史。
const state = { nodes: [], chain: null, auditChain: null, nodesTimer: null };

async function api(method, path, body) {
  const response = await fetch(path, {
    method,
    headers: body ? { 'Content-Type': 'application/json' } : {},
    body: body ? JSON.stringify(body) : undefined,
  });
  const data = await response.json();
  if (!response.ok) {
    const error = new Error(data.error);
    error.status = response.status;
    throw error;
  }
  return data;
}

function el(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(props)) {
    if (key === 'class') node.className = value;
    else if (key.startsWith('on')) node.addEventListener(key.slice(2), value);
    else node.setAttribute(key, value);
  }
  for (const child of children.flat()) {
    if (child !== null && child !== undefined) node.append(child instanceof Node ? child : String(child));
  }
  return node;
}

const time = (iso) => (iso ? new Date(iso).toLocaleString('zh-CN', { hour12: false }) : '');
const nodeName = (id) => state.nodes.find((node) => node.id === id)?.name ?? id;

// 各事件类型在对话和审计链里的一行摘要。
function summary(event) {
  const data = event.data;
  switch (event.type) {
    case 'operator_message': return data.text;
    case 'chat_worker_reply': return data.text;
    case 'router_route': return `流程：${data.route}`;
    case 'master_completion': return data.text || '（空正文）';
    case 'node_failed': return `失败：${data.detail}`;
    case 'probe_requested': return '请求探测';
    case 'probe_passed': return `探测通过，${data.latencyMs} ms`;
    default: return '';
  }
}

function latency(event) {
  return event.data.latencyMs === undefined ? '' : ` · ${event.data.latencyMs} ms`;
}

/* ---------- 主题 ---------- */

function renderThemeToggle() {
  const dark = document.documentElement.dataset.theme === 'dark';
  document.getElementById('theme-toggle').textContent = dark ? '亮色模式' : '暗色模式';
}

document.getElementById('theme-toggle').addEventListener('click', () => {
  const theme = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
  document.documentElement.dataset.theme = theme;
  localStorage.setItem('neuralcloud.theme', theme);
  renderThemeToggle();
});
renderThemeToggle();

/* ---------- 标签页 ---------- */

function showTab(name) {
  document.querySelectorAll('.tab').forEach((tab) => tab.classList.toggle('active', tab.dataset.tab === name));
  document.querySelectorAll('.panel').forEach((panel) => panel.classList.toggle('active', panel.id === `tab-${name}`));
  clearInterval(state.nodesTimer);
  if (name === 'audit') loadChains();
  if (name === 'nodes') {
    loadNodes();
    state.nodesTimer = setInterval(loadNodes, 5000);
  }
}

document.querySelectorAll('.tab').forEach((tab) => tab.addEventListener('click', () => showTab(tab.dataset.tab)));

/* ---------- 对话 ---------- */

function setChain(chain) {
  state.chain = chain;
  localStorage.setItem('neuralcloud.chain', chain);
  document.getElementById('chat-chain').textContent = chain;
}

async function loadChat() {
  const log = document.getElementById('chat-log');
  let events = [];
  try {
    events = (await api('GET', `/api/audit/chains/${state.chain}`)).events;
  } catch (error) {
    if (error.status !== 404) throw error;
  }
  log.replaceChildren(...events.map((event) => {
    const operator = event.type === 'operator_message';
    const meta = operator ? `操作者 → ${nodeName(event.node)}` : `${nodeName(event.node)}${latency(event)}`;
    const extra = event.type === 'master_completion' && event.data.finishReason
      ? ` · finish_reason=${event.data.finishReason}${event.data.reasoningFieldPresent ? ' · 含推理字段' : ''}` : '';
    return el('div', { class: `bubble ${operator ? 'operator' : event.type === 'node_failed' ? 'failed' : 'node'}` },
      el('div', { class: 'meta' }, `${meta}${extra} · ${time(event.recordedAt)}`), summary(event));
  }));
  if (!events.length) log.append(el('p', { class: 'muted' }, '新会话。选择节点后发送正文，节点的实际返回会写入审计链。'));
  log.scrollTop = log.scrollHeight;
}

async function sendChat(event) {
  event.preventDefault();
  const text = document.getElementById('chat-text');
  const button = document.getElementById('chat-send');
  const errorLine = document.getElementById('chat-error');
  const node = document.getElementById('chat-node').value;
  if (!text.value.trim()) return;
  button.disabled = true;
  errorLine.textContent = `等待 ${nodeName(node)} 返回…`;
  try {
    await api('POST', `/api/nodes/${node}/messages`, { chain: state.chain, text: text.value });
    text.value = '';
    errorLine.textContent = '';
  } catch (error) {
    // 502 表示节点调用失败，失败事件已写入审计链，下方对话里会显示。
    errorLine.textContent = error.status === 502 ? '' : error.message;
  } finally {
    button.disabled = false;
    await loadChat();
  }
}

document.getElementById('chat-form').addEventListener('submit', sendChat);
document.getElementById('chat-text').addEventListener('keydown', (event) => {
  if (event.key === 'Enter' && event.ctrlKey) document.getElementById('chat-form').requestSubmit();
});
document.getElementById('chat-new').addEventListener('click', () => {
  setChain(crypto.randomUUID());
  loadChat();
});

/* ---------- 审计链 ---------- */

async function loadChains() {
  const list = document.getElementById('audit-chains');
  const { chains } = await api('GET', '/api/audit/chains');
  list.replaceChildren(...chains.map((chain) => el('li', {
    class: chain.chain === state.auditChain ? 'active' : '',
    onclick: () => loadAudit(chain.chain),
  },
  el('div', { class: 'id' }, chain.chain),
  el('div', { class: 'muted' }, `${chain.nodes.map(nodeName).join('、')} · ${chain.events} 条`),
  el('div', { class: 'muted' }, `${time(chain.firstAt)} — ${time(chain.lastAt)}`))));
  if (!chains.length) list.append(el('li', {}, el('span', { class: 'muted' }, '暂无审计记录')));
}

// causedBy 只在同一 runId 内有效，所以用 runId:eventId 作为事件键建树。
function buildTree(events) {
  const key = (runId, id) => `${runId}:${id}`;
  const children = new Map();
  const known = new Set(events.map((event) => key(event.runId, event.eventId)));
  const roots = [];
  for (const event of events) {
    const parent = event.causedBy === null ? null : key(event.runId, event.causedBy);
    if (parent && known.has(parent)) {
      if (!children.has(parent)) children.set(parent, []);
      children.get(parent).push(event);
    } else {
      roots.push(event);
    }
  }
  const render = (event) => {
    const kids = children.get(key(event.runId, event.eventId)) ?? [];
    return el('li', { class: `event ${event.type}` },
      el('div', { class: 'event-head' },
        el('span', { class: 'badge' }, `#${event.eventId}`),
        el('span', { class: 'event-type' }, event.type),
        el('span', { class: 'muted' }, `${nodeName(event.node)} · ${time(event.recordedAt)}${latency(event)}`)),
      el('div', { class: 'event-summary' }, summary(event)),
      el('details', {}, el('summary', { class: 'muted' }, '原始事件'), el('pre', {}, JSON.stringify(event, null, 2))),
      kids.length ? el('ul', {}, kids.map(render)) : null);
  };
  return el('ul', { class: 'tree' }, roots.map(render));
}

async function loadAudit(chain) {
  state.auditChain = chain;
  document.querySelectorAll('#audit-chains li').forEach((item) => {
    item.classList.toggle('active', item.querySelector('.id')?.textContent === chain);
  });
  const { events } = await api('GET', `/api/audit/chains/${chain}`);
  const runs = new Set(events.map((event) => event.runId));
  document.getElementById('audit-events').replaceChildren(
    el('div', { class: 'toolbar' }, el('strong', {}, chain),
      el('span', { class: 'muted' }, `${events.length} 条事件 · ${runs.size} 个进程`)),
    buildTree(events));
}

document.getElementById('audit-refresh').addEventListener('click', async () => {
  await loadChains();
  if (state.auditChain) await loadAudit(state.auditChain);
});

/* ---------- 节点状态 ---------- */

function outcomeText(outcome) {
  if (!outcome) return el('span', { class: 'muted' }, '暂无');
  return el('span', {},
    el('span', { class: `badge ${outcome.ok ? 'ok' : 'bad'}` }, outcome.ok ? '成功' : '失败'),
    ` ${time(outcome.at)} · ${outcome.latencyMs} ms`,
    outcome.detail ? el('div', { class: 'error' }, outcome.detail) : null);
}

// 有指标端点的节点以最近一次指标读取判断可达；没有指标端点的节点以最近一次调用为准。
function health(status) {
  const reading = status.metricsReading;
  if (status.metrics) {
    if (!reading) return ['unknown', '读取中'];
    return reading.ok ? ['ok', '服务可达'] : ['bad', '服务不可达'];
  }
  if (!status.lastCall) return ['unknown', '尚未调用'];
  return status.lastCall.ok ? ['ok', '最近调用成功'] : ['bad', '最近调用失败'];
}

async function probe(node, button) {
  button.disabled = true;
  try {
    await api('POST', `/api/nodes/${node.id}/probe`);
  } catch (error) {
    if (error.status !== 502) alert(error.message);
  } finally {
    button.disabled = false;
    await loadNodes();
  }
}

function metricsTable(status) {
  if (!status.metrics) return el('p', { class: 'muted' }, '该节点的服务不提供指标端点。');
  const reading = status.metricsReading;
  const head = el('div', { class: 'muted' }, `${status.metrics.format} · ${status.metrics.endpoint}`
    + (reading ? ` · ${time(reading.at)}` : ''));
  if (!reading) return el('div', {}, head);
  if (!reading.ok) return el('div', {}, head, el('div', { class: 'error' }, reading.detail));
  if (!reading.items.length) return el('div', {}, head, el('p', { class: 'muted' }, '端点未提供所关注的指标。'));
  return el('div', {}, head, el('table', {},
    el('tr', {}, el('th', {}, '指标'), el('th', {}, '标签'), el('th', {}, '取值')),
    reading.items.map((sample) => el('tr', {},
      el('td', {}, sample.name), el('td', {}, el('code', {}, sample.labels)), el('td', {}, sample.value)))));
}

function renderGpu(gpu) {
  const box = document.getElementById('gpu');
  if (!gpu) {
    box.replaceChildren(el('p', { class: 'muted' }, '未配置 GPU 读数，或首次读取尚未完成。'));
    return;
  }
  if (!gpu.ok) {
    box.replaceChildren(el('p', { class: 'error' }, `${time(gpu.at)} · ${gpu.detail}`));
    return;
  }
  box.replaceChildren(el('div', { class: 'muted' }, `nvidia-smi · ${time(gpu.at)}`), el('table', {},
    el('tr', {}, ['序号', '型号', '显存已用 (MiB)', '显存总量 (MiB)', 'GPU 利用率 (%)', '功耗 (W)'].map((name) => el('th', {}, name))),
    gpu.items.map((device) => el('tr', {}, ['index', 'name', 'memory.used', 'memory.total', 'utilization.gpu', 'power.draw']
      .map((field) => el('td', {}, device[field]))))));
}

async function loadNodes() {
  const data = await api('GET', '/api/nodes');
  state.nodes = data.nodes;
  document.getElementById('nodes-updated').textContent = `更新于 ${time(new Date().toISOString())}`;
  document.getElementById('node-cards').replaceChildren(...data.nodes.map((node) => {
    const [kind, label] = health(node.status);
    const button = node.probe ? el('button', {}, '推理探测') : null;
    if (button) button.addEventListener('click', () => probe(node, button));
    return el('div', { class: 'card' },
      el('h3', {}, node.name, el('span', { class: `badge ${kind}` }, label), button),
      el('dl', {},
        el('dt', {}, '节点'), el('dd', {}, el('code', {}, node.id)),
        el('dt', {}, '端点'), el('dd', {}, node.endpoint),
        el('dt', {}, '模型'), el('dd', {}, node.model ?? '—'),
        el('dt', {}, '最近调用'), el('dd', {}, outcomeText(node.status.lastCall)),
        el('dt', {}, '最近探测'), el('dd', {}, node.probe ? outcomeText(node.status.lastProbe) : el('span', { class: 'muted' }, '不提供探测'))),
      metricsTable(node.status));
  }));
  renderGpu(data.gpu);
}

document.getElementById('nodes-refresh').addEventListener('click', loadNodes);

/* ---------- 启动 ---------- */

async function init() {
  await loadNodes();
  document.getElementById('chat-node').replaceChildren(
    ...state.nodes.map((node) => el('option', { value: node.id }, node.name)));
  setChain(localStorage.getItem('neuralcloud.chain') ?? crypto.randomUUID());
  await loadChat();
}

init().catch((error) => {
  document.getElementById('chat-error').textContent = `控制台加载失败：${error.message}`;
});
