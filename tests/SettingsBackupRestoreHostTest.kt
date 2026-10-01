package io.github.xgl34222220.hetu
import host.HostContext
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.io.File
import java.io.IOException
import java.util.concurrent.*
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.runBlocking
import org.json.*
private val core = ProxyRuntimeProfile.Core.MIHOMO
private var checks=0
private val root by lazy { Files.createTempDirectory("hetu-backup-tests-").toFile() }
private fun fixture(name:String)=HostContext(File(root,name))
private fun item(name:String,data:String="rules: []\n",id:String="mihomo")=JSONObject().put("core",id).put("name",name).put("data",Base64.encodeToString(data.toByteArray(),0))
private fun setting(t:String,v:Any)=JSONObject().put("t",t).put("v",v)
private fun settings(vararg pairs:Pair<String,JSONObject>)=JSONObject().apply{pairs.forEach{(k,v)->put(k,v)}}
private fun document(s:JSONObject=JSONObject(),c:JSONArray=JSONArray())=JSONObject().put("schema",1).put("settings",s).put("configs",c)
private fun restore(c:HostContext,s:JSONObject=JSONObject(),a:JSONArray=JSONArray())=restoreRaw(c,document(s,a).toString().toByteArray())
private fun restoreRaw(c:HostContext,bytes:ByteArray):Result<Int> = runCatching {
 val f=File(c.files,"backup.json");f.writeBytes(bytes);runBlocking{HetuSettingsBackup.restore(c,Uri.fromFile(f))}
}
private fun verify(ok:Boolean,label:String){check(ok){label};checks++}
private fun file(c:HostContext,name:String)=File(c.files,"hetu/configs/mihomo/$name")
private fun selected(c:HostContext)=c.prefs.getString("proxySelectedConfig.mihomo","")
private fun imported(c:HostContext,name:String,text:String="original"):ProxyConfigLibrary.Entry=ProxyConfigLibrary(c).importConfig(core,name,text.byteInputStream())
private fun cleanStaging(c:HostContext)=File(c.files,"hetu/configs").listFiles()?.none{it.name.startsWith(".restore-")} ?: true
private fun rejectUnchanged(label:String,doc:JSONObject){
 val c=fixture(label);val old=imported(c,"old.yaml");c.prefs.edit().putBoolean("enableBlur",false).putString("unrelated","keep").commit();val before=c.prefs.all
 verify(restoreRaw(c,doc.toString().toByteArray()).isFailure,"$label rejected")
 verify(old.file.readText()=="original" && c.prefs.all==before,"$label leaves all state unchanged")
 verify(!file(c,"new.yaml").exists(),"$label never installs earlier entry")
}
fun main(args: Array<String>){
 if(args.firstOrNull()=="crash-before-settings"){
  val context=HostContext(File(args[1]))
  ProxyConfigLibrary(context).restoreBatch(listOf(ProxyConfigLibrary.RestoreItem(core,"first.yaml","restored".toByteArray())),mapOf(core to "first.yaml")){Runtime.getRuntime().halt(73)}
  error("crash injection was not reached")
 }
 try {
 val selection=fixture("selection");imported(selection,"previous.yaml")
 val s=settings("proxySelectedConfig.mihomo" to setting("s","first.yaml"))
 verify(restore(selection,s,JSONArray().put(item("first.yaml")).put(item("last.yaml"))).getOrThrow()==2,"count")
 verify(selected(selection)=="first.yaml","intended selection survives import order")
 val collision=fixture("collision");val original=imported(collision,"first.yaml","old")
 verify(restore(collision,s,JSONArray().put(item("first.yaml","restored")).put(item("last.yaml","last"))).getOrThrow()==2,"collision count")
 verify(original.file.readText()=="old" && file(collision,"first (2).yaml").readText()=="restored","collision preserves both")
 verify(selected(collision)=="first (2).yaml","selection remapped")
 restore(collision,s,JSONArray().put(item("first.yaml","restored")).put(item("last.yaml","last"))).getOrThrow()
 verify(!file(collision,"first (3).yaml").exists(),"repeat restore reuses exact prior copy")
 val identical=fixture("identical");val same=imported(identical,"same.yaml","same");val key=Files.readAttributes(same.file.toPath(),"unix:ino")["ino"]
 restore(identical,JSONObject(),JSONArray().put(item("same.yaml","same"))).getOrThrow()
 verify(Files.readAttributes(same.file.toPath(),"unix:ino")["ino"]==key&&!file(identical,"same (2).yaml").exists(),"identical original is not rewritten")
 verify(selected(identical)=="same.yaml","absent backup selection preserves current")
 val base=JSONArray().put(item("new.yaml"))
 val invalid=listOf(
  "extension" to item("bad.json"),"path" to item("../bad.yaml"),"trimmed" to item(" bad.yaml "),
  "unknown-core" to item("bad.yaml",id="unknown"),"base64" to item("bad.yaml").put("data","%%%%"),
  "empty" to item("bad.yaml", ""),"blank" to item("bad.yaml"," \n"),
  "utf8" to item("bad.yaml").put("data",Base64.encodeToString(byteArrayOf(0xc3.toByte(),0x28),0)),
  "oversize" to item("bad.yaml").put("data",Base64.encodeToString(ByteArray(4*1024*1024+1){65},0)),
  "duplicate" to item("new.yaml","different"),"bad-entry" to "bad"
 )
 for((label,entry) in invalid)rejectUnchanged(label,document(settings("enableBlur" to setting("b",true)),JSONArray(base.toString()).put(entry)))
 for((label,value) in listOf("bool-string" to setting("b","false"),"int-overflow" to setting("i",Long.MAX_VALUE),"float-overflow" to setting("f",1e100),"unknown-type" to setting("q","x"),"bad-set" to setting("ss",JSONArray().put(3)),"number-string" to setting("i","2"))){
  rejectUnchanged(label,document(settings("enableBlur" to value),base))
 }
 rejectUnchanged("missing-selection",document(s,base))
 rejectUnchanged("wrong-settings-container",document().put("settings",JSONArray()))
 rejectUnchanged("wrong-config-container",document().put("configs",JSONObject()))
 rejectUnchanged("schema-string",document().put("schema","1"))
 val malformed=fixture("malformed-raw");verify(restoreRaw(malformed,byteArrayOf(0xc3.toByte(),0x28)).isFailure&&!File(malformed.files,"hetu").exists(),"invalid document UTF8 rejected before library")
 verify(restoreRaw(malformed,ByteArray(16*1024*1024+1){65}).isFailure,"16MiB document limit")
 val typed=fixture("typed");typed.prefs.edit().putString("proxyCustomApiSecret","keep-secret").putString("unrelated","keep").commit()
 val typedSettings=settings("enableBlur" to setting("b",true),"uiScale" to setting("f",1.25),"latencyAutoRefreshSeconds" to setting("i",3),"proxyStatusTime" to setting("l",Long.MAX_VALUE),"proxyAppList" to setting("ss",JSONArray().put("").put("one")),"appearance" to setting("s","dark"),"proxyCustomApiSecret" to setting("s","do-not-import"),"unrelated" to setting("s","drop"))
 restore(typed,typedSettings).getOrThrow();verify(typed.prefs.all["uiScale"]==1.25f && typed.prefs.all["proxyStatusTime"]==Long.MAX_VALUE && typed.prefs.all["proxyAppList"]==setOf("","one"),"exact preference types")
 verify(typed.prefs.getString("proxyCustomApiSecret","")=="keep-secret" && typed.prefs.getString("unrelated","")=="keep","excluded/unrelated prefs preserved")
 val failed=fixture("commit-failure");val old=imported(failed,"first.yaml");val before=failed.prefs.all;failed.prefs.failures=1
 verify(restore(failed,s,JSONArray().put(item("first.yaml","restored"))).isFailure,"failed settings commit surfaced")
 verify(failed.prefs.all==before && old.file.readText()=="original"&&!file(failed,"first (2).yaml").exists()&&cleanStaging(failed),"confirmed rollback restores affected prefs and removes own copies")
 val threw=fixture("commit-throws");imported(threw,"first.yaml");var firstCommit=true
 threw.prefs.onCommit=Runnable{if(firstCommit){firstCommit=false;throw IllegalStateException("synthetic disk failure")}}
 verify(restore(threw,s,JSONArray().put(item("first.yaml","restored"))).isFailure&&!file(threw,"first (2).yaml").exists()&&selected(threw)=="first.yaml","thrown settings commit rolls back own changes")
 val concurrent=fixture("concurrent-preference");concurrent.prefs.edit().putString("appearance","system").putString("unrelated","old").commit();concurrent.prefs.failures=1
 var newer=false;concurrent.prefs.onCommit=Runnable{if(!newer){newer=true;concurrent.prefs.data["appearance"]="light";concurrent.prefs.data["unrelated"]="newer"}}
 verify(restore(concurrent,settings("appearance" to setting("s","dark")),base).isFailure&&concurrent.prefs.getString("appearance","")=="light"&&concurrent.prefs.getString("unrelated","")=="newer","rollback preserves newer visible preference values")
 val uncertain=fixture("uncertain");imported(uncertain,"first.yaml");uncertain.prefs.failCommit=true
 val failure=restore(uncertain,s,JSONArray().put(item("first.yaml","restored"))).exceptionOrNull()
 verify(failure is ProxyConfigLibrary.RestoreCommitUncertain && failure.message!!.contains("未能确认"),"unconfirmed preference rollback explicit")
 verify(file(uncertain,"first.yaml").readText()=="original"&&file(uncertain,"first (2).yaml").readText()=="restored","uncertain rollback retains originals and copies")
 for(mode in listOf("inplace","incomplete-copy","grown-file","replacement","same-content-replacement","symlink")){
  val c=fixture("changed-$mode");imported(c,"first.yaml");c.prefs.failures=1
  var changed=false
  c.prefs.onCommit=Runnable{if(!changed){changed=true;val target=file(c,"first (2).yaml");when(mode){
   "inplace"->target.writeText("concurrent change")
   "incomplete-copy"->java.io.RandomAccessFile(target,"rw").use{it.setLength(2L)}
   "grown-file"->java.io.RandomAccessFile(target,"rw").use{it.setLength(64L*1024*1024)}
   "replacement","same-content-replacement"->{val newer=File(c.files,"replacement");newer.writeText(if(mode=="replacement")"concurrent change" else "restored");Files.move(newer.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING)}
   "symlink"->{val newer=File(c.files,"replacement");newer.writeText("restored");target.delete();Files.createSymbolicLink(target.toPath(),newer.toPath())}
  }}}
  verify(restore(c,s,JSONArray().put(item("first.yaml","restored"))).isFailure,"$mode commit failure surfaced")
  verify(file(c,"first (2).yaml").exists()&&file(c,"first.yaml").readText()=="original","$mode changed copy retained")
  if(mode=="incomplete-copy")verify(file(c,"first (2).yaml").length()==2L&&selected(c)=="first.yaml","incomplete owned copy is retained conservatively and old selection survives")
  if(mode=="grown-file")verify(file(c,"first (2).yaml").length()==64L*1024*1024,"rollback retains a larger concurrent file without reading it into memory")
 }
 val staging=fixture("staging-failure");imported(staging,"old.yaml");File(staging.files,"hetu/configs/xray").writeText("directory obstruction")
 verify(restore(staging,JSONObject(),JSONArray().put(item("new.yaml")).put(item("valid.json","{}","xray"))).isFailure&&!file(staging,"new.yaml").exists()&&cleanStaging(staging),"late staging failure installs nothing")
 val racing=fixture("promotion-race");val library=ProxyConfigLibrary(racing);File(racing.files,"hetu/configs").mkdirs()
 val watcher=File(racing.files,"hetu/configs").toPath().fileSystem.newWatchService();File(racing.files,"hetu/configs").toPath().register(watcher,java.nio.file.StandardWatchEventKinds.ENTRY_CREATE)
 val inserted=CompletableFuture<Boolean>();val racer=Thread{try{while(true){val eventKey=watcher.poll(5,TimeUnit.SECONDS)?:error("no staging");val staged=eventKey.pollEvents().any{it.context().toString().startsWith(".restore-")};eventKey.reset();if(staged){file(racing,"reserved.yaml").parentFile.mkdirs();Files.writeString(file(racing,"reserved.yaml").toPath(),"concurrent owner",java.nio.file.StandardOpenOption.CREATE_NEW);inserted.complete(true);break}}}catch(t:Throwable){inserted.completeExceptionally(t)}};racer.start()
 val items=(0..40).map{ProxyConfigLibrary.RestoreItem(core,if(it==40)"reserved.yaml" else "race-$it.yaml",ByteArray(256*1024){65})}
 var committed=false;val raced=runCatching{library.restoreBatch(items,emptyMap()){committed=true}}
 inserted.get(5,TimeUnit.SECONDS);racer.join();watcher.close()
 verify(raced.isFailure&&!committed&&file(racing,"reserved.yaml").readText()=="concurrent owner","no-clobber promotion retains destination race winner")
 verify(!file(racing,"race-1.yaml").exists()&&cleanStaging(racing),"late promotion failure removes earlier installed copies only")
 val lock=ProxyConfigLibrary::class.java.getDeclaredField("WRITE_LOCK").apply{isAccessible=true}.get(null)
 val done=CompletableFuture<Boolean>();val entered=CountDownLatch(1);val thread=Thread{entered.countDown();library.restoreBatch(emptyList(),emptyMap()){done.complete(true)}}
 synchronized(lock){thread.start();entered.await();val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(thread.state!=Thread.State.BLOCKED&&!done.isDone&&System.nanoTime()<end)Thread.yield();verify(thread.state==Thread.State.BLOCKED&&!done.isDone,"batch shares existing write/CAS/rename lock")};done.get(5,TimeUnit.SECONDS);thread.join()
 for((name,value) in listOf("appearance" to setting("b",true),"enableBlur" to setting("s","true"),"uiScale" to setting("i",1),"proxyCustomApiHost" to setting("i",127))){
  rejectUnchanged("known-type-$name",document(settings(name to value),base))
 }
 val dynamic=fixture("dynamic-type");dynamic.prefs.edit().putLong("proxyStatusCustomTimestamp",123L).commit();val dynamicBefore=dynamic.prefs.all
 verify(restore(dynamic,settings("proxyStatusCustomTimestamp" to setting("i",3)),base).isFailure && dynamic.prefs.all==dynamicBefore && !file(dynamic,"new.yaml").exists(),"existing dynamic key cannot change preference type")
 for((label,changes) in listOf(
  "host" to settings("proxyCustomApiHost" to setting("s","changed.example.invalid")),
  "port" to settings("proxyCustomApiPort" to setting("i",9091)),
  "disabled-host" to settings("proxyCustomApiEnabled" to setting("b",false),"proxyCustomApiHost" to setting("s","changed.example.invalid"))
 )){
  val c=fixture("binding-$label");c.prefs.edit().putBoolean("proxyCustomApiEnabled",true).putString("proxyCustomApiHost","Original.Example.Invalid").putInt("proxyCustomApiPort",9090).putString("proxyCustomApiSecret","synthetic-only-secret").commit();val before=c.prefs.all
  val error=restore(c,changes,base).exceptionOrNull()
  verify(error!=null&&error.message!!.contains("API")&&c.prefs.all==before&&!File(c.files,"hetu").exists(),"$label retained credential cannot move to another endpoint")
 }
 val equivalent=fixture("binding-equivalent");equivalent.prefs.edit().putString("proxyCustomApiHost"," Original.Example.Invalid ").putInt("proxyCustomApiPort",80).putString("proxyCustomApiSecret","synthetic-only-secret").commit()
 restore(equivalent,settings("proxyCustomApiHost" to setting("s","original.example.invalid"),"proxyCustomApiPort" to setting("i",9090),"proxyCustomApiEnabled" to setting("b",true))).getOrThrow()
 verify(equivalent.prefs.getString("proxyCustomApiSecret","")=="synthetic-only-secret","case/whitespace and defaulted port equivalent endpoint allowed")
 val unbound=fixture("binding-empty");restore(unbound,settings("proxyCustomApiHost" to setting("s","new.example.invalid"),"proxyCustomApiPort" to setting("i",9999))).getOrThrow()
 verify(unbound.prefs.getString("proxyCustomApiHost","")=="new.example.invalid","endpoint restore without existing secret remains available")
 val crash=fixture("process-crash");imported(crash,"first.yaml","original")
 val crashed=ProcessBuilder(File(System.getProperty("java.home"),"bin/java").path,"-cp",System.getProperty("java.class.path"),"io.github.xgl34222220.hetu.SettingsBackupRestoreHostTestKt","crash-before-settings",crash.files.path).inheritIO().start()
 verify(crashed.waitFor(10,TimeUnit.SECONDS)&&crashed.exitValue()==73,"separate JVM exits during actual batch before settings commit")
 verify(file(crash,"first.yaml").readText()=="original"&&file(crash,"first (2).yaml").readText()=="restored"&&!cleanStaging(crash),"process death leaves original intact and recoverable restored/staging copies")
 val export=fixture("export");imported(export,"exported.yaml","synthetic original");export.prefs.edit().putBoolean("enableBlur",true).commit();val dest=File(export.files,"export.json")
 val count=runBlocking{HetuSettingsBackup.export(export,Uri.fromFile(dest))};val imported=fixture("roundtrip");val restored=restoreRaw(imported,dest.readBytes()).getOrThrow()
 verify(count==restored&&selected(imported)=="exported.yaml"&&file(imported,"exported.yaml").readText()=="synthetic original","actual export/restore roundtrip")
 println("SettingsBackupRestoreTest passed: $checks checks; actual production export/restore/library with synthetic temporary files")
} finally {root.deleteRecursively()}}
