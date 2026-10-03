// Inspect the existing debug APK WebView. Never changes debug flags or page data.
const port=Number(process.argv[2]),action=process.argv[3]||'read',label=process.argv[4];
if(!Number.isInteger(port)||port<1024||port>65535||!['read','click','locate'].includes(action))throw Error('Invalid local WebView command');
if(typeof WebSocket!=='function')throw Error('Node24 WebSocket support required');
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
(async()=>{
 let target,lastError='No visible page';
 const deadline=Date.now()+20000;
 while(Date.now()<deadline){
  let targets;
  try{targets=await fetch(`http://127.0.0.1:${port}/json/list`,{signal:AbortSignal.timeout(5000)}).then(r=>{if([401,403].includes(r.status)){const e=Error('CDP access denied '+r.status);e.accessDenied=true;throw e}if(!r.ok)throw Error('CDP target list '+r.status);return r.json()})}
  catch(error){if(error.accessDenied)throw error;lastError=error.message;await sleep(250);continue}
  const matches=targets.filter(t=>t.type==='page'&&(String(t.url).startsWith('http://127.0.0.1:29090/')||t.title==='河图 WebUI')).filter(t=>{try{return JSON.parse(t.description||'{}').visible!==false}catch{return true}});
  if(matches.length===1){target=matches[0];break}
  await sleep(250);
 }
 if(!target)throw Error('No unique visible built-in loopback WebView target: '+lastError);
 const source=new URL(target.webSocketDebuggerUrl);
 if(!source.pathname.startsWith('/devtools/page/'))throw Error('Unexpected DevTools target path');
 const socket=new WebSocket(`ws://127.0.0.1:${port}${source.pathname}${source.search}`);
 let nextId=0;const pending=new Map();
 socket.onmessage=event=>{const message=JSON.parse(String(event.data));if(message.id&&pending.has(message.id)){const item=pending.get(message.id);clearTimeout(item.timer);pending.delete(message.id);message.error?item.reject(Error(JSON.stringify(message.error))):item.resolve(message.result)}};
 const request=(method,params={})=>new Promise((resolve,reject)=>{const id=++nextId;const timer=setTimeout(()=>{pending.delete(id);reject(Error('CDP timeout '+method))},15000);pending.set(id,{resolve,reject,timer});socket.send(JSON.stringify({id,method,params}))});
 try{
  await new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(Error('WebView debug socket timeout')),10000);socket.onopen=()=>{clearTimeout(timer);resolve()};socket.onerror=()=>{clearTimeout(timer);reject(Error('WebView debug socket error'))}});
  const locate=`(()=>{if(!document.baseURI.startsWith('http://127.0.0.1:29090/'))throw Error('Unexpected WebView base URI');const wanted=${JSON.stringify(label)};const nodes=[...document.querySelectorAll('button')].filter(e=>e.getClientRects().length&&(e.getAttribute('aria-label')||e.innerText.trim())===wanted);if(nodes.length!==1)throw Error('Expected one visible button '+wanted+', found '+nodes.length);if(nodes[0].disabled)throw Error('Button disabled '+wanted);const r=nodes[0].getBoundingClientRect();return {located:wanted,x:r.left+r.width/2,y:r.top+r.height/2,viewportWidth:innerWidth,viewportHeight:innerHeight}})()`;
  const expression=action==='locate'?locate:action==='click'?`(()=>{if(!document.baseURI.startsWith('http://127.0.0.1:29090/'))throw Error('Unexpected WebView base URI');const wanted=${JSON.stringify(label)};const nodes=[...document.querySelectorAll('button')].filter(e=>e.getClientRects().length&&(e.getAttribute('aria-label')||e.innerText.trim())===wanted);if(nodes.length!==1)throw Error('Expected one visible button '+wanted+', found '+nodes.length);if(nodes[0].disabled)throw Error('Button disabled '+wanted);nodes[0].click();return {clicked:wanted}})()`:`(async()=>{if(!document.baseURI.startsWith('http://127.0.0.1:29090/'))throw Error('Unexpected WebView base URI');await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));return {text:document.body.innerText,status:document.querySelector('#status-label')?.textContent,metricCount:document.querySelectorAll('.metric').length,groupCount:document.querySelectorAll('.group').length,connectionCount:document.querySelectorAll('.connection').length,firstSelected:document.querySelector('.pill')?.textContent,nodeDialogOpen:!!document.querySelector('#node-dialog')?.open,noticeOpen:!!document.querySelector('#notice')?.open,noticeMessage:document.querySelector('#notice-message')?.textContent}})()`;
  const result=await request('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});
  if(result.exceptionDetails)throw Error(result.exceptionDetails.exception?.description||result.exceptionDetails.text);
  console.log(JSON.stringify({source:'actual Android WebView CDP',targetId:target.id,action,value:result.result.value}));
 }finally{for(const item of pending.values())clearTimeout(item.timer);socket.close()}
})().catch(error=>{console.error(error.stack||String(error));process.exitCode=1});
