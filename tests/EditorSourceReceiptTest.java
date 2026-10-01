package io.github.xgl34222220.hetu;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Production on-disk editor commit and apply receipts, with no Root or network. */
public final class EditorSourceReceiptTest {
    private static final ProxyRuntimeProfile.Core CORE=ProxyRuntimeProfile.Core.MIHOMO;
    private static final String SOURCE="mixed-port: 7890\nrules: [MATCH,DIRECT]\n";
    private static int checks;
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;}
    private interface Action{void run()throws Exception;}
    private static void reject(Action action,String label)throws Exception{
        try{action.run();throw new AssertionError(label);}catch(IOException expected){checks++;}
    }
    private static final class Prefs implements SharedPreferences{
        final Map<String,String> values=new ConcurrentHashMap<>();
        public String getString(String key,String fallback){return values.getOrDefault(key,fallback);}
        public Editor edit(){return new Editor(){
            final Map<String,String> updates=new HashMap<>();final Set<String> removes=new HashSet<>();
            public Editor putString(String key,String value){updates.put(key,value);return this;}
            public Editor remove(String key){removes.add(key);return this;}
            public void apply(){removes.forEach(values::remove);values.putAll(updates);}
        };}
    }
    private static final class Host extends Context{
        final File files;final Prefs prefs=new Prefs();
        Host(File files){this.files=files;files.mkdirs();}
        public File getFilesDir(){return files;}
        public SharedPreferences getSharedPreferences(String name,int mode){return prefs;}
    }
    private static ProxyConfigLibrary.Entry create(ProxyConfigLibrary library,String name)throws Exception{
        return library.importConfig(CORE,name,new ByteArrayInputStream(SOURCE.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    private static ConfigEditSnapshot snapshot(ProxyConfigLibrary library,ProxyConfigLibrary.Entry entry)throws Exception{
        return new ConfigEditSnapshot(entry.core.id,entry.name,library.read(entry));
    }
    public static void main(String[] args)throws Exception{
        Host host=new Host(new File(args[0]));ProxyConfigLibrary library=new ProxyConfigLibrary(host);
        ProxyConfigLibrary.Entry a=create(library,"a.yaml");
        ConfigEditSnapshot opened=snapshot(library,a);
        String updated="# editor save\n"+SOURCE;
        ProxyConfigLibrary.SourceVersion receipt=library.writeCurrentIfUnchanged(opened,updated);
        check(receipt.matches(CORE.id,a.name,updated),"receipt binds committed source bytes");
        check(ProxyConfigLibrary.SourceVersion.restored(receipt.coreId,receipt.name,receipt.sha256).matches(CORE.id,a.name,updated),"receipt can be restored after process recreation");
        check(library.read(a).equals(updated),"save committed exact bytes");
        try(ProxyConfigLibrary.SourceApplication source=library.beginSourceApplication(receipt)){
            check(source.entry.name.equals(a.name)&&source.text.equals(updated),"apply resolves saved source");
        }
        reject(()->library.writeCurrentIfUnchanged(opened,SOURCE),"stale editor overwrote new contents");
        check(library.read(a).equals(updated),"stale rejection preserves file");
        ProxyConfigLibrary.Entry b=create(library,"b.yaml");
        ConfigEditSnapshot savedA=snapshot(library,a);
        reject(()->library.writeCurrentIfUnchanged(savedA,"# wrong selection\n"+updated),"editor wrote unselected A");
        reject(()->library.beginSourceApplication(receipt),"saved A applied current B");
        check(library.read(a).equals(updated)&&library.read(b).equals(SOURCE),"switch preserves both files");

        library.select(CORE,a.name);
        ConfigEditSnapshot race=snapshot(library,a);
        java.lang.reflect.Field field=ProxyConfigLibrary.class.getDeclaredField("WRITE_LOCK");field.setAccessible(true);Object lock=field.get(null);
        CountDownLatch started=new CountDownLatch(1);
        FutureTask<Void> worker=new FutureTask<>(()->{started.countDown();reject(()->library.writeCurrentIfUnchanged(race,"# raced\n"+updated),"late selection change bypassed commit check");return null;});
        Thread thread=new Thread(worker,"editor-commit-selection-race");
        synchronized(lock){
            thread.start();check(started.await(2,TimeUnit.SECONDS),"commit thread started");
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(thread.getState()!=Thread.State.BLOCKED&&!worker.isDone()&&System.nanoTime()<deadline)Thread.yield();
            check(thread.getState()==Thread.State.BLOCKED&&!worker.isDone(),"commit shares writer lock");
            library.select(CORE,b.name);
        }
        worker.get(5,TimeUnit.SECONDS);
        check(library.read(a).equals(updated)&&library.read(b).equals(SOURCE),"race preserves both sources");
        library.select(CORE,a.name);
        host.prefs.edit().putString("proxyBaseCore","mihomo-smart").apply();
        reject(()->library.writeCurrentIfUnchanged(savedA,SOURCE),"changed core accepted");
        host.prefs.edit().putString("proxyBaseCore",CORE.id).apply();
        reject(()->library.writeCurrentIfUnchanged(null,SOURCE),"missing snapshot accepted");
        reject(()->library.writeCurrentIfUnchanged(new ConfigEditSnapshot("unknown",a.name,updated),SOURCE),"unknown core accepted");
        reject(()->library.writeCurrentIfUnchanged(new ConfigEditSnapshot(CORE.id,"../a.yaml",updated),SOURCE),"unsafe name accepted");
        reject(()->library.writeCurrentIfUnchanged(savedA,""),"empty replacement accepted");
        reject(()->library.writeCurrentIfUnchanged(savedA,"#".repeat(4*1024*1024+1)),"oversized replacement accepted");
        check(library.read(a).equals(updated),"invalid saves preserve source");
        library.delete(a);
        reject(()->library.writeCurrentIfUnchanged(savedA,SOURCE),"deleted source recreated");
        check(!a.file.exists(),"deleted source stays absent");
        System.out.println("Editor source receipt: "+checks+" checks passed");
    }
}
