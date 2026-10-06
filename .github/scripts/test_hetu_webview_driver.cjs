const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict'),path=require('node:path');
const source=fs.readFileSync(path.join(__dirname,'check_hetu_webview.cjs'),'utf8');
async function test(action,label,status=200){
 const output=[],errors=[],commands=[],connections=[];const process={argv:['node','driver','32768',action,...(label?[label]:[])],exitCode:0};
 class Socket{
  constructor(url){connections.push(url);setTimeout(()=>this.onopen(),0)}
  send(raw){const message=JSON.parse(raw);commands.push(message);this.onmessage({data:JSON.stringify({id:message.id,result:{result:{value:action==='read'?{text:'actual fixture DOM',metricCount:4}:{clicked:label}}}})})}
  close(){}
 }
 const context={process,console:{log:x=>output.push(JSON.parse(x)),error:x=>errors.push(x)},WebSocket:Socket,URL,AbortSignal,setTimeout,clearTimeout,Date,
  fetch:async url=>{assert.equal(url,'http://127.0.0.1:32768/json/list');return{ok:status===200,status,json:async()=>[{type:'page',title:'河图 WebUI',url:'http://127.0.0.1:29090/',id:'fixture-target',description:'{"visible":true}',webSocketDebuggerUrl:'ws://untrusted.invalid:9999/devtools/page/fixture-target'}]}}};
 await vm.runInNewContext(source,context);
 return{output,errors,commands,connections,process};
}
(async()=>{
 let r=await test('read');assert.equal(r.process.exitCode,0);assert.equal(r.output[0].value.metricCount,4);assert.equal(r.output[0].source,'actual Android WebView CDP');assert.deepEqual(r.connections,['ws://127.0.0.1:32768/devtools/page/fixture-target']);assert.equal(r.commands[0].method,'Runtime.evaluate');assert(r.commands[0].params.expression.includes('document.baseURI'));assert(r.commands[0].params.expression.includes('requestAnimationFrame(()=>requestAnimationFrame(resolve))'));assert.equal(r.commands[0].params.awaitPromise,true);
 r=await test('click','香港 "02"');assert.equal(r.output[0].value.clicked,'香港 "02"');assert(r.commands[0].params.expression.includes(JSON.stringify('香港 "02"')));assert(r.commands[0].params.expression.includes('nodes[0].click()'));
 r=await test('read',null,403);assert.equal(r.process.exitCode,1);assert.equal(r.connections.length,0);assert(r.errors.some(x=>x.includes('access denied')));
 console.log('3 WebView driver protocol checks passed (host mocks only; native execution still required)');
})().catch(e=>{console.error(e);process.exitCode=1});
