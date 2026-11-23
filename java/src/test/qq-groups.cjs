'use strict';
// 正式两个 JVM、云端推理和隔离 OneBot 的联调。唯一外发目的地是本进程的回环 WebSocket。
// 参数为 Harness jar、Memory jar、Chat Worker 配置和一个尚不存在的证据目录。
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const net = require('node:net');
const crypto = require('node:crypto');
const { spawn } = require('node:child_process');
const { once } = require('node:events');
const { WebSocketServer } = require('ws');

const [harness, memory, chat, dir] = process.argv.slice(2).map(p => path.resolve(p));
fs.mkdirSync(dir, { mode: 0o700 });
const children = [];
const sent = [];
let member = true, socket, panel, mem, counter = 6000;
const sleep = ms => new Promise(ok => setTimeout(ok, ms));
const server = new WebSocketServer({ host: '127.0.0.1', port: 0 });
const env = { ...process.env, QQ_TRIAL_TOKEN: crypto.randomBytes(32).toString('hex'), QQ_TRIAL_MEMORY: crypto.randomBytes(32).toString('hex') };

// 假群只响应已知 OneBot 动作，对所有私聊和群聊发送都校验目标并返回可追溯的消息 ID。
server.on('connection', (ws, req) => {
  assert.equal(req.headers.authorization, 'Bearer ' + env.QQ_TRIAL_TOKEN);
  socket = ws;
  ws.on('message', bytes => {
    const { action, params, echo } = JSON.parse(bytes);
    let data, code = 0;
    switch (action) {
      case 'get_login_info': data = { user_id: 12345 }; break;
      case 'get_group_list': data = [{ group_id: 23456, group_name: '独立测试群', member_count: 3, max_member_count: 200 }]; break;
      case 'get_group_member_info':
        assert.deepEqual(params, { group_id: 23456, user_id: 67890, no_cache: true });
        if (member) data = { group_id: 23456, user_id: 67890 }; else code = 1404;
        break;
      case 'send_group_msg':
      case 'send_private_msg':
        assert.equal(action === 'send_group_msg' ? params.group_id : params.user_id, action === 'send_group_msg' ? 23456 : 67890);
        data = { message_id: ++counter };
        sent.push({ action, text: params.message[0].data.text, id: data.message_id });
        break;
      default: throw Error('未知 OneBot 动作 ' + action);
    }
    ws.send(JSON.stringify({ status: code ? 'failed' : 'ok', retcode: code, data, echo }));
  });
});

function config(name, values) {
  const file = path.join(dir, name + '.properties');
  fs.writeFileSync(file, Object.entries(values).map(([k,v]) => k + '=' + v).join('\n') + '\n');
  return file;
}
async function port() {
  const listener = net.createServer().listen(0, '127.0.0.1');
  await once(listener, 'listening');
  const n = listener.address().port;
  await new Promise(ok => listener.close(ok));
  return n;
}
function start(name, args) {
  const log = fs.openSync(path.join(dir, name + '.log'), 'w');
  const child = spawn('java', ['-Dfile.encoding=UTF-8', ...args], { env, stdio: ['ignore', log, log] });
  fs.closeSync(log);
  children.push(child);
}
async function until(check, label, ms = 120000) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    if (children.some(c => c.exitCode !== null)) throw Error('JVM 提前退出，详见 ' + dir);
    const result = await check();
    if (result) return result;
    await sleep(250);
  }
  throw Error('等待超时 ' + label);
}
async function get(uri) {
  const r = await fetch(panel + uri);
  return r.ok ? r.json() : null;
}
function emit(id, text, group, at = false) {
  const message = [{ type: 'text', data: { text } }];
  if (at) message.unshift({ type: 'at', data: { qq: '12345' } });
  socket.send(JSON.stringify({ post_type: 'message', message_type: group ? 'group' : 'private',
    sub_type: group ? 'normal' : 'friend', self_id: 12345, user_id: group ? 54321 : 67890, message_id: id,
    ...(group ? { group_id: 23456 } : {}), message }));
}
const chain = group => 'qq-' + (group ? 'group-12345-23456' : 'private-12345-67890');
async function events(group) { return (await get('audit/chains/' + chain(group)))?.events ?? []; }
async function policy(revision, policy, expected = 200) {
  const r = await fetch(panel + 'qq/groups/23456/policy', { method: 'PUT', headers: {'Content-Type':'application/json'}, body: JSON.stringify({ revision, policy }) });
  assert.equal(r.status, expected);
}
async function turn(id, text, group, at, terminal) {
  emit(id, text, group, at);
  return until(async () => {
    const all = await events(group), start = all.findIndex(e => e.type === 'qq_message' && e.data.messageId === id);
    if (start < 0) return false;
    const steps = all.slice(start);
    const failed = steps.find(e => ['node_failed','qq_delivery_failed','qq_delivery_unknown'].includes(e.type));
    if (failed) throw Error(JSON.stringify(failed));
    return steps.some(terminal) && steps;
  }, '消息 ' + id);
}
const durable = e => e.type === 'memory_durable' && e.data.kind === 'OBSERVATION';
const delivered = e => e.type === 'qq_delivery';
async function main() {
  const mp = await port(), hp = await port();
  panel = 'http://127.0.0.1:' + hp + '/api/';
  mem = 'http://127.0.0.1:' + mp + '/';
  const client = config('client', { endpoint:mem,characterId:'qq-trial',accessTokenEnv:'QQ_TRIAL_MEMORY',apiVersion:2,recallMode:'scoped' });
  const qc = config('onebot', { endpoint:'ws://127.0.0.1:' + server.address().port + '/',accountId:12345,allowedUserId:67890,
    accessTokenEnv:'QQ_TRIAL_TOKEN',groupPoliciesFile:path.join(dir,'policies.json') });
  const mc = config('memory', { port:mp,dataDirectory:path.join(dir,'database'),characterId:'qq-trial',sourceInstance:'neuralcloud-java',accessTokenEnv:'QQ_TRIAL_MEMORY' });
  const hc = config('console', { listenHost:'127.0.0.1',listenPort:hp,auditDirectory:path.join(dir,'audit'),chatWorkerConfig:chat,
    chatWorkerRolePrompt:'classpath:/config/chat-worker-misty-prompt.txt',routerConfig:'classpath:/config/router.properties',
    routerRolePrompt:'classpath:/config/router-role-prompt.txt',masterConfig:'classpath:/config/master-deepseek.properties',
    masterMaxTokens:1024,masterTemperature:0,openQqConfig:qc,memoryConfig:client,gpuMetrics:'NONE',metricsIntervalSeconds:10,metricsTimeoutSeconds:5 });
  start('memory', ['-jar', memory, '--config', mc]);
  await until(() => fetch(mem+'v2/health',{headers:{Authorization:'Bearer '+env.QQ_TRIAL_MEMORY}}).then(r=>r.ok,()=>false),'记忆就绪');
  start('harness', ['-cp',harness,'neuralcloud.controlplane.app.ConsoleMain','--config',hc]);
  await until(() => get('qq/groups').then(g=>g?.connected,()=>false),'面板群名单');
  assert.equal((await get('qq/groups')).groups[0].policy,'OFF');
  emit(101,'停收哨兵',true,true);
  await sleep(500);
  assert.equal((await events(true)).length,0);
  await policy(0,'OBSERVE');
  await policy(0,'OFF',409);
  const first=(await turn(102,'/通知 周六看展的集合地点是南门，集合暗号是蓝伞七号。',true,true,durable)).find(durable).data;
  assert.equal(first.revision,1);
  assert.equal(sent.length,0);
  const private1=await turn(103,'周六看展的集合地点和集合暗号是什么？请按群里最新通知回答。',false,false,delivered);
  const verify = (steps, version, words) => {
    for (const name of ['intent','router_plan','worker_dispatch','chat_worker_reply','qq_delivery']) assert(steps.some(e=>e.type===name),name);
    assert(steps.find(delivered).data.source_refs?.some(r=>r.record_id===first.recordId && r.revision===version),'确切来源版本 '+version);
    for (const w of words) assert(sent.at(-1).text.includes(w),sent.at(-1).text);
  };
  verify(private1,1,['南门','蓝伞']);
  const second=(await turn(104,'/变更通知 102@1 周六看展的集合地点改为北门，集合暗号改为红叶九号。',true,false,durable)).find(durable).data;
  assert.equal(second.recordId,first.recordId);
  assert.equal(second.revision,2);
  const private2=await turn(105,'请查周六看展群通知，只回答现行集合地点和集合暗号。',false,false,delivered);
  verify(private2,2,['北门','红叶']);
  assert(!sent.at(-1).text.includes('南门'));
  await policy(1,'OBSERVE_REPLY');
  await turn(106,'小雾，在吗？',true,true,delivered);
  const ambient=await turn(107,'甲乙两位自行处理登记，其他人无需回复。',true,false,e=>e.type==='qq_participation');
  const choice=ambient.find(e=>e.type==='qq_participation').data;
  assert.equal(choice.addressing,'AMBIENT');
  if (choice.decision==='REPLY') await until(async()=>(await events(true)).some(e=>e.type==='qq_delivery' && e.eventId>ambient[0].eventId),'参与回复送达');
  else assert.equal(choice.decision,'SKIP');
  await policy(2,'OFF');
  const summary=(await get('qq/groups')).groups[0];
  assert.equal(summary.received,4);
  assert.equal(summary.delivered,choice.decision==='REPLY'?2:1);
  console.log(JSON.stringify({ group:summary, notice:first.recordId, revisions:[first.revision,second.revision], privateDeliveries:2, participation:choice.decision, evidence:dir }));
}
main().catch(e=>{ console.error(e); process.exitCode=1; }).finally(async()=>{
  for (const child of children.slice().reverse()) { if (child.exitCode===null) { const done=once(child,'exit'); child.kill(); await done; } }
  server.close();
});
