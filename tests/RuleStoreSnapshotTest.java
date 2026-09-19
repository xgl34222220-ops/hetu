package io.github.xgl34222220.hetu;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Publish successive immutable generations while an exporter captures its pair. */
public final class RuleStoreSnapshotTest {
    private static int checks;
    private static void check(boolean value,String message) {
        if(!value)throw new AssertionError(message);
        checks++;
    }
    public static void main(String[] args) throws Exception {
        Class<?> type=Class.forName("io.github.xgl34222220.hetu.RuleStore$Snapshot");
        Constructor<?> constructor=type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Field live=RuleStore.class.getDeclaredField("live");
        live.setAccessible(true);
        Object previous=live.get(null);
        Object first=snapshot(constructor,"first"),second=snapshot(constructor,"second");
        AtomicBoolean stop=new AtomicBoolean(false);
        Thread writer=new Thread(()->{
            try {
                while(!stop.get()) { live.set(null,first);live.set(null,second); }
            }catch(IllegalAccessException impossible) { throw new AssertionError(impossible); }
        },"rule-generation-test");
        try {
            live.set(null,first);
            RuleStore.ExportRules captured=RuleStore.currentExportRules();
            live.set(null,second);
            verify(captured);
            check(captured.revision.equals("first"),"captured providers retain their original revision");
            try { captured.domains.add("tamper.test");throw new AssertionError("mutable exported block set"); }
            catch(UnsupportedOperationException expected) { checks++; }
            try { captured.allowDomains.clear();throw new AssertionError("mutable exported allow set"); }
            catch(UnsupportedOperationException expected) { checks++; }
            writer.start();
            for(int i=0;i<20000;i++)verify(RuleStore.currentExportRules());
        }finally {
            stop.set(true);writer.join();live.set(null,previous);
        }
        System.out.println("RuleStoreSnapshotTest passed: "+checks);
    }
    private static Object snapshot(Constructor<?> constructor,String revision) throws Exception {
        return constructor.newInstance(revision,"",false,
                Collections.singleton(revision+".blocked.test"),
                Collections.singleton(revision+".allowed.test"),
                Collections.emptySet(),Collections.singleton(revision+".exception.test"),
                Collections.emptyMap(),Collections.emptyMap(),0L);
    }
    private static void verify(RuleStore.ExportRules snapshot) {
        String id=snapshot.revision;
        check(snapshot.domains.equals(Collections.singleton(id+".blocked.test")),"block and revision belong to one generation");
        check(snapshot.allowDomains.equals(Set.of(id+".allowed.test",id+".exception.test")),"allow and automatic exceptions belong to the same generation");
    }
}
