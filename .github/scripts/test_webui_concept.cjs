const fs=require('fs'),vm=require('vm'),assert=require('node:assert/strict');
(async()=>{
 const s=fs.readFileSync('android-app/app/src/main/java/io/github/xgl34222220/hetu/ProxyLocalWebUiActivity.kt','utf8');
 const html=s.split('val WEB_UI = """')[1].split('""".trimIndent()')[0];const js=html.split('<script>')[1].split('</script>')[0].replace('__SECRET_JSON__','"test-only-secret"');
 const els={};class El{constructor(){this.innerHTML='';this.textContent='';this.open=false;this.classList={toggle(){}};}querySelectorAll(){return []}showModal(){this.open=true}close(){this.open=false}}
 const document={hidden:false,getElementById:id=>els[id]||(els[id]=new El()),querySelectorAll:()=>[]};
 let failGet=false,failPut=false,ignorePut=false,putCount=0,requests=[],interval;
 const group='Test <Group>',node='Node <B>';
 const data={version:{version:'v-test'},proxies:{proxies:{[group]:{type:'Selector',now:'Node A',all:['Node A',node]}}},connections:{uploadTotal:1024,downloadTotal:1048576,connections:[{metadata:{host:'host.test'},chains:[group,'Node A'],upload:2048,download:4096}]}};
 const fetch=async(path,opts)=>{
  requests.push({path,method:opts.method||'GET',authorization:opts.headers.Authorization});
  if(opts.method==='PUT'){
   putCount++;assert.equal(path,'/proxies/'+encodeURIComponent(group));if(failPut)return {ok:false,status:503};
   if(!ignorePut)data.proxies.proxies[group].now=JSON.parse(opts.body).name;
   return {ok:true,status:204};
  }
  if(failGet)return {ok:false,status:503};const body=data[path.slice(1)];assert(body,'Unexpected request '+path);
  return {ok:true,status:200,json:async()=>JSON.parse(JSON.stringify(body))};
 };
 const ctx=vm.createContext({document,fetch,setInterval:(cb)=>interval=cb});vm.runInContext(js,ctx);await vm.runInContext('refresh()',ctx);
 const results=[];const pass=name=>results.push({name,result:'passed'}),run=expr=>vm.runInContext(expr,ctx),content=()=>els.content.innerHTML;
 assert.equal((content().match(/card metric/g)||[]).length,4);assert(content().includes('Mihomo v-test'));assert(content().includes('↑ 1 KB · ↓ 1 MB'));pass('Overview generator renders four response-derived metrics and traffic');
 run('current="proxies";render()');assert(content().includes('Test &lt;Group&gt;'));assert(content().includes('Selector · 2 节点'));assert(!content().includes('Test <Group>'));pass('Group names escape markup and use actual type/count/selection');
 run('openNodes(...groups()[0])');assert.equal(els['node-dialog'].open,true);assert(els['node-options'].innerHTML.includes('Node &lt;B&gt;'));assert(els['node-options'].innerHTML.includes('aria-checked="true"'));els['cancel-node'].onclick();assert.equal(els['node-dialog'].open,false);assert.equal(putCount,0);pass('Dialog renderer escapes names, marks current radio, and cancellation sends no request');
 failPut=true;await run(`selectNode(${JSON.stringify(group)},${JSON.stringify(node)})`);assert.equal(els['notice-message'].textContent,'切换失败：HTTP 503');assert(content().includes('>Node A</span>'));assert.equal(run('pendingGroup'),null);els['confirm-notice'].onclick();assert(!els.notice.open);pass('Rejected PUT opens error notice and retains old selection; dismiss resets notice');
 failPut=false;await run(`selectNode(${JSON.stringify(group)},${JSON.stringify(node)})`);assert(content().includes('>Node &lt;B&gt;</span>'));assert.equal(data.proxies.proxies[group].now,node);assert.equal(els.notice.open,false);pass('Successful authenticated PUT rereads controller and updates selected node');
 ignorePut=true;await run(`selectNode(${JSON.stringify(group)},"Node A")`);assert.equal(els['notice-message'].textContent,'切换失败：控制器尚未确认所选节点');assert(content().includes('>Node &lt;B&gt;</span>'));ignorePut=false;pass('Unconfirmed 204 cannot invent a successful selection');
 const before=putCount;run(`pendingGroup=${JSON.stringify(group)}`);await run(`selectNode(${JSON.stringify(group)},"Node A")`);assert.equal(putCount,before);run('pendingGroup=null');pass('Pending selection blocks repeated writes');
 run('current="connections";render()');assert(content().includes('host.test'));assert(content().includes('Test &lt;Group&gt; → Node A'));assert(content().includes('class="up"><i>↑</i>2 KB'));assert(content().includes('class="down"><i>↓</i>4 KB'));pass('Connection generator preserves host/chain and separate directional counters');
 failGet=true;await run('refresh()');assert(content().includes('控制器未连接：HTTP 503'));run('current="overview";render()');assert(!content().includes('card metric'));assert.equal(els['status-label'].textContent,'未连接');pass('Disconnected refresh and tab changes cannot fabricate zero metrics or stale connected state');
 failGet=false;await run('refresh()');assert(content().includes('Mihomo v-test'));assert.equal(els['status-label'].textContent,'已连接');pass('A successful retry restores real cached data');
 assert.equal(run('fmt(null)'),'—');assert.equal(run('fmt(undefined)'),'—');assert.equal(run('fmt("invalid")'),'—');assert.equal(run('fmt(0)'),'0 B');pass('Missing and invalid traffic remain unknown while actual zero stays zero');
 assert(requests.every(r=>r.authorization==='Bearer test-only-secret'));assert(requests.every(r=>/^\/(version|proxies|connections)(\/|$)/.test(r.path)));assert(!/https?:\/\//.test(js));pass('Every API call retains bearer secret and only existing loopback relative endpoints are used');
 const requestCount=requests.length;document.hidden=true;interval();await Promise.resolve();assert.equal(requests.length,requestCount);pass('Background polling is paused when the document is hidden');
 fs.mkdirSync('out/verification',{recursive:true});const output={results,passed:results.length,putCount,requestCount:requests.length,fixtureOnly:true,testKind:'Isolated Node VM with minimal DOM and intercepted API fixture',limitations:'Logic/generator checks only. Chromium launch blocked by environment socket restriction; no rendered browser screenshots and no Android WebView/Mihomo device validation.'};fs.writeFileSync('out/verification/webui-tests.json',JSON.stringify(output,null,2));console.log(JSON.stringify(output,null,2));
})().catch(error=>{console.error(error);process.exit(1)});
