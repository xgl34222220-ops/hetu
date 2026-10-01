package io.github.xgl34222220.hetu;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real library/file transactions with anonymous fixtures. No Root or network. */
public final class SubscriptionSourceSnapshotTest {
    private static int checks;
    private static final ProxyRuntimeProfile.Core CORE=ProxyRuntimeProfile.Core.MIHOMO;
    private static final String SOURCE="# preserved\nproxy-providers:\n  sample:\n    type: http\n    url: 'https://example.invalid/original'\n  backup:\n    type: http\n    url: 'https://example.invalid/backup'\nproxy-groups:\n  - name: select\n    type: select\n    use: [sample, backup]\nrules: []\n";
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
        return library.importConfig(CORE,name,new ByteArrayInputStream(SOURCE.getBytes(StandardCharsets.UTF_8)));
    }
    private static void unchanged(ProxyConfigLibrary library,ProxyConfigLibrary.Entry entry,String expected,String label)throws Exception{
        check(expected.equals(library.read(entry)),label);
    }
    private static void sharesLock(Object lock,Action action,String label)throws Exception{
        CountDownLatch attempted=new CountDownLatch(1);
        FutureTask<Void> work=new FutureTask<>(()->{attempted.countDown();action.run();return null;});
        Thread thread=new Thread(work,label);
        synchronized(lock){
            thread.start();check(attempted.await(2,TimeUnit.SECONDS),label+" began");
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(thread.getState()!=Thread.State.BLOCKED&&!work.isDone()&&System.nanoTime()<deadline)Thread.yield();
            check(thread.getState()==Thread.State.BLOCKED&&!work.isDone(),label+" shares WRITE_LOCK");
        }
        work.get(5,TimeUnit.SECONDS);
    }
    public static void main(String[] args)throws Exception{
        Host host=new Host(new File(args[0]));
        ProxyConfigLibrary one=new ProxyConfigLibrary(host),two=new ProxyConfigLibrary(host);
        ProxyConfigLibrary.Entry a=create(one,"a.yaml");
        ProxyConfigLibrary.SubscriptionEditSnapshot opened=one.subscriptionEditSnapshot(CORE);
        check(opened.core==CORE&&opened.source.name.equals(a.name),"snapshot binds actual source identity");
        check(opened.source.originalText.equals(SOURCE),"snapshot retains exact source bytes in memory");
        check(opened.subscriptions.size()==2&&opened.subscriptions.get(0).url.equals("https://example.invalid/original"),"provider values come from same source revision");
        try{opened.subscriptions.clear();throw new AssertionError("mutable snapshot list");}catch(UnsupportedOperationException expected){checks++;}
        ProxyConfigLibrary.SourceVersion receipt=one.updateSubscriptionIfUnchanged(opened,"sample","https://example.invalid/updated");
        String updated=one.read(a);
        check(updated.contains("https://example.invalid/updated")&&updated.startsWith("# preserved\n"),"bound update changes field and keeps unrelated YAML");
        check(receipt.matches(CORE.id,a.name,updated),"receipt confirms exact committed identity and bytes");
        check(!receipt.matches(CORE.id,"other.yaml",updated),"receipt rejects another source name");
        check(!receipt.matches("mihomo-smart",a.name,updated),"receipt rejects another core");
        check(!receipt.matches(CORE.id,a.name,updated+"# later\n"),"receipt rejects a later source revision");
        Set<String> receiptFields=new HashSet<>();
        for(java.lang.reflect.Field field:ProxyConfigLibrary.SourceVersion.class.getDeclaredFields())receiptFields.add(field.getName());
        check(receiptFields.equals(Set.of("coreId","name","sha256")),"receipt stores no YAML or subscription URL");
        check(receipt.sha256.matches("[a-f0-9]{64}"),"receipt uses SHA-256");
        reject(()->two.updateSubscriptionIfUnchanged(opened,"sample","https://example.invalid/stale"),"old form overwrote newer URL");
        unchanged(one,a,updated,"stale update leaves committed bytes unchanged");

        ProxyConfigLibrary.Entry b=create(one,"b.yaml");one.select(CORE,a.name);
        ProxyConfigLibrary.SubscriptionEditSnapshot boundA=one.subscriptionEditSnapshot(CORE);
        two.select(CORE,b.name);
        reject(()->one.updateSubscriptionIfUnchanged(boundA,"sample","https://example.invalid/wrong"),"stale update targeted B");
        reject(()->one.addSubscriptionIfUnchanged(boundA,"new","https://example.invalid/new"),"stale add targeted B");
        reject(()->one.deleteSubscriptionIfUnchanged(boundA,"sample"),"stale delete targeted B");
        unchanged(one,a,updated,"A unchanged after all rejected cross-source actions");
        unchanged(one,b,SOURCE,"B unchanged after all rejected cross-source actions");

        one.select(CORE,a.name);
        ProxyConfigLibrary.SubscriptionEditSnapshot beforeCoreChange=one.subscriptionEditSnapshot(CORE);
        ProxyConfigLibrary.Entry sameName=one.importConfig(ProxyRuntimeProfile.Core.MIHOMO_SMART,a.name,new ByteArrayInputStream(SOURCE.getBytes(StandardCharsets.UTF_8)));
        host.prefs.edit().putString("proxyBaseCore","mihomo-smart").apply();
        reject(()->one.updateSubscriptionIfUnchanged(beforeCoreChange,"sample","https://example.invalid/wrong-core"),"core switch redirected old edit");
        reject(()->one.subscriptionEditSnapshot(CORE),"capture used a noncurrent core");
        unchanged(one,a,updated,"old core source unchanged");unchanged(one,sameName,SOURCE,"same filename in new core unchanged");
        host.prefs.edit().putString("proxyBaseCore",CORE.id).apply();

        one.select(CORE,a.name);
        ProxyConfigLibrary.SubscriptionEditSnapshot external=one.subscriptionEditSnapshot(CORE);
        Files.writeString(a.file.toPath(),"# external writer\n"+updated,StandardCharsets.UTF_8);
        reject(()->two.addSubscriptionIfUnchanged(external,"new","https://example.invalid/new"),"completed external edit overwritten");
        unchanged(one,a,"# external writer\n"+updated,"external change preserved by full-source CAS");

        ProxyConfigLibrary.Entry rename=create(one,"rename.yaml");
        ProxyConfigLibrary.SubscriptionEditSnapshot beforeRename=one.subscriptionEditSnapshot(CORE);
        ProxyConfigLibrary.Entry renamed=two.rename(rename,"renamed.yaml");
        reject(()->one.deleteSubscriptionIfUnchanged(beforeRename,"sample"),"renamed source recreated");
        check(!rename.file.exists(),"old name stays absent");unchanged(one,renamed,SOURCE,"renamed bytes retained");
        ProxyConfigLibrary.SubscriptionEditSnapshot beforeDelete=one.subscriptionEditSnapshot(CORE);
        two.delete(renamed);
        reject(()->one.updateSubscriptionIfUnchanged(beforeDelete,"sample","https://example.invalid/no"),"deleted source recreated");
        check(!renamed.file.exists(),"deleted source stays absent");

        ProxyConfigLibrary.Entry mutations=create(one,"mutations.yaml");
        ProxyConfigLibrary.SourceVersion added=one.addSubscriptionIfUnchanged(one.subscriptionEditSnapshot(CORE),"added","https://example.invalid/added");
        check(one.subscriptions(mutations).size()==3&&one.read(mutations).contains("use: [sample, backup, 'added']"),"bound add updates provider and references");
        check(added.matches(CORE.id,mutations.name,one.read(mutations)),"add receipt matches commit");
        ProxyConfigLibrary.SourceVersion removed=one.deleteSubscriptionIfUnchanged(one.subscriptionEditSnapshot(CORE),"added");
        check(one.subscriptions(mutations).size()==2&&!one.read(mutations).contains("https://example.invalid/added"),"bound delete removes provider");
        check(removed.matches(CORE.id,mutations.name,one.read(mutations)),"delete receipt matches commit");
        one.deleteSubscriptionIfUnchanged(one.subscriptionEditSnapshot(CORE),"backup");
        String last=one.read(mutations);
        reject(()->one.deleteSubscriptionIfUnchanged(one.subscriptionEditSnapshot(CORE),"sample"),"last provider deleted");
        unchanged(one,mutations,last,"last-provider guard preserves source");

        java.lang.reflect.Field f=ProxyConfigLibrary.class.getDeclaredField("WRITE_LOCK");f.setAccessible(true);Object lock=f.get(null);
        create(one,"locked.yaml");
        sharesLock(lock,()->two.subscriptionEditSnapshot(CORE),"snapshot capture");
        ProxyConfigLibrary.SubscriptionEditSnapshot updateBaseline=one.subscriptionEditSnapshot(CORE);
        sharesLock(lock,()->two.updateSubscriptionIfUnchanged(updateBaseline,"sample","https://example.invalid/locked"),"bound update");
        ProxyConfigLibrary.SubscriptionEditSnapshot addBaseline=one.subscriptionEditSnapshot(CORE);
        sharesLock(lock,()->two.addSubscriptionIfUnchanged(addBaseline,"third","https://example.invalid/third"),"bound add");
        ProxyConfigLibrary.SubscriptionEditSnapshot deleteBaseline=one.subscriptionEditSnapshot(CORE);
        sharesLock(lock,()->two.deleteSubscriptionIfUnchanged(deleteBaseline,"third"),"bound delete");

        ExecutorService pool=Executors.newFixedThreadPool(2);
        try{
            for(int i=0;i<20;i++){
                ProxyConfigLibrary.Entry race=create(one,"writer-race.yaml");
                ProxyConfigLibrary.SubscriptionEditSnapshot first=one.subscriptionEditSnapshot(CORE),second=two.subscriptionEditSnapshot(CORE);
                CountDownLatch gate=new CountDownLatch(1);AtomicBoolean saved1=new AtomicBoolean(),saved2=new AtomicBoolean();
                Future<?> x=pool.submit(()->{try{gate.await();one.updateSubscriptionIfUnchanged(first,"sample","https://example.invalid/one");saved1.set(true);}catch(IOException conflict){}catch(Exception error){throw new RuntimeException(error);}});
                Future<?> y=pool.submit(()->{try{gate.await();two.updateSubscriptionIfUnchanged(second,"sample","https://example.invalid/two");saved2.set(true);}catch(IOException conflict){}catch(Exception error){throw new RuntimeException(error);}});
                gate.countDown();x.get(5,TimeUnit.SECONDS);y.get(5,TimeUnit.SECONDS);
                check(saved1.get()!=saved2.get(),"same revision admits exactly one winner");
                check(one.read(race).contains(saved1.get()?"https://example.invalid/one":"https://example.invalid/two"),"winner bytes retained");
            }
            for(int i=0;i<20;i++){
                ProxyConfigLibrary.Entry ra=create(one,"selection-race-a.yaml"),rb=create(one,"selection-race-b.yaml");one.select(CORE,ra.name);
                ProxyConfigLibrary.SubscriptionEditSnapshot form=one.subscriptionEditSnapshot(CORE);
                CountDownLatch gate=new CountDownLatch(1);AtomicBoolean saved=new AtomicBoolean();
                Future<?> x=pool.submit(()->{try{gate.await();one.updateSubscriptionIfUnchanged(form,"sample","https://example.invalid/winner");saved.set(true);}catch(IOException conflict){}catch(Exception error){throw new RuntimeException(error);}});
                Future<?> y=pool.submit(()->{try{gate.await();two.select(CORE,rb.name);}catch(Exception error){throw new RuntimeException(error);}});
                gate.countDown();x.get(5,TimeUnit.SECONDS);y.get(5,TimeUnit.SECONDS);
                unchanged(one,rb,SOURCE,"selection race never writes B");
                check(one.read(ra).contains(saved.get()?"https://example.invalid/winner":"https://example.invalid/original"),"A reflects only committed save");
            }
        }finally{pool.shutdownNow();}
        System.out.println("SubscriptionSourceSnapshotTest passed: "+checks+" checks; source API only, runtime apply is not exercised");
    }
}
