package io.github.xgl34222220.bichen;

import android.content.*;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

/** Multiple source configurations per core. Source files are never rewritten in place by startup generation. */
final class ProxyConfigLibrary {
    private static final int LIMIT=4*1024*1024;
    private final File root;
    private final SharedPreferences prefs;
    ProxyConfigLibrary(Context c){root=new File(c.getFilesDir(),"proxy/configs");prefs=c.getSharedPreferences("bichen",Context.MODE_PRIVATE);}

    static final class Entry {
        final ProxyRuntimeProfile.Core core;final String name;final File file;
        Entry(ProxyRuntimeProfile.Core core,String name,File file){this.core=core;this.name=name;this.file=file;}
    }

    private File dir(ProxyRuntimeProfile.Core core){return new File(root,core.id);}
    private String key(ProxyRuntimeProfile.Core core){return "proxySelectedConfig."+core.id;}

    List<Entry> list(ProxyRuntimeProfile.Core core){
        File d=dir(core);File[] files=d.listFiles(File::isFile);ArrayList<Entry> out=new ArrayList<>();if(files!=null)for(File f:files)if(coreAccepts(core,f.getName()))out.add(new Entry(core,f.getName(),f));out.sort(Comparator.comparing(e->e.name,CollatorHolder.ORDER));return out;
    }

    Entry selected(ProxyRuntimeProfile.Core core){
        String name=prefs.getString(key(core),"");if(name.isEmpty())return null;File f=new File(dir(core),name);return f.isFile()&&coreAccepts(core,name)?new Entry(core,name,f):null;
    }

    void select(ProxyRuntimeProfile.Core core,String name)throws IOException{
        String safe=safeName(name);File f=new File(dir(core),safe);if(!f.isFile()||!coreAccepts(core,safe))throw new IOException("配置不存在或格式不属于当前核心");prefs.edit().putString(key(core),safe).apply();
    }

    Entry importConfig(ProxyRuntimeProfile.Core core,String requestedName,InputStream source)throws IOException{
        if(source==null)throw new IOException("无法读取配置文件");String name=safeName(requestedName);if(!coreAccepts(core,name))throw new IOException(core.label+" 不支持该配置格式");File d=dir(core);if(!d.isDirectory()&&!d.mkdirs())throw new IOException("无法创建配置目录");File target=new File(d,name),tmp=new File(d,name+".new");int total=0;
        try(InputStream in=source;FileOutputStream out=new FileOutputStream(tmp,false)){
            byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("配置导入已取消");total+=n;if(total>LIMIT)throw new IOException("配置超过 4 MiB");out.write(b,0,n);}out.getFD().sync();
        }catch(IOException e){tmp.delete();throw e;}
        if(total==0){tmp.delete();throw new IOException("配置为空");}
        try{Files.move(tmp.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(Exception atomic){if(!tmp.renameTo(target)){tmp.delete();throw new IOException("无法保存配置");}}
        prefs.edit().putString(key(core),name).apply();return new Entry(core,name,target);
    }

    String read(Entry entry)throws IOException{
        if(entry==null||!entry.file.isFile())throw new IOException("尚未选择配置");byte[] bytes=Files.readAllBytes(entry.file.toPath());if(bytes.length>LIMIT)throw new IOException("配置超过 4 MiB");try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();}catch(CharacterCodingException e){throw new IOException("配置必须是 UTF-8 文本");}
    }

    void delete(Entry entry)throws IOException{
        if(entry==null)return;if(entry.file.exists()&&!entry.file.delete())throw new IOException("无法删除配置");if(entry.name.equals(prefs.getString(key(entry.core),"")))prefs.edit().remove(key(entry.core)).apply();
    }

    static boolean coreAccepts(ProxyRuntimeProfile.Core core,String name){if(name==null)return false;int dot=name.lastIndexOf('.');return dot>0&&core.extensions.contains(name.substring(dot+1).toLowerCase(Locale.ROOT));}
    static String safeName(String name)throws IOException{
        if(name==null)throw new IOException("配置名称无效");String n=name.trim();if(n.length()<3||n.length()>120||n.contains("/")||n.contains("\\")||n.contains("\u0000")||n.equals(".")||n.equals(".."))throw new IOException("配置名称无效");return n;
    }
    private static final class CollatorHolder{static final java.text.Collator ORDER=java.text.Collator.getInstance(Locale.CHINA);}
}
