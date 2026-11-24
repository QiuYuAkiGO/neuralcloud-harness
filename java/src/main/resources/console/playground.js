'use strict';
// 只经同源 Playground 接口交互。实验选择留在内存，私聊正文不进入浏览器持久缓存。
window.dialoguePlayground = (() => {
  const host = document.getElementById('pg-root');
  const s = {page:'回放', kind:'', query:'', after:'', before:'', source:'', conv:'', cases:[],
    rooms:[], timeline:[], selected:null, saved:null, run:null, report:null, a:'', b:'', generation:0, poll:null};
  const labels = {queued:'排队中',running:'运行中',complete:'已完成',failed:'失败',awaiting_scores:'等待 CharacterRM 评分',
    PASS:'通过',FAIL:'未通过',REVIEW:'待复核',REPLY:'参与',SKIP:'跳过',EITHER:'两者均可'};
  const nice = x => labels[x] || x || '—';
  const shortTime = x => x ? new Date(x).toLocaleString('zh-CN',{hour12:false}) : '';
  const parts = ['回放','配置版本','回归批次','公开评测'];
  function e(tag, attrs={}, ...children) {
    const n=document.createElement(tag);
    for (const [k,v] of Object.entries(attrs)) {
      if (k==='class') n.className=v;
      else if (k==='click') n.addEventListener('click',ev=>{ev.preventDefault(); action(()=>v(ev));});
      else if (k==='change') n.addEventListener('change',ev=>action(()=>v(ev.target.value,ev.target)));
      else if (k==='value') n.value=v??'';
      else if (k==='checked'||k==='disabled'||k==='multiple') n[k]=!!v;
      else n.setAttribute(k,v);
    }
    for (const v of children.flat()) if (v!==undefined && v!==null) n.append(v instanceof Node?v:String(v));
    return n;
  }
  const button=(label,fn,cls='')=>e('button',{type:'button',class:cls,click:fn},label);
  const label=(name,control)=>e('label',{class:'pg-field'},e('span',{},name),control);
  const note=text=>e('p',{class:'pg-note'},text);
  const block=(title,...content)=>e('section',{class:'pg-card'},e('h3',{},title),...content);
  function select(options,selected,fn) {
    const n=e('select',fn?{change:fn}:{});
    for(const [value,name] of options) n.append(e('option',{value},name));
    if(selected!=null) n.value=selected;
    return n;
  }
  const configs=()=>s.info.configs.map(c=>[c.id,c.name+' · '+c.id.slice(0,6)]);
  const verdict=()=>select([['PASS','通过'],['FAIL','未通过']],'PASS');
  async function api(path,body) {
    const res=await fetch('/api/playground/'+path,{method:body===undefined?'GET':'POST',cache:'no-store',
      headers:body===undefined?{}:{'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body)});
    const x=await res.json();
    if(!res.ok) throw Error(x.error || '请求失败 '+res.status);
    return x;
  }
  function message(txt,err=false) {
    const n=host.querySelector('.pg-status'); if(n){n.textContent=txt;n.classList.toggle('pg-error',err);}
  }
  async function action(task) {
    try {await task();} catch(err){message(err.message,true);}
  }
  async function info() {
    s.info=await api('state');
    s.source ||= s.info.sources[0]?.id || '';
    s.a ||= s.info.configs.at(-1)?.id || '';
    if(!s.b || s.b===s.a) s.b=s.info.configs.find(c=>c.id!==s.a)?.id || '';
  }
  async function loadRooms() {
    const args=new URLSearchParams({source:s.source,kind:s.kind,q:s.query,after:s.after,before:s.before});
    s.rooms=s.source?await api('conversations?'+args):[];
  }
  async function room(id, target=null) {
    ++s.generation;
    s.conv=id;s.saved=null;s.run=null;
    s.timeline=await api('messages?'+new URLSearchParams({source:s.source,conversation:id}));
    s.selected=target?s.timeline.find(m=>m.id===target):null;
    draw();
  }
  async function openCase(c) {
    s.page='回放';s.source=c.source;await room(c.conversation,c.target);s.saved=c;draw();
  }
  async function openRun(id) {
    clearTimeout(s.poll);
    const run=await api('runs/'+id);
    const c=s.info.cases.find(c=>c.id===run.caseId);
    if(!c) throw Error('案例已不可用');
    if(s.saved?.id!==c.id || s.page!=='回放') await openCase(c);
    const editing=host.querySelector('.pg-lane') && document.activeElement?.closest('.pg-lane details');
    if(s.run?.id===id && editing && !s.run.revealed){
      s.poll=setTimeout(()=>{if(s.run?.id===id)action(()=>openRun(id));},1400);
      return;
    }
    const changed=s.run?.id!==id || s.run.status!==run.status ||
      run.lanes.some((l,i)=>s.run.lanes[i]?.status!==l.status);
    s.run=run;if(changed)draw();
    if(!['complete','failed'].includes(run.status)) {
      const token=s.generation;
      s.poll=setTimeout(()=>{if(token===s.generation && s.run?.id===id) action(()=>openRun(id));},1400);
    }
  }
  function draw() {
    const heading=e('div',{class:'pg-heading'},e('div',{},e('h2',{},'对话测试'),
      e('p',{},'在历史发言的时间点重放小雾的判断与回复。')),
      s.info?e('span',{class:'pg-scope'},'隔离实验库'):null);
    const tabs=e('div',{class:'pg-tabs','aria-label':'实验工作区'},parts.map(p=>
      button(p,async()=>{s.page=p;++s.generation;await info();draw();},s.page===p?'chosen':'')));
    const status=e('p',{class:'pg-status',role:'status','aria-live':'polite'});
    host.replaceChildren(heading,tabs,status);
    if(!s.info) return;
    if(s.page==='回放') host.append(replay());
    if(s.page==='配置版本') host.append(settingsPage());
    if(s.page==='回归批次') host.append(batchPage());
    if(s.page==='公开评测') host.append(benchPage());
  }
  function sidebar(){
    const kind=select([['','全部会话'],['group','群聊'],['private','私聊']],s.kind);
    const q=e('input',{type:'search',value:s.query,placeholder:'搜索原话或会话号','aria-label':'搜索原话或会话号'});
    const after=e('input',{type:'date',value:s.after}),before=e('input',{type:'date',value:s.before});
    const find=async()=>{s.kind=kind.value;s.query=q.value;s.after=after.value;s.before=before.value;
      await loadRooms();draw();};
    q.addEventListener('keydown',ev=>{if(ev.key==='Enter')action(find);});
    const sourceSelect=select(s.info.sources.map(it=>[it.id,it.id]),s.source,async id=>{
      s.source=id;s.conv='';s.selected=null;s.saved=null;s.timeline=[];s.run=null;await loadRooms();draw();});
    const list=e('div',{class:'pg-rooms'},s.rooms.map(it=>
      e('button',{class:'pg-room '+(it.id===s.conv?'active':''),click:()=>room(it.id)},
        e('strong',{},(it.kind==='group'?'群聊 ':'私聊 ')+it.id.split(':').at(-1)),
        e('small',{},it.count+' 条发言 · '+shortTime(it.last).split(' ')[0]))));
    return e('aside',{class:'pg-browser'},block('历史会话',label('记忆快照',sourceSelect),
      e('div',{class:'pg-stack'},kind,q),
      e('details',{},e('summary',{},'按时间筛选'),label('开始日期',after),label('结束日期',before)),
      button('筛选会话',find),list,s.rooms.length?null:note('没有匹配的会话。')),
      block('已保存案例',s.info.cases.filter(c=>c.source===s.source).map(c=>button(c.name,()=>openCase(c),'pg-link'))),
      e('details',{class:'pg-card'},e('summary',{},'最近运行'),s.info.runs.slice(0,12).map(r=>
        button(shortTime(r.created)+' · '+nice(r.status),()=>openRun(r.id),'pg-link'))));
  }
  function transcript(){
    const search=s.query.toLocaleLowerCase();
    const messages=s.selected?s.timeline.filter(m=>m.seq<=s.selected.seq):s.timeline;
    const list=e('div',{class:'pg-transcript',tabindex:'0'});
    for(const m of messages){
      const selected=s.selected?.id===m.id;
      const day=m.time.slice(0,10), filtered=Boolean(search||s.after||s.before);
      const hit=filtered && (!search||m.text.toLocaleLowerCase().includes(search)||s.conv.includes(search)) &&
        (!s.after||day>=s.after) && (!s.before||day<=s.before);
      const row=e(m.kind==='observation'?'button':'div',{class:'pg-msg '+(selected?'target ':'')+
        (m.kind==='self_expression'?'misty ':'')+(hit?'match':'')},
        e('span',{class:'pg-msg-meta'},e('strong',{},m.kind==='self_expression'?'小雾':m.speaker||m.sender||'历史发言者未知'),
          e('time',{},shortTime(m.time))),e('span',{class:'pg-body'},m.text),
        selected?e('strong',{class:'pg-fence'},'本轮模型可见到这里'):null);
      if(m.kind==='observation')row.addEventListener('click',()=>{s.selected=m;s.saved=null;s.run=null;++s.generation;draw();});
      list.append(row);
    }
    if(s.selected) requestAnimationFrame(()=>{list.scrollTop=list.scrollHeight;});
    else if(search||s.after||s.before) requestAnimationFrame(()=>{const hit=list.querySelector('.match');if(hit)list.scrollTop=hit.offsetTop-list.firstElementChild.offsetTop;});
    return block(s.conv?'会话时间线':'选择历史会话',s.conv?e('div',{class:'pg-row'},e('code',{},s.conv),
      s.selected?button('重新选点',()=>{s.selected=null;s.saved=null;s.run=null;++s.generation;draw();}):null):null,
      s.conv?list:note('从左侧选一个群聊或私聊，再点要测试的发言。'));
  }
  function picks(){
    return e('div',{class:'pg-pair'},label('配置 A',select(configs(),s.a,id=>{s.a=id;})),
      label('配置 B',select([['','选择另一版本'],...configs()],s.b,id=>{s.b=id;})));
  }
  function caseEditor(){
    const m=s.selected, current=s.saved;
    if(!m)return null;
    if(s.run && !s.run.revealed)return block(current.name,
      note('记忆水位 '+current.watermark+' · 原文修订 '+current.revision),
      button('回到截点重新配置',()=>{++s.generation;s.run=null;draw();}));
    const group=s.conv.startsWith('group:');
    const name=e('input',{value:current?.name||shortTime(m.time)+' · '+(group?'群聊':'私聊')});
    const policy=select([['','缺少策略证据'],['OBSERVE_REPLY','观察与回复'],['OBSERVE','仅观察'],['OFF','不接收']],
      current?.policy??m.policy??'');
    const addressed=select([['','寻址未知'],['true','直接点名'],['false','普通群消息']],
      String(current?.addressed??m.addressed??''));
    const sender=e('input',{value:current?.speaker??m.sender??'',placeholder:'发件人 QQ'});
    const initialSplit=s.info.cases.find(c=>c.conversation===s.conv)?.split||'dev';
    const split=select([['dev','调试集'],['regression','留出回归集']],current?.split||initialSplit);
    const expect=select([['','待人工定标'],['REPLY','应参与'],['SKIP','应沉默'],['EITHER','两者均可']],current?.expected||'');
    const why=e('input',{value:current?.rationale||'',placeholder:'哪条上下文支持这个判断？'});
    async function save(){
      // 已保存变体可以显式确认与原文相同的字段；复用它的 ID 和原始条件声明。
      const chosenSpeaker=sender.value.trim()||null;
      const chosenAddress=addressed.value===''?null:addressed.value==='true';
      const chosenPolicy=policy.value||null;
      if(current && name.value===current.name && split.value===current.split && chosenSpeaker===current.speaker &&
        (!group || (chosenAddress===current.addressed && chosenPolicy===current.policy)) &&
        (current.expected||'')===expect.value && (current.rationale||'')===(expect.value?why.value:''))return current;
      const variant={...current?.variant};
      if(chosenSpeaker!==current?.speaker)delete variant.speaker;
      if(chosenPolicy!==current?.policy)delete variant.policy;
      if(chosenAddress!==current?.addressed)delete variant.addressed;
      if(group && policy.value && policy.value!==m.policy)variant.policy=policy.value;
      if(group && addressed.value && (addressed.value==='true')!==m.addressed)variant.addressed=addressed.value==='true';
      if(sender.value && sender.value!==m.sender)variant.speaker=sender.value.trim();
      const body={source:s.source,conversation:s.conv,target:m.id,name:name.value,split:split.value,variant};
      if(expect.value){body.expected=expect.value;body.rationale=why.value;}
      s.saved=await api('cases',body);await info();return s.saved;
    }
    const missing=[!m.sender?'发件人':null,group&&m.policy==null?'群策略':null,
      group&&m.addressed==null?'是否点名':null].filter(Boolean);
    return block('测试截点',note('记忆水位 '+(m.seq-1)+' · 原文修订 '+m.revision),
      missing.length?note('历史证据缺少'+missing.join('、')+'。指定条件后可作为变体运行。'):null,
      group?note(m.addressed===true?'原始寻址：直接点名小雾。':'其他收件人或具体对谈对象以原话中保留的称呼、引用为准。'):null,
      m.revision!==1?note('这条发言已被更正，原始收信版本无法重放。'):null,
      label('案例名称',name),e('div',{class:'pg-pair'},label('分集',split),label('发言人',sender)),
      group?e('div',{class:'pg-pair'},label('群策略',policy),label('寻址',addressed)):null,
      group?e('details',{},e('summary',{},'人工参与标签'),label('期望',expect),label('依据',why)):null,
      picks(),e('div',{class:'pg-row'},button('保存案例',async()=>{await save();draw();message('案例已保存。');}),
        button('运行 A/B',async()=>{const c=await save();const r=await api('runs',{caseId:c.id,configs:[s.a,s.b]});
          await openRun(r.id);},'pg-primary')),
      !s.b?button('新建对照配置',()=>{s.page='配置版本';draw();},'pg-link'):null);
  }
  function details(title,value){
    return e('details',{},e('summary',{},title),e('pre',{},JSON.stringify(value,null,2)));
  }
  function laneCard(lane,run){
    const header=e('div',{class:'pg-lanehead'},e('h4',{},'分支 '+lane.side),
      e('strong',{class:'pg-badge'},nice(lane.decision||lane.status)));
    const card=e('article',{class:'pg-lane'},header);
    if(lane.configuration)card.append(note(lane.configuration));
    if(lane.error)card.append(e('p',{class:'pg-error'},lane.error));
    if(lane.status!=='complete')return card;
    card.append(e('p',{class:'pg-answer'},lane.reply||'本轮保持沉默。'));
    if(lane.initial)card.append(e('details',{},e('summary',{},'自动初评'),
      e('p',{},'人设 · '+nice(lane.initial.persona)+'　'+lane.initial.personaReason),
      e('p',{},'回应 · '+nice(lane.initial.reasonable)+'　'+lane.initial.reasonableReason)));
    if(lane.reviewReason||lane.judgeError)card.append(note('待人工复核 · '+(lane.reviewReason||lane.judgeError)));
    card.append(details('本轮输入与路由',{input:lane.input,trace:lane.trace}));
    if(lane.modelInputs)card.append(details('实际模型请求',lane.modelInputs));
    if(lane.reviews.length)card.append(details('复核历史',lane.reviews));
    const persona=verdict(),reasonable=verdict(),reason=e('textarea',{rows:2,placeholder:'写下判断依据'});
    const review=e('details',{},e('summary',{},'人工复核'),
      lane.reply?label('人设一致',persona):null,lane.reply?label('回应合理',reasonable):null,label('复核理由',reason),
      button('提交评价',async()=>{s.run=await api('runs/'+run.id+'/review',{side:lane.side,
        persona:lane.reply?persona.value:null,reasonable:lane.reply?reasonable.value:null,reason:reason.value});draw();}));
    const m=s.saved;const qtext=e('textarea',{rows:2,placeholder:'输入下一条发言'});
    const speakers=[...new Set([m.speaker,...s.timeline.filter(x=>x.seq<=m.watermark+1).map(x=>x.sender)].filter(Boolean))];
    const speaker=select(speakers.map(v=>[v,v]),m.speaker);
    const addressed=select([['true','点名小雾'],['false','普通群消息']],'true');
    const follow=e('details',{},e('summary',{},'沿此分支续聊'),
      m.kind==='group'?e('div',{class:'pg-pair'},label('发言人',speaker),label('寻址',addressed)):null,
      label('后续发言',qtext),button('发送到实验分支',async()=>{
        const res=await api('runs/'+run.id+'/turns',{side:lane.side,speaker:m.kind==='group'?speaker.value:m.speaker,
          addressed:m.kind==='private'||addressed.value==='true',text:qtext.value});await openRun(res.id);}));
    card.append(review,follow);return card;
  }
  function results(){
    if(!s.run)return null;
    const r=s.run;
    return block('A/B 回放',e('div',{class:'pg-row'},e('strong',{},nice(r.status)),
      note(r.revealed?'已揭晓':'盲评中 · 所有成功分支提交评价后揭晓配置和历史原答')),
      r.error?note(r.error):null,e('div',{class:'pg-lanes'},r.lanes.map(l=>laneCard(l,r))),
      r.release?details('本次使用的运行版本',r.release):null,
      r.historical?details('历史实际回复',r.historical.map(x=>x.text)):null);
  }
  function replay(){
    return e('div',{class:'pg-layout'},sidebar(),e('div',{class:'pg-workspace'},transcript(),caseEditor(),results()));
  }
  function settingsPage(){
    const preset=select(configs(),s.a), model=select(s.info.models.map(x=>[x,x]),s.info.models[0]);
    const name=e('input',{placeholder:'为这个版本命名'}), persona=e('textarea',{rows:10}), pref=e('textarea',{rows:5});
    const load=()=>{const c=s.info.configs.find(x=>x.id===preset.value);if(c){persona.value=c.persona;pref.value=c.preferences;model.value=c.model;}};
    preset.addEventListener('change',load);load();
    return e('div',{class:'pg-config'},block('新建配置版本',e('div',{class:'pg-pair'},label('从版本复制',preset),label('生成模型',model)),
      label('版本名称',name),label('实验角色提示',persona),label('群聊参与偏好',pref),
      button('保存新版本',async()=>{const created=await api('configs',{name:name.value,model:model.value,persona:persona.value,preferences:pref.value});
        s.b=created.id;await info();draw();message('已保存新版本，并设为对照配置 B。');},'pg-primary')),
      block('评分人设',note('该人设供独立裁判使用，所有实录配置共用。'),e('p',{class:'pg-body'},s.info.fixedPersona)));
  }
  function batchPage(){
    const name=e('input',{placeholder:'例如：群聊参与基线'}), checks=[];
    const entries=s.info.cases.map(c=>{const cb=e('input',{type:'checkbox',value:c.id});checks.push(cb);
      return e('label',{class:'pg-check'},cb,c.name,e('small',{},c.split==='dev'?'调试':'回归'));});
    const start=async()=>{const result=await api('batches',{name:name.value,cases:checks.filter(x=>x.checked).map(x=>x.value),configs:[s.a,s.b]});
      await info();s.report=await api('batches/'+result.id);draw();};
    const records=s.info.batches.map(b=>button(b.name+' · '+shortTime(b.created),async()=>{await openReport(b.id);},'pg-link'));
    const screen=e('div',{class:'pg-config'},block('固定回归批次',label('批次名称',name),...entries,picks(),
      button('运行所选案例',start,'pg-primary')),block('批次报告',...records));
    if(s.report){
      const r=s.report,summary=r.overall;
      const inspect=async key=>{const ids=summary.links[key]||[];const list=host.querySelector('#pg-drill');
        list.replaceChildren(...r.items.filter(x=>ids.includes(x.id)).map(x=>button(x.case+' / '+x.side,()=>openRun(x.run),'pg-link')));
        if(!list.children.length)list.append(note('此项没有案例。'));};
      const stats=[['误插话','unwantedTotal',summary.participation.unwanted.count+'/'+summary.participation.unwanted.total],
        ['漏接话','missedTotal',summary.participation.missed.count+'/'+summary.participation.missed.total],
        ['两者均可','either',summary.links.either?.length||0],['待人工定标','unlabelled',summary.links.unlabelled?.length||0],
        ['点名应答','direct',(summary.policies?.direct.passed||0)+'/'+(summary.policies?.direct.total||0)],
        ['策略静默','observe',(summary.policies?.observe.passed||0)+'/'+(summary.policies?.observe.total||0)],
        ['群聊回复通过','group',summary.group.passed+'/'+summary.group.total],['私聊回复通过','private',summary.private.passed+'/'+summary.private.total],
        ['待复核','pending',summary.links.pending.length],['运行失败','failed',summary.links.failed.length]];
      const comparison=Object.entries(r.byConfig).map(([id,counts])=>e('article',{class:'pg-lane'},
        e('h4',{},s.info.configs.find(c=>c.id===id)?.name||id),
        e('p',{},'群聊回复　'+counts.group.passed+' / '+counts.group.total+' 通过'),
        e('p',{},'私聊回复　'+counts.private.passed+' / '+counts.private.total+' 通过'),
        note('误插话 '+counts.participation.unwanted.count+'/'+counts.participation.unwanted.total+
          '　漏接话 '+counts.participation.missed.count+'/'+counts.participation.missed.total)));
      screen.append(block(r.name,note(r.cases.length+' 个固定案例 · '+r.items.length+' / '+(r.cases.length*2)+' 个分支已有结果'),
        e('div',{class:'pg-metrics'},stats.map(([title,key,val])=>button(title+'　'+val,()=>inspect(key)))),
        note('误插话、漏接话只计有明确人工参与标签的普通群消息。回复须同时通过人设和合理性两项。'),
        e('div',{id:'pg-drill'}),e('h4',{},'按配置比较'),e('div',{class:'pg-lanes'},comparison),e('div',{class:'pg-row'},
          button('刷新报告',async()=>{s.report=await api('batches/'+r.id);draw();}),
          button('下载批次报告',()=>download('dialogue-'+r.id+'.json',r)),
          button('用所选配置重跑',async()=>{const x=await api('batches/'+r.id+'/repeat',{configs:[s.a,s.b]});
            await info();s.report=await api('batches/'+x.id);draw();})),
        ...r.items.map(x=>button(x.case+' / '+x.side+' · '+nice(x.decision||x.status),()=>openRun(x.run),'pg-link'))));
    }
    return screen;
  }
  function download(filename,val){
    const url=URL.createObjectURL(new Blob([JSON.stringify(val,null,2)],{type:'application/json'}));
    const a=e('a',{href:url,download:filename});a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
  }
  function benchmarkOutputs(id){
    const list=e('div'), item=e('details',{},e('summary',{},'查看原角色与生成回复'),list);
    // 公开基准可能含数千道题。按需读取，并分页展示题目、生成结果及分配的指标。
    item.addEventListener('toggle',()=>{if(!item.open||list.childNodes.length)return;
      list.append(note('正在读取生成记录…'));
      (async()=>{const data=await api('benchmarks/'+id+'/export'), rows=JSON.parse(data.generationUtf8);
        function page(start){
          list.replaceChildren(note('数据版本 '+data.job.sourceRevision.slice(0,12)+' · 权重版本 '+data.job.weightsRevision.slice(0,12)),
            ...rows.slice(start,start+12).map(row=>e('details',{},e('summary',{},row.role+' · '+row.id+' · '+
              data.job.expectedIds[row.id].join(' / ')),e('p',{class:'pg-body'},row.context),
              e('p',{class:'pg-answer'},row.model_output))));
          if(start>0)list.append(button('上一页',()=>page(start-12)));
          if(start+12<rows.length)list.append(button('下一页',()=>page(start+12)));
        }
        page(0);
      })().catch(err=>list.replaceChildren(note(err.message)));
    });
    return item;
  }
  function benchPage(){
    const model=select(s.info.models.map(x=>[x,x]),s.info.models[0]);
    const limit=e('input',{type:'number',min:'1',value:'3'});
    const mode=select([['sample','固定抽样'],['full','完整测试集']],'sample',v=>{limit.disabled=v==='full';});
    const jobs=s.info.benchmarks.map(b=>{
      const file=e('input',{type:'file',accept:'.json,application/json','aria-label':'官方评分回填文件'});
      return e('article',{class:'pg-job'},e('h4',{},b.model+' · '+nice(b.status)),
        note(shortTime(b.created)+'　'+(b.kind==='full'?'完整测试':'抽样')+'　'+(b.count??'—')+' 题'),
        b.error?note(b.error):null,b.report?e('div',{},e('h4',{},'逐指标评分'),
          ...Object.entries(b.report.metrics).map(([metric,x])=>e('p',{},metric+'　'+x.mean.toFixed(2)+' / 5　·　'+x.count+' 项'))):null,
        ['complete','awaiting_scores'].includes(b.status)?benchmarkOutputs(b.id):null,
        ['complete','awaiting_scores'].includes(b.status)?e('div',{class:'pg-row'},
          button('下载待评分任务',async()=>download('charactereval-'+b.id+'.json',await api('benchmarks/'+b.id+'/export'))),
          e('details',{},e('summary',{},'回填官方评分'),file,button('导入评分',async()=>{
            if(!file.files[0])throw Error('请选择官方评分输出文件');
            await api('benchmarks/'+b.id+'/scores',JSON.parse(await file.files[0].text()));await info();draw();}))):null);});
    return e('div',{class:'pg-config'},block('CharacterEval',note('按题目原角色生成中文回复，使用官方 CharacterRM 逐项评分。'),
      label('生成模型',model),e('div',{class:'pg-pair'},label('测试范围',mode),label('抽样题数',limit)),
      button('开始公开评测',async()=>{await api('benchmarks',{model:model.value,limit:mode.value==='full'?null:Number(limit.value)});
        await info();draw();},'pg-primary')),block('公开评测记录',button('刷新',async()=>{await info();draw();}),...jobs));
  }
  async function openReport(id){
    s.page='回归批次';s.report=await api('batches/'+id);draw();
    requestAnimationFrame(()=>host.querySelector('#pg-drill')?.closest('section')?.scrollIntoView({block:'start'}));
  }
  // 报告链接只携带实验 ID，刷新或从外部打开时重新走同源接口读取内容。
  return {open:async()=>{const hash=location.hash,token=++s.generation;draw();await action(async()=>{
    await info();await loadRooms();
    if(token!==s.generation || hash!==location.hash)return;
    const [,kind,id]=hash.split('/');
    if(kind==='batch' && id)await openReport(id);
    else if(kind==='run' && id)await openRun(id);
    else if(kind==='benchmarks'){s.page='公开评测';draw();}
    else {s.page='回放';draw();}
  });}};
})();
