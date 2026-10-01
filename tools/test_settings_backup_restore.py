#!/usr/bin/env python3
"""Run actual settings-backup/library methods with synthetic Android host adapters.

Requires a local Kotlin compiler distribution (KOTLIN_LIB), an org.json jar
(JSON_JAR), and a JDK (JAVA_HOME or PATH). Does not download dependencies or
contact a controller; Android runtime behavior is covered separately by the
Robolectric SettingsBackupRestoreTest. Every configuration/credential is synthetic.
"""
from pathlib import Path
import subprocess, sys, os, shutil, tempfile
ROOT=Path(__file__).resolve().parents[1]
PKG=ROOT/'android-app/app/src/main/java/io/github/xgl34222220/hetu'
temporary=tempfile.TemporaryDirectory(prefix='hetu-backup-restore-host-')
D=Path(temporary.name);src=D/'src';src.mkdir()
java_home=os.environ.get('JAVA_HOME')
def java_tool(name): return str(Path(java_home)/'bin'/name) if java_home else name
def put(name,text):
 p=src/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text);return p
put('android/content/Context.java','''package android.content;import java.io.*;public abstract class Context {public static final int MODE_PRIVATE=0;public Context getApplicationContext(){return this;} public abstract File getFilesDir();public abstract SharedPreferences getSharedPreferences(String n,int mode);public abstract ContentResolver getContentResolver();}''')
put('android/content/ContentResolver.java','''package android.content;import java.io.*;import android.net.Uri;public class ContentResolver {public InputStream openInputStream(Uri uri)throws IOException{return new FileInputStream(uri.path);}public OutputStream openOutputStream(Uri uri,String mode)throws IOException{return new FileOutputStream(uri.path);}}''')
put('android/net/Uri.java','''package android.net;import java.io.*;public final class Uri {public final File path;private Uri(File f){path=f;}public static Uri fromFile(File f){return new Uri(f);}}''')
put('android/util/Base64.java','''package android.util;public class Base64 {public static final int DEFAULT=0,NO_WRAP=2;public static String encodeToString(byte[] v,int f){return java.util.Base64.getEncoder().encodeToString(v);}public static byte[] decode(String v,int f){return java.util.Base64.getDecoder().decode(v.replaceAll("\\\\s", ""));}}''')
put('android/content/SharedPreferences.java','''package android.content;import java.util.*;public interface SharedPreferences {Map<String,?> getAll();String getString(String k,String d);boolean getBoolean(String k,boolean d);boolean contains(String k);Editor edit();interface Editor {Editor putString(String k,String v);Editor putBoolean(String k,boolean v);Editor putInt(String k,int v);Editor putLong(String k,long v);Editor putFloat(String k,float v);Editor putStringSet(String k,Set<String> v);Editor remove(String k);Editor clear();void apply();boolean commit();}}''')
put('host/HostContext.java','''package host;import android.content.*;import java.io.*;import java.util.*;public class HostContext extends Context {public final File files;public final Prefs prefs=new Prefs();public HostContext(File f){files=f;files.mkdirs();}public File getFilesDir(){return files;}public SharedPreferences getSharedPreferences(String n,int m){return prefs;}public ContentResolver getContentResolver(){return new ContentResolver();}public static class Prefs implements SharedPreferences {public final Map<String,Object> data=new HashMap<>();public boolean failCommit=false;public int failures=0;public int commits=0;public Runnable onCommit=null;public Map<String,?> getAll(){return new HashMap<>(data);}public String getString(String k,String d){return (String)data.getOrDefault(k,d);}public boolean getBoolean(String k,boolean d){return (Boolean)data.getOrDefault(k,d);}public boolean contains(String k){return data.containsKey(k);}public Editor edit(){return new Editor(){final Map<String,Object> pending=new HashMap<>();boolean clear=false;public Editor putString(String k,String v){pending.put(k,v);return this;}public Editor putBoolean(String k,boolean v){pending.put(k,v);return this;}public Editor putInt(String k,int v){pending.put(k,v);return this;}public Editor putLong(String k,long v){pending.put(k,v);return this;}public Editor putFloat(String k,float v){pending.put(k,v);return this;}public Editor putStringSet(String k,Set<String> v){pending.put(k,new HashSet<>(v));return this;}public Editor remove(String k){pending.put(k,null);return this;}public Editor clear(){clear=true;return this;}public void apply(){if(clear)data.clear();pending.forEach((k,v)->{if(v==null)data.remove(k);else data.put(k,v);});}public boolean commit(){apply();commits++;if(onCommit!=null)onCommit.run();if(failCommit)return false;if(failures>0){failures--;return false;}return true;}};}}}''')
profile=(PKG/'ProxyRuntimeProfile.java').read_text();enum=profile[profile.index('    enum Core {'):profile.index('    enum Mode {')]
put('io/github/xgl34222220/hetu/ProxyRuntimeProfile.java','package io.github.xgl34222220.hetu;import java.util.*;final class ProxyRuntimeProfile {'+enum+'static Set<String> set(String... x){return new HashSet<>(Arrays.asList(x));}}')
put('io/github/xgl34222220/hetu/BuildConfig.java','package io.github.xgl34222220.hetu;public class BuildConfig {public static final String VERSION_NAME="synthetic-test";}')
classes=D/'classes';shutil.rmtree(classes,ignore_errors=True);classes.mkdir(exist_ok=True)
java_src=list(src.rglob('*.java'))+[PKG/n for n in ['ProxyConfigLibrary.java','ConfigEditSnapshot.java','BundledProxyConfig.java']]+list(PKG.glob('BundledProxyConfigData*.java'))
subprocess.run([java_tool('javac'),'--release','17','-encoding','UTF-8','-d',str(classes),*map(str,java_src)],check=True)
lib=Path(os.environ['KOTLIN_LIB']);json_jar=Path(os.environ['JSON_JAR']);cp=':'.join([str(classes),str(next(lib.glob('kotlin-stdlib-[0-9]*.jar'))),str(next(lib.glob('kotlinx-coroutines-core-jvm-*.jar'))),str(json_jar)])
subprocess.run([java_tool('java'),'-cp',str(lib/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp,'-d',str(classes),str(PKG/'HetuSettingsBackup.kt'),str(ROOT/'tests/SettingsBackupRestoreHostTest.kt')],check=True)
subprocess.run([java_tool('java'),'-cp',cp,'io.github.xgl34222220.hetu.SettingsBackupRestoreHostTestKt'],check=True)

temporary.cleanup()
