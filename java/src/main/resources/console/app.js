'use strict';

// 网页控制台前端主脚本：对话记录、审计链因果树和节点监控均直接从服务端 API 拉取，页面不另存本地历史副本。
// state 字段说明：nodes 缓存节点元数据；chain 为当前对话会话链；auditChain 为审计页当前选中的链；
// nodesTimer / auditTimer 管理页签级轮询；auditRequest / auditSnapshot / auditPolling 用于防竞态与增量跳绘。
const state = { nodes: [], chain: null, auditChain: null, nodesTimer: null, auditTimer: null,
  auditRequest: 0, auditSnapshot: null, auditPolling: false };

// 封装 JSON HTTP 调用，非 2xx 响应时将服务端 error 字段与 HTTP 状态码一并抛出。
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

// 轻量级 DOM 节点构造辅助函数：支持 class、on* 事件绑定及嵌套子节点，默认将文本以纯文本节点插入防止 XSS。
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
const NODE_NAMES = { orchestrator: '编排器', 'task-worker': '任务 Worker', memory: '记忆', openqq: 'OpenQQ' };
const nodeName = (id) => state.nodes.find((node) => node.id === id)?.name ?? NODE_NAMES[id] ?? id;

// 各事件类型在对话和审计链里的一行摘要。
function summary(event) {
  const data = event.data;
  switch (event.type) {
    case 'operator_message': return data.text;
    case 'qq_message': return data.text;
    case 'observe_queued': return 'P3 已排队 · ' + (data.reason === 'COUNT' ? '累计 20 条' : '首条已等 30 分钟');
    case 'observe_claimed': return '本批领取 ' + data.sources.length + ' 条群原文';
    case 'observe_evidence': return data.originals.length ? '已取证 · ' + data.originals.length + ' 条可引用原文' : '全批原文已失效或失去引用权';
    case 'observe_submission': return data.submission.impression || '本批没有新印象';
    case 'observe_candidate': return 'Router 提交印象 · ' + data.source_refs.length + ' 条来源';
    case 'observe_completed': return data.result === 'IMPRESSION' ? '印象已持久入库' : '已完成 · 无新印象';
    case 'observe_refused': case 'observe_cancelled': return data.reason;
    case 'observe_retry': return data.detail;
    case 'observe_correction': case 'dreaming_correction': return '等待一次补正 · ' + data.detail;
    case 'observe_suspended': case 'dreaming_suspended': return '已挂起 · ' + data.detail;
    case 'qq_send_requested': return '已请求 OpenQQ 发送私聊回复';
    case 'qq_delivery': return 'OpenQQ 已确认发送，消息 ID ' + data.messageId;
    case 'qq_delivery_failed': return 'OpenQQ 拒绝发送，错误码 ' + data.retcode;
    case 'qq_delivery_unknown': return 'OpenQQ 发送状态未确认：' + data.detail;
    case 'console_response_written': return '控制台已写出 HTTP 回复';
    case 'router_memory_write': return data.kind === 'OBSERVATION' ? 'Router 提交用户发言入库' : 'Router 提交已送达的角色回复入库';
    case 'memory_durable': return ({OBSERVATION: '用户发言已写入记忆', SELF_EXPRESSION: '小雾的回复已写入记忆',
      impression: 'Master 的主观印象已写入记忆'})[data.kind] || '已取得持久记忆回执';
    case 'memory_recall': {
      if (!data.historicalRecordIds) return `读取了 ${data.recordIds.length} 条记忆材料`;
      // 旧事件的 recordIds 同时包含历史检索结果和额外回读的近期对话。
      const hits = new Set(data.historicalRecordIds);
      const recent = data.recordIds.filter(id => !hits.has(id)).length;
      return `历史检索命中 ${hits.size} 条` + (recent ? `，另有 ${recent} 条近期对话` : '');
    }
    case 'chat_worker_received': return 'Chat Worker 已接收本轮消息';
    case 'subject_conflict': return '提交 Master 终审：' + data.conflict;
    case 'chat_worker_turn_plan': return 'Chat Worker 选择 ' + data.kind;
    case 'memory_call': case 'chat_worker_memory_request': return data.kind === 'SELF_HISTORY' ? '请求查询本人跨会话发言：' + data.query
      : data.query ? '请求查询记忆：' + data.query : '请求查询当前用户身份';
    case 'memory_call_result': return data.sentences.join('\n');
    case 'memory_call_failed': return '记忆查询失败：' + data.detail;
    case 'router_memory_query': return data.kind === 'SELF_HISTORY' ? 'Router 已按当前 QQ 发件人查询历史发言' : 'Router 已发起记忆查询';
    case 'router_memory_result': return data.sentences?.join('\n') || 'Router 已回传 ' + data.count + ' 条材料';
    case 'memory_write_failed': return '记忆写入失败：' + data.detail;
    case 'chat_worker_reply': return data.text;
    case 'conversation_no_reply': return 'Master 已确认本轮无需回复。';
    case 'chat_worker_router_request': return '已将本轮消息交给 Router 分流';
    case 'task_worker_dispatch': return '已派发给任务 Worker';
    case 'task_worker_result': return '已读取当前 Linux 服务器的状态';
    case 'task_worker_unavailable': return '本轮请求没有可用的执行能力';
    case 'router_route': return `流程：${data.route}`;
    case 'master_completion': return data.text || '（空正文）';
    // 对话页呈现已接受的评价与回复意图，具体记录版本可在同一事件的审计数据中查看。
    case 'intent': return [data.assessment, data.needsReply ? '回复意图　' + data.replyIntent : '本轮无需回复',
      data.impression && '新印象　' + data.impression].filter(Boolean).join('\n');
    case 'node_failed': return `失败：${data.detail}`;
    case 'probe_requested': return '请求探测';
    case 'probe_passed': return `探测通过，${data.latencyMs} ms`;
    case 'pipeline_completed': return data.completed
      ? `链路完成，共 ${data.replies.length} 条回复（${data.caseId}，提醒核对模拟为 ${data.simulatedOutcome}）`
      : `链路在中途被拒绝，原因见审计链（${data.caseId}）`;
    case 'router_run_started': return `Router ${data.routerModel} · Master ${data.masterModel} · ${data.workerMode}`;
    case 'router_input': case 'router_report': case 'chat_worker_draft': return data.text;
    case 'router_rejected': return `拒绝：${data.detail}`;
    case 'router_plan': return data.steps.map((step) => `${step.id} → ${step.worker}`
      + (step.dependsOn.length ? `（等待 ${step.dependsOn.join('、')}）` : '')).join('\n');
    case 'worker_dispatch': return `${data.action} → ${data.worker}：${data.text}`;
    case 'reminder_receipt': return `模拟回执：${data.receipt.outcome}`;
    case 'model_input': return `${data.phase} 第 ${data.round} 轮，${data.messages.length} 条消息`;
    case 'model_output': return data.text || `工具调用 ${data.toolCallCount} 次`;
    case 'memory_query': return `${data.kind}查询`;
    case 'memory_result': return data.text;
    case 'required_results': case 'submission_retry': return `须提交：${data.items.join('、')}`;
    case 'result_submission': return data.missing.length ? `仍缺：${data.missing.join('、')}` : '必交结果已齐';
    case 'decision': return Object.entries(data).map(([key, value]) => `${key}=${JSON.stringify(value)}`).join('\n');
    case 'case_result': return `${data.category} ${data.detail}`;
    default: return '';
  }
}

// 对话页只显示操作者能直接看到的往返，链路中间步骤在审计链里查看。
const CHAT_TYPES = new Set(['operator_message', 'chat_worker_reply', 'router_route', 'master_completion', 'intent',
  'node_failed', 'chat_worker_draft', 'router_rejected', 'pipeline_completed', 'conversation_no_reply']);

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

// 切换顶部功能页签，并按需启停对应页签的后台定时轮询（审计页 3 秒刷新打开的链，节点页 5 秒刷新状态）。
function showTab(name) {
  document.querySelectorAll('.tab').forEach((tab) => tab.classList.toggle('active', tab.dataset.tab === name));
  document.querySelectorAll('.panel').forEach((panel) => panel.classList.toggle('active', panel.id === `tab-${name}`));
  clearInterval(state.nodesTimer);
  clearInterval(state.auditTimer);
  clearInterval(state.qqTimer);
  if (name === 'playground') window.dialoguePlayground?.open();
  if (name === 'qq') {
    loadQq();
    state.qqTimer = setInterval(() => {
      if (!document.hidden && !document.activeElement?.closest('#qq-rooms select') && !document.querySelector('#qq-rooms select[data-saving]')) loadQq();
    }, 5000);
  }
  if (name === 'audit') {
    loadChains();
    refreshOpenAudit();
    state.auditTimer = setInterval(refreshOpenAudit, 3000);
  }
  if (name === 'nodes') {
    loadNodes();
    state.nodesTimer = setInterval(loadNodes, 5000);
  }
}

document.querySelectorAll('.tab').forEach((tab) => tab.addEventListener('click', () => showTab(tab.dataset.tab)));
// 同一窗口打开另一个评测链接时，浏览器只触发 hashchange。
window.addEventListener('hashchange', () => {
  if (location.hash.split('/')[0] === '#playground') showTab('playground');
});

/* ---------- 对话 ---------- */

// 构造带或不带 ?chain=<id> 查询参数的控制台地址，用于分享、续聊与新建会话跳转。
function chatUrl(chain) {
  const url = new URL(location.href);
  if (chain === null) url.searchParams.delete('chain');
  else url.searchParams.set('chain', chain);
  return url;
}

// 仅含 operator_message 的链属于网页控制台会话；QQ 渠道或系统探测链只能在审计页只读查看。
const isConsoleChat = (events) => events.some((event) => event.type === 'operator_message');

// 加载并渲染指定 chain 的控制台对话历史：校验链名格式、过滤非控制台会话并同步 URL 与 localStorage。
async function loadChat(chain, linked = false) {
  const log = document.getElementById('chat-log');
  if (!/^[A-Za-z0-9-]{1,64}$/.test(chain) || chain.startsWith('probe-')) {
    throw new Error('会话标识无效，请核对链接或新建会话。');
  }
  let events = [];
  try {
    events = (await api('GET', `/api/audit/chains/${chain}`)).events;
  } catch (error) {
    if (error.status !== 404) throw error;
    if (linked) throw new Error('找不到链接中的会话，请核对链接或新建会话。');
  }
  if (events.length && !isConsoleChat(events)) throw new Error('这条记录只能在审计链中查看。');
  // 确认渠道和会话后才绑定发送目标。刷新或分享已有对话时仍指向同一条链。
  if (events.length) history.replaceState(null, '', chatUrl(chain));
  state.chain = chain;
  localStorage.setItem('neuralcloud.chain', chain);
  document.getElementById('chat-chain').textContent = chain;
  // 一个会话可同时使用独立节点和对话编排。沿 causedBy 辨认编排内部事件，保留独立节点的返回。
  const planned = new Set();
  const visible = events.filter((event) => {
    const inPlan = (event.type === 'operator_message' && event.node === 'conversation')
      || planned.has(auditEventKey(event.runId, event.causedBy));
    if (inPlan) planned.add(auditEventKey(event.runId, event.eventId));
    return CHAT_TYPES.has(event.type) && !(inPlan && ['intent', 'router_route'].includes(event.type)
      && event.data.latencyMs === undefined);
  });
  log.replaceChildren(...visible.map((event) => {
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

// 将操作者输入提交到当前选中节点；无论成功或 502 失败，最终都重新拉取服务端审计链以呈现真实落地状态。
async function sendChat(event) {
  event.preventDefault();
  const text = document.getElementById('chat-text');
  const button = document.getElementById('chat-send');
  const errorLine = document.getElementById('chat-error');
  const node = document.getElementById('chat-node').value;
  if (button.disabled || !text.value.trim()) return;
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
    await loadChat(state.chain);
  }
}

document.getElementById('chat-form').addEventListener('submit', sendChat);
document.getElementById('chat-text').addEventListener('keydown', (event) => {
  if (event.key === 'Enter' && event.ctrlKey) document.getElementById('chat-form').requestSubmit();
});
document.getElementById('chat-new').addEventListener('click', () => {
  localStorage.removeItem('neuralcloud.chain');
  location.assign(chatUrl(null));
});

/* ---------- 审计链 ---------- */

// 加载左侧全部审计链摘要列表（按服务端返回的最近活跃顺序排列）。
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
const auditEventKey = (runId, id) => `${runId}:${id}`;

// 根据 causedBy 因果关系把扁平事件列表重建为嵌套 DOM 树，并保留用户已手动展开的 <details> 原始 JSON 面板。
function buildTree(events, expanded) {
  const key = auditEventKey;
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
  // 只有一个后续事件时画在同一层，出现分叉时才缩进，避免长链路逐级右移。
  const render = (event) => {
    const eventKey = key(event.runId, event.eventId);
    const kids = children.get(eventKey) ?? [];
    const details = { 'data-event-key': eventKey };
    if (expanded.has(eventKey)) details.open = '';
    const item = el('li', { class: `event ${event.type}` },
      el('div', { class: 'event-head' },
        el('span', { class: 'badge' }, `#${event.eventId}`),
        el('span', { class: 'event-type' }, event.type),
        el('span', { class: 'muted' }, `${nodeName(event.node)} · ${time(event.recordedAt)}${latency(event)}`)),
      el('div', { class: 'event-summary' }, summary(event)),
      event.type === 'observe_evidence' && event.data.originals.length ? el('details',
        { 'data-event-key': eventKey + '-sources', ...(expanded.has(eventKey + '-sources') ? { open: '' } : {}) },
        el('summary', {}, '查看本次取证原文'), el('ol', {}, event.data.originals.map(s => el('li', {},
          el('div', { class: 'muted' }, s.ref.record_id + ' @ ' + s.ref.revision), el('div', { class: 'event-summary' }, s.text))))) : null,
      el('details', details, el('summary', { class: 'muted' }, '原始事件'), el('pre', {}, JSON.stringify(event, null, 2))),
      kids.length > 1 ? el('ul', {}, kids.flatMap(render)) : null);
    return kids.length === 1 ? [item, ...render(kids[0])] : [item];
  };
  return el('ul', { class: 'tree' }, roots.flatMap(render));
}

// 拉取并渲染指定审计链的全部事件；通过递增请求号丢弃过期的并发响应，并用快照签名避免无新事件时重绘打断阅读。
function observationEvents(events, batch) {
  const steps = events.filter(e => e.data.batchId === batch);
  const sources = steps.flatMap(e => e.data.sources || []);
  const selected = new Set(sources.map(s => auditEventKey(s.runId, s.eventId)));
  // 同运行的原文写请求及回执沿因果链查找；跨重启的群原文由批次保存的 runId/eventId 定位。
  return events.filter(e => {
    const source = selected.has(auditEventKey(e.runId, e.eventId));
    if (['router_memory_write', 'memory_durable'].includes(e.type) && selected.has(auditEventKey(e.runId, e.causedBy))) {
      selected.add(auditEventKey(e.runId, e.eventId));
      return true;
    }
    return source || e.data.batchId === batch;
  });
}

async function loadAudit(chain, batch = null) {
  const request = ++state.auditRequest;
  state.auditChain = chain;
  state.auditBatch = batch;
  document.querySelectorAll('#audit-chains li').forEach((item) => {
    item.classList.toggle('active', item.querySelector('.id')?.textContent === chain);
  });
  const response = await api('GET', `/api/audit/chains/${chain}`);
  if (request !== state.auditRequest) return;
  const events = batch ? observationEvents(response.events, batch) : response.events;
  const latest = events.at(-1);
  const snapshot = JSON.stringify([chain, batch, events.length, latest?.runId, latest?.eventId]);
  if (snapshot === state.auditSnapshot) return;
  state.auditSnapshot = snapshot;
  const panel = document.getElementById('audit-events');
  const expanded = new Set(Array.from(panel.querySelectorAll('details[open]'), (detail) => detail.dataset.eventKey));
  const runs = new Set(events.map((event) => event.runId));
  panel.replaceChildren(
    el('div', { class: 'toolbar' }, el('strong', {}, batch ? '整理批次 ' + batch : chain),
      el('span', { class: 'muted' }, `${events.length} 条事件 · ${runs.size} 个进程`),
      isConsoleChat(events) ? el('a', { href: chatUrl(chain).href }, '继续此会话') : null),
    buildTree(events, expanded));
}

// 定时增量刷新当前打开的审计链（例如跟踪 QQ 异步后续回复或长链路步骤），串行防重入。
async function refreshOpenAudit() {
  if (!state.auditChain || state.auditPolling) return;
  state.auditPolling = true;
  try {
    await loadAudit(state.auditChain, state.auditBatch);
  } catch (error) {
    console.warn('刷新当前审计链失败', error);
  } finally {
    state.auditPolling = false;
  }
}

document.getElementById('audit-refresh').addEventListener('click', async () => {
  await loadChains();
  if (state.auditChain) await loadAudit(state.auditChain, state.auditBatch);
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

// 向指定节点触发一次服务端推理探测（POST /api/nodes/{id}/probe），完成后立即刷新节点状态卡片。
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

// 渲染单个节点的 Prometheus 监控指标表（无指标端点、读取失败或无关注指标时展示明确状态说明）。
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

// 渲染本机 nvidia-smi GPU 状态表格（显存、算力利用率与功耗）。
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

// 从 /api/nodes 刷新所有节点卡片与 GPU 状态，同时更新全局节点列表缓存供节点名称翻译使用。
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

// 名单、保存与版本冲突均以服务器回执为准。每张卡可直接打开该群历史审计。
const qqModes = { OFF: '不接收', OBSERVE: '仅观察', OBSERVE_REPLY: '观察与回复' };
const observationStates = { IDLE: '空闲', WAITING: '等待触发', QUEUED: '已排队', RUNNING: '整理中',
  SENDING: '等待入库回执', RETRY: '等待重试', SUSPENDED: '已挂起', DISCONNECTED: '等待重连' };
function observationPanel(room) {
  const s = room.observation;
  if (s.state === 'DISABLED') return el('p', { class: 'muted' }, '印象整理未启用');
  const open = (id, label) => id ? el('button', { onclick: () => { showTab('audit'); loadAudit(room.chain, id); } }, label) : null;
  const last = s.lastBatchId ? '上次完成 ' + time(s.lastCompletedAt) + ' · ' + (s.lastResult === 'IMPRESSION' ? '已保存新印象' : '无新印象') : '尚未整理';
  return el('div', {}, el('p', {}, '待整理 ' + s.pending + ' 条 · ' + observationStates[s.state]),
    el('p', { class: 'muted' }, last), el('div', { class: 'toolbar' },
      open(s.currentBatchId, '当前批次审计'), open(s.lastBatchId, '上次批次审计')));
}
let qqRequest = 0;
async function loadQq() {
  const request = ++qqRequest;
  const error = document.getElementById('qq-error');
  try {
    const data = await api('GET', '/api/qq/groups');
    if (request !== qqRequest) return;
    error.textContent = '';
    document.getElementById('qq-status').textContent = !data.enabled ? '未配置群接入' :
      [data.connected ? '已连接' : '已断开', '账号 ' + data.account, data.groups.length + ' 个群', time(data.refreshed)].filter(Boolean).join(' · ');
    const cards = data.groups.map(room => {
      const select = el('select', { 'aria-label': room.name + ' 接收策略' },
        Object.entries(qqModes).map(([value, label]) => el('option', { value }, label)));
      select.value = room.policy;
      select.disabled = !data.connected;
      select.addEventListener('change', async () => {
        select.disabled = true;
        select.dataset.saving = 'true';
        ++qqRequest;
        try {
          await api('PUT', '/api/qq/groups/' + room.id + '/policy', { policy: select.value, revision: room.revision });
          await loadQq();
        } catch (failure) {
          await loadQq();
          error.textContent = failure.message;
        }
      });
      const audit = el('button', {}, '查看审计链');
      audit.disabled = room.received === 0;
      audit.addEventListener('click', () => { showTab('audit'); loadAudit(room.chain); });
      return el('article', { class: 'card' }, el('h2', {}, room.name), el('p', { class: 'muted' }, '群号 ' + room.id + ' · ' + room.members + ' 人'),
        el('p', {}, '接收 ' + room.received + ' 条 · 确认回复 ' + room.delivered + ' 条'),
        room.lastReceived ? el('p', { class: 'muted' }, '最近接收 ' + time(room.lastReceived)) : null,
        room.lastDelivered ? el('p', { class: 'muted' }, '最近回复 ' + time(room.lastDelivered)) : null,
        el('div', { class: 'toolbar' }, el('label', {}, '接收策略 ', select), audit), observationPanel(room));
    });
    document.getElementById('qq-rooms').replaceChildren(...cards);
  } catch (failure) { if (request === qqRequest) error.textContent = failure.message; }
}
document.getElementById('qq-refresh').addEventListener('click', loadQq);

/* ---------- 启动 ---------- */

// 页面初始化：先加载可用节点填充下拉框，再按“URL ?chain 参数 > 上次 localStorage 会话 > 随机 UUID”恢复当前对话。
async function init() {
  await loadNodes();
  document.getElementById('chat-node').replaceChildren(
    ...state.nodes.map((node) => el('option', { value: node.id }, node.name)));
  const linked = new URL(location.href).searchParams.get('chain');
  await loadChat(linked ?? localStorage.getItem('neuralcloud.chain') ?? crypto.randomUUID(), linked !== null);
  if (location.hash === '#qq') showTab('qq');
  if (location.hash.split('/')[0] === '#playground') showTab('playground');
  document.getElementById('chat-send').disabled = false;
}

init().catch((error) => {
  document.getElementById('chat-error').textContent = `控制台加载失败：${error.message}`;
});
