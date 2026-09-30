package io.github.xgl34222220.hetu;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ConfigEditLibraryTest {
    private static int checks;
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); checks++; }
    private static final class MemoryPrefs implements SharedPreferences {
        final Map<String,String> values = new ConcurrentHashMap<>();
        public String getString(String key,String fallback) { return values.getOrDefault(key,fallback); }
        public Editor edit() { return new Editor() {
            final Map<String,String> updates=new HashMap<>(); final Set<String> removes=new HashSet<>();
            public Editor putString(String key,String value){ updates.put(key,value);return this; }
            public Editor remove(String key){ removes.add(key);return this; }
            public void apply(){ removes.forEach(values::remove);values.putAll(updates); }
        }; }
    }
    private static final class HostContext extends Context {
        final File dir; final MemoryPrefs prefs=new MemoryPrefs();
        HostContext(File value) { dir=value;dir.mkdirs(); }
        public File getFilesDir(){ return dir; }
        public SharedPreferences getSharedPreferences(String name,int mode){ return prefs; }
    }
    private static ProxyConfigLibrary.Entry imported(ProxyConfigLibrary library,String name,String text) throws Exception {
        return library.importConfig(ProxyRuntimeProfile.Core.MIHOMO,name,new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }
    private static ConfigEditSnapshot snapshot(ProxyConfigLibrary library,ProxyConfigLibrary.Entry entry) throws Exception {
        return new ConfigEditSnapshot(entry.core.id,entry.name,library.read(entry));
    }
    private static void reject(RunnableWithError action,String label) throws Exception {
        try { action.run();throw new AssertionError(label); } catch(IOException expected){checks++;}
    }
    private interface RunnableWithError { void run() throws Exception; }
    private static void sharesLock(Object lock,RunnableWithError action,String label) throws Exception {
        CountDownLatch attempted=new CountDownLatch(1);
        FutureTask<Void> future=new FutureTask<>(()->{ attempted.countDown();action.run();return null; });
        Thread thread=new Thread(future,label);
        synchronized(lock) {
            thread.start();attempted.await();
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(thread.getState()!=Thread.State.BLOCKED&&!future.isDone()&&System.nanoTime()<until)Thread.yield();
            check(thread.getState()==Thread.State.BLOCKED&&!future.isDone(),label+" must share transaction lock");
        }
        future.get(5,TimeUnit.SECONDS);
    }
    public static void main(String[] args) throws Exception {
        HostContext context=new HostContext(new File(args[0]));
        ProxyConfigLibrary one=new ProxyConfigLibrary(context),two=new ProxyConfigLibrary(context);
        String original="proxy-providers:\n  sample:\n    type: http\n    url: 'https://example.invalid/original'\nrules: []\n";
        ProxyConfigLibrary.Entry entry=imported(one,"test.yaml",original);
        ConfigEditSnapshot baseline=snapshot(one,entry);
        one.writeIfUnchanged(entry,baseline,"# first\n"+original);
        check(one.read(entry).startsWith("# first"),"CAS writes the exact selected source");
        reject(()->two.writeIfUnchanged(entry,baseline,"# stale\n"+original),"stale editor overwrote data");
        check(one.read(entry).startsWith("# first"),"conflict preserves prior bytes");
        ConfigEditSnapshot current=snapshot(one,entry);
        two.delete(entry);
        reject(()->one.writeIfUnchanged(entry,current,original),"deleted source was recreated");
        check(!entry.file.exists(),"missing file is not recreated");

        java.lang.reflect.Field field=ProxyConfigLibrary.class.getDeclaredField("WRITE_LOCK");field.setAccessible(true);Object lock=field.get(null);
        ProxyConfigLibrary.Entry locked=imported(one,"locked.yaml",original);
        sharesLock(lock,()->two.write(locked,"# write\n"+original),"ordinary write");
        sharesLock(lock,()->two.updateSubscription(locked,"sample","https://example.invalid/new"),"subscription read-modify-write");
        sharesLock(lock,()->imported(two,"new.yaml",original),"import");
        sharesLock(lock,()->two.delete(locked),"delete");

        ProxyConfigLibrary.Entry renaming=imported(one,"rename.yaml",original);
        ConfigEditSnapshot renameSnapshot=snapshot(one,renaming);
        sharesLock(lock,()->two.rename(renaming,"renamed.yaml"),"rename");
        reject(()->one.writeIfUnchanged(renaming,renameSnapshot,"# stale\n"+original),"renamed source was recreated");
        check(!renaming.file.exists(),"renamed source not recreated by old editor");
        check(new File(renaming.file.getParentFile(),"renamed.yaml").isFile(),"renamed content preserved");

        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            for(int i=0;i<40;i++) {
                ProxyConfigLibrary.Entry race=imported(one,"race.yaml",original);
                ConfigEditSnapshot raceSnapshot=snapshot(one,race);
                CountDownLatch start=new CountDownLatch(1);AtomicBoolean saved=new AtomicBoolean(false);
                Future<?> editor=pool.submit(()->{try{start.await();one.writeIfUnchanged(race,raceSnapshot,"# editor-preserved\n"+original);saved.set(true);}catch(IOException conflict){}catch(Exception failure){throw new RuntimeException(failure);}});
                Future<?> subscription=pool.submit(()->{try{start.await();two.updateSubscription(race,"sample","https://example.invalid/updated");}catch(Exception failure){throw new RuntimeException(failure);}});
                start.countDown();editor.get(5,TimeUnit.SECONDS);subscription.get(5,TimeUnit.SECONDS);
                String result=one.read(race);
                check(result.contains("https://example.invalid/updated"),"subscription result remains");
                check(!saved.get()||result.contains("# editor-preserved"),"successful editor save survives subscription transaction");
            }
        } finally { pool.shutdownNow(); }
        try(var paths=Files.walk(context.dir.toPath())){check(paths.noneMatch(p->p.toString().endsWith(".new")),"no partial write files remain");}
        System.out.println("ConfigEditLibraryTest passed: "+checks+" checks with real temporary file IO");
    }
}
