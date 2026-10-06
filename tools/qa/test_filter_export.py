#!/usr/bin/env python3
"""Run the actual old/new exporter with a cache-evicting Android API fixture.

Only temporary files and Java API stubs are used. No device, Root or network.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

REPO = Path(__file__).resolve().parents[2]
PRODUCTION = REPO / 'android-app/app/src/main/java/io/github/xgl34222220/hetu'

CONTEXT = '''package android.content;
import java.io.File;
public final class Context {
  public final File root;
  public Context(File root){this.root=root;getCacheDir().mkdirs();}
  public Context getApplicationContext(){return this;}
  public File getCacheDir(){return new File(root,"cache");}
  public File getNoBackupFilesDir(){File f=new File(root,"stable");f.mkdirs();return f;}
}'''
RULES = '''package io.github.xgl34222220.hetu;
import android.content.Context;
import java.io.File;
import java.util.Set;
final class RuleStore {
  final Context context;
  static String revision="g.first";
  RuleStore(Context context){this.context=context;}
  public void reload(){clear(context.getCacheDir());}
  static void clear(File file){File[] children=file.listFiles();if(children!=null)for(File child:children)clear(child);file.delete();}
  static final class ExportRules {
    final String revision=RuleStore.revision;
    final Set<String> domains=Set.of("block-"+RuleStore.revision+".test");
    final Set<String> allowDomains=Set.of("allow-"+RuleStore.revision+".test");
  }
  static ExportRules currentExportRules(){return new ExportRules();}
}'''
HARNESS = '''package io.github.xgl34222220.hetu;
import android.content.Context;
import java.io.*;
import java.nio.file.Files;
import java.util.Properties;
public final class ExportHarness {
  static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
  public static void main(String[] args)throws Exception {
    Context context=new Context(new File(args[1]));
    if(args[0].equals("before")){
      try{ProxyAdblockRules.export(context);throw new AssertionError("old cache race was not reproduced");}
      catch(FileNotFoundException error){check(error.getMessage().contains("hetu-adblock.txt.new"),"wrong failure: "+error);}
      System.out.println("old-cache-eviction-ENOENT-reproduced");return;
    }
    ProxyAdblockRules.Snapshot first=ProxyAdblockRules.export(context);
    check(first.file.getCanonicalPath().startsWith(context.getNoBackupFilesDir().getCanonicalPath()+File.separator),"cache-backed export");
    check(first.file.isFile() && first.allowFile.isFile(),"incomplete pair");
    byte[] old=Files.readAllBytes(first.file.toPath());
    check(first.file.equals(ProxyAdblockRules.export(context).file),"same revision not reused");
    RuleStore.revision="g.second";
    ProxyAdblockRules.Snapshot second=ProxyAdblockRules.export(context);
    check(!second.file.equals(first.file),"mutable snapshot path");
    check(java.util.Arrays.equals(old,Files.readAllBytes(first.file.toPath())),"active handoff overwritten");
    Properties meta=new Properties();try(InputStream in=new FileInputStream(new File(second.file.getParentFile(),"hetu-adblock.meta"))){meta.load(in);}
    check(RuntimeCompatibility14.cachedPairValid(meta,second.file,second.allowFile),"unverified pair");
    System.out.println("new-cache-eviction-stable-pair-and-handoff-passed");
  }
}'''


def method(source, name):
    start = source.index('    static ', source.index('    static String sha256') if name == 'sha256' else source.index('    static boolean cachedPairValid'))
    brace = source.index('{', start)
    depth = 1
    end = brace + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


def run_exporter(source, mode):
    with tempfile.TemporaryDirectory(prefix='hetu-filter-qa-') as temp:
        root = Path(temp)
        compat = (PRODUCTION/'RuntimeCompatibility14.java').read_text()
        compatibility = 'package io.github.xgl34222220.hetu;\nimport java.io.*;\nimport java.security.*;\nimport java.util.*;\nfinal class RuntimeCompatibility14 {\n' + method(compat, 'sha256') + '\n' + method(compat, 'cachedPairValid') + '\n}\n'
        sources = {'android/content/Context.java': CONTEXT,
                   'io/github/xgl34222220/hetu/RuleStore.java': RULES,
                   'io/github/xgl34222220/hetu/ProxyAdblockRules.java': source,
                   'io/github/xgl34222220/hetu/RuntimeCompatibility14.java': compatibility,
                   'io/github/xgl34222220/hetu/ExportHarness.java': HARNESS}
        paths = []
        for name, text in sources.items():
            path = root/name; path.parent.mkdir(parents=True, exist_ok=True); path.write_text(text); paths.append(str(path))
        subprocess.run(['javac', '--release', '17', '-d', str(root/'classes'), *paths], check=True, timeout=60, capture_output=True, text=True)
        result = subprocess.run(['java', '-cp', str(root/'classes'), 'io.github.xgl34222220.hetu.ExportHarness', mode, str(root/'fixture')], check=True, timeout=30, capture_output=True, text=True)
        return result.stdout.strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--before', type=Path, required=True)
    args = parser.parse_args()
    before = args.before.read_text()
    current = (PRODUCTION/'ProxyAdblockRules.java').read_text()
    result = {'fixtureOnly': True, 'deviceOrNetworkAccess': False,
              'beforeSourceSha256': hashlib.sha256(before.encode()).hexdigest(),
              'afterSourceSha256': hashlib.sha256(current.encode()).hexdigest(),
              'before': run_exporter(before, 'before'), 'after': run_exporter(current, 'after')}
    output = REPO/'out/verification/filter-export-before-after.json'
    output.parent.mkdir(parents=True, exist_ok=True); output.write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps(result))


if __name__ == '__main__':
    main()
