package io.github.xgl34222220.bichen;
import java.util.*;
public final class ProxyAppPolicyTest {
 private static int checks;
 private static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
 public static void main(String[] args){
  Set<String> draft=new HashSet<>(Arrays.asList("my.app","com.allowed","com.absent","android"));
  Set<String> installed=new HashSet<>(Arrays.asList("com.allowed","android","not.requested"));
  ProxyAppPolicy p=new ProxyAppPolicy(true,draft,installed,"my.app");
  check(p.requested.size()==3&&!p.requested.contains("my.app"),"self bypass is mandatory but not counted as user choice");
  check(p.applied.equals(new TreeSet<>(Arrays.asList("com.allowed","android"))),"only requested and installed exclusions count as applied");
  check(p.missing.equals(Collections.singleton("com.absent")),"uninstalled package reported separately");
  check(!p.differs(true,draft,"my.app"),"missing package does not cause a permanent pending-change warning");
  draft.clear();installed.clear();
  check(p.requested.size()==3&&p.applied.size()==2,"running policy cannot change when preferences set is mutated");
  check(p.differs(true,draft,"my.app"),"draft edits do not claim live effect");
  check(p.differs(false,p.requested,"my.app"),"filter changes are pending not active");
  boolean immutable=false;try{p.requested.clear();}catch(UnsupportedOperationException expected){immutable=true;}
  check(immutable,"published requested set immutable");
  immutable=false;try{p.applied.add("new.app");}catch(UnsupportedOperationException expected){immutable=true;}
  check(immutable,"published applied set immutable");
  for(String bad:new String[]{"", "com.bad/name", "com.bad\nname", "com.bad name", ".bad", "bad.", "bad..app", "9bad.app", "com.9bad"}){
   boolean rejected=false;try{new ProxyAppPolicy(true,Collections.singleton(bad),null,"my.app");}catch(IllegalArgumentException expected){rejected=true;}
   check(rejected,"invalid package rejected: "+bad.replace('\n','?'));
  }
  HashSet<String> huge=new HashSet<>();for(int i=0;i<2001;i++)huge.add("com.example.app"+i);
  boolean capped=false;try{new ProxyAppPolicy(true,huge,null,"my.app");}catch(IllegalArgumentException expected){capped=true;}
  check(capped,"oversized bypass preference is rejected before VPN setup");
  check(p.differs(true,Collections.singleton("bad app"),"my.app"),"malformed external preference is visibly pending");
  check(new ProxyAppPolicy(false,null,null,"my.app").applied.isEmpty(),"empty defaults bypass no third party app");
  System.out.println("ProxyAppPolicyTest passed: "+checks);
 }
}
