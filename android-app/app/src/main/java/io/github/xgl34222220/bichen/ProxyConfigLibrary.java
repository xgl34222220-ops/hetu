package io.github.xgl34222220.bichen;

import android.content.*;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

/** Multiple source configurations per core. Source files are never rewritten by runtime generation. */
final class ProxyConfigLibrary {
    static final String BUNDLED_NAME="辟尘内置-TPROXY.yaml";
    private static final String BUNDLED_ASSET="proxy/bichen-default-tproxy.yaml";
    private static final String PLACEHOLDER="example.invalid/bichen-subscription-";
    private static final int LIMIT=4*1024*1024;
    private final Context app;
    private final File root;
    private final SharedPreferences prefs;
    ProxyConfigLibrary(Context c){app=c.getApplicationContext();root=new File(app.getFilesDir(),"proxy/configs");prefs=app.getSharedPreferences("bichen",Context.MODE_PRIVATE);}

    static final class Entry {
        final ProxyRuntimeProfile.Core core;final String name;final File file;
        Entry(ProxyRuntimeProfile.Core core,String name,File file){this.core=core;this.name=name;this.file=file;}
    }

    static final class Subscription {
        final String name,url;final boolean placeholder;
        Subscription(String name,String url){this.name=name;this.url=url;this.placeholder=url.contains(PLACEHOLDER);}
    }

    private File dir(ProxyRuntimeProfile.Core core){return new File(root,core.id);}
    private String key(ProxyRuntimeProfile.Core core){return "proxySelectedConfig."+core.id;}

    private void ensureBundled(ProxyRuntimeProfile.Core core){
        if(core!=ProxyRuntimeProfile.Core.MIHOMO&&core!=ProxyRuntimeProfile.Core.MIHOMO_SMART)return;
        try{
            File d=dir(core);if(!d.isDirectory()&&!d.mkdirs())return;
            File target=new File(d,BUNDLED_NAME);
            if(!target.isFile()){
                try(InputStream in=app.getAssets().open(BUNDLED_ASSET)){writeStreamAtomic(target,in);}
            }
            String selected=prefs.getString(key(core),"");
            if(selected.isEmpty()||!new File(d,selected).isFile())prefs.edit().putString(key(core),BUNDLED_NAME).apply();
        }catch(Exception ignored){/* Missing optional asset must not hide imported configurations. */}
    }

    List<Entry> list(ProxyRuntimeProfile.Core core){
        ensureBundled(core);File d=dir(core);File[] files=d.listFiles(File::isFile);ArrayList<Entry> out=new ArrayList<>();if(files!=null)for(File f:files)if(coreAccepts(core,f.getName()))out.add(new Entry(core,f.getName(),f));out.sort(Comparator.comparing(e->e.name,CollatorHolder.ORDER));return out;
    }

    Entry selected(ProxyRuntimeProfile.Core core){
        ensureBundled(core);String name=prefs.getString(key(core),"");if(name.isEmpty())return null;File f=new File(dir(core),name);return f.isFile()&&coreAccepts(core,name)?new Entry(core,name,f):null;
    }

    void select(ProxyRuntimeProfile.Core core,String name)throws IOException{
        ensureBundled(core);String safe=safeName(name);File f=new File(dir(core),safe);if(!f.isFile()||!coreAccepts(core,safe))throw new IOException("配置不存在或格式不属于当前核心");prefs.edit().putString(key(core),safe).apply();
    }

    Entry importConfig(ProxyRuntimeProfile.Core core,String requestedName,InputStream source)throws IOException{
        if(source==null)throw new IOException("无法读取配置文件");String name=safeName(requestedName);if(!coreAccepts(core,name))throw new IOException(core.label+" 不支持该配置格式");File d=dir(core);if(!d.isDirectory()&&!d.mkdirs())throw new IOException("无法创建配置目录");File target=new File(d,name);
        writeStreamAtomic(target,source);prefs.edit().putString(key(core),name).apply();return new Entry(core,name,target);
    }

    String read(Entry entry)throws IOException{
        if(entry==null||!entry.file.isFile())throw new IOException("尚未选择配置");byte[] bytes=Files.readAllBytes(entry.file.toPath());if(bytes.length>LIMIT)throw new IOException("配置超过 4 MiB");try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();}catch(CharacterCodingException e){throw new IOException("配置必须是 UTF-8 文本");}
    }

    void write(Entry entry,String text)throws IOException{
        if(entry==null)throw new IOException("尚未选择配置");if(text==null||text.trim().isEmpty())throw new IOException("配置不能为空");byte[] bytes=text.getBytes(StandardCharsets.UTF_8);if(bytes.length>LIMIT)throw new IOException("配置超过 4 MiB");
        File d=entry.file.getParentFile();if(d!=null&&!d.isDirectory()&&!d.mkdirs())throw new IOException("无法创建配置目录");File tmp=new File(entry.file.getParentFile(),entry.file.getName()+".new");try(FileOutputStream out=new FileOutputStream(tmp,false)){out.write(bytes);out.getFD().sync();}catch(IOException e){tmp.delete();throw e;}replaceAtomic(tmp,entry.file);
    }

    void delete(Entry entry)throws IOException{
        if(entry==null)return;if(BUNDLED_NAME.equals(entry.name))throw new IOException("内置配置不能删除，可以直接编辑或导入其他配置");if(entry.file.exists()&&!entry.file.delete())throw new IOException("无法删除配置");if(entry.name.equals(prefs.getString(key(entry.core),"")))prefs.edit().remove(key(entry.core)).apply();
    }

    List<Subscription> subscriptions(Entry entry)throws IOException{
        String source=normalize(read(entry));String[] lines=source.split("\n",-1);Section section=providerSection(lines);ArrayList<Subscription> out=new ArrayList<>();if(section==null)return out;
        for(int i=section.start+1;i<section.end;i++){
            String t=lines[i].trim();if(indent(lines[i])!=2||t.isEmpty()||t.startsWith("#")||!t.endsWith(":"))continue;
            String name=unquote(t.substring(0,t.length()-1).trim());if(name.isEmpty())continue;String url="";
            for(int j=i+1;j<section.end;j++){
                int ind=indent(lines[j]);String inner=lines[j].trim();if(ind==2&&!inner.isEmpty()&&!inner.startsWith("#")&&inner.endsWith(":"))break;
                if(ind>=4&&inner.startsWith("url:")){url=unquote(stripComment(inner.substring(4).trim()));break;}
            }
            if(!url.isEmpty())out.add(new Subscription(name,url));
        }
        return out;
    }

    boolean hasConfiguredSubscription(Entry entry)throws IOException{
        List<Subscription> items=subscriptions(entry);if(items.isEmpty())return true;for(Subscription s:items)if(!s.placeholder)return true;return false;
    }

    void updateSubscription(Entry entry,String name,String url)throws IOException{
        String safeName=providerName(name);String safeUrl=subscriptionUrl(url);String source=normalize(read(entry));String[] lines=source.split("\n",-1);Section section=providerSection(lines);if(section==null)throw new IOException("当前配置没有 proxy-providers 段");
        int start=findProvider(lines,section,safeName);if(start<0)throw new IOException("订阅不存在："+safeName);int end=providerEnd(lines,section,start);boolean found=false;StringBuilder out=new StringBuilder();
        for(int i=0;i<lines.length;i++){
            String line=lines[i];if(i>start&&i<end&&indent(line)>=4&&line.trim().startsWith("url:")){String prefix=line.substring(0,line.indexOf('u'));out.append(prefix).append("url: '").append(yamlSingle(safeUrl)).append("'\n");found=true;}else out.append(line).append('\n');
        }
        if(!found)throw new IOException("订阅缺少 url 字段，建议使用 YAML 编辑器修改");write(entry,trimTerminal(out.toString()));
    }

    void addSubscription(Entry entry,String name,String url)throws IOException{
        String safeName=providerName(name);String safeUrl=subscriptionUrl(url);String source=normalize(read(entry));String[] lines=source.split("\n",-1);Section section=providerSection(lines);if(section==null)throw new IOException("当前配置没有 proxy-providers 段");if(findProvider(lines,section,safeName)>=0)throw new IOException("订阅名称已存在");
        LinkedHashSet<String> existing=new LinkedHashSet<>();for(Subscription s:subscriptions(entry))existing.add(s.name);
        StringBuilder block=new StringBuilder();block.append("  ").append(safeName).append(":\n").append("    <<: *BaseProvider\n").append("    url: '").append(yamlSingle(safeUrl)).append("'\n").append("    path: ./proxy_provider/").append(fileToken(safeName)).append(".yaml\n").append("    override:\n").append("      skip-cert-verify: false\n").append("      udp: true\n").append("      additional-suffix: ' [").append(yamlSingle(safeName)).append("]'\n");
        StringBuilder out=new StringBuilder();for(int i=0;i<lines.length;i++){if(i==section.end)out.append(block);out.append(appendProviderToUse(lines[i],existing,safeName)).append('\n');}if(section.end==lines.length)out.append(block);write(entry,trimTerminal(out.toString()));
    }

    void deleteSubscription(Entry entry,String name)throws IOException{
        String safeName=providerName(name);String source=normalize(read(entry));String[] lines=source.split("\n",-1);Section section=providerSection(lines);if(section==null)throw new IOException("当前配置没有 proxy-providers 段");List<Subscription> all=subscriptions(entry);if(all.size()<=1)throw new IOException("至少保留一个订阅槽位；也可以直接使用 YAML 编辑器重构配置");int start=findProvider(lines,section,safeName);if(start<0)throw new IOException("订阅不存在："+safeName);int end=providerEnd(lines,section,start);StringBuilder out=new StringBuilder();for(int i=0;i<lines.length;i++){if(i>=start&&i<end)continue;out.append(removeProviderFromUse(lines[i],safeName)).append('\n');}write(entry,trimTerminal(out.toString()));
    }

    private void writeStreamAtomic(File target,InputStream source)throws IOException{
        File d=target.getParentFile();if(d!=null&&!d.isDirectory()&&!d.mkdirs())throw new IOException("无法创建配置目录");File tmp=new File(d,target.getName()+".new");int total=0;try(InputStream in=source;FileOutputStream out=new FileOutputStream(tmp,false)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("配置导入已取消");total+=n;if(total>LIMIT)throw new IOException("配置超过 4 MiB");out.write(b,0,n);}out.getFD().sync();}catch(IOException e){tmp.delete();throw e;}if(total==0){tmp.delete();throw new IOException("配置为空");}replaceAtomic(tmp,target);
    }
    private static void replaceAtomic(File tmp,File target)throws IOException{try{Files.move(tmp.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(Exception atomic){try{Files.move(tmp.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);}catch(Exception fallback){tmp.delete();throw new IOException("无法保存配置");}}}

    private static final class Section{final int start,end;Section(int start,int end){this.start=start;this.end=end;}}
    private static Section providerSection(String[] lines){int start=-1;for(int i=0;i<lines.length;i++){if(indent(lines[i])==0&&lines[i].trim().startsWith("proxy-providers:")){start=i;break;}}if(start<0)return null;int end=lines.length;for(int i=start+1;i<lines.length;i++){String t=lines[i].trim();if(t.isEmpty()||t.startsWith("#"))continue;if(indent(lines[i])==0){end=i;break;}}return new Section(start,end);}
    private static int findProvider(String[] lines,Section s,String name){for(int i=s.start+1;i<s.end;i++){String t=lines[i].trim();if(indent(lines[i])==2&&t.endsWith(":")&&unquote(t.substring(0,t.length()-1).trim()).equals(name))return i;}return -1;}
    private static int providerEnd(String[] lines,Section s,int start){for(int i=start+1;i<s.end;i++){String t=lines[i].trim();if(indent(lines[i])==2&&!t.isEmpty()&&!t.startsWith("#")&&t.endsWith(":"))return i;}return s.end;}

    private static String appendProviderToUse(String line,Set<String> existing,String add){String t=line.trim();if(!t.startsWith("use:"))return line;int l=line.indexOf('['),r=line.indexOf(']',l+1);if(l<0||r<0)return line;String inside=line.substring(l+1,r);ArrayList<String> names=parseInlineList(inside);boolean managed=false;for(String n:names)if(existing.contains(n)){managed=true;break;}if(!managed||names.contains(add))return line;names.add(add);return line.substring(0,l+1)+String.join(", ",names)+line.substring(r);}
    private static String removeProviderFromUse(String line,String remove){String t=line.trim();if(!t.startsWith("use:"))return line;int l=line.indexOf('['),r=line.indexOf(']',l+1);if(l<0||r<0)return line;ArrayList<String> names=parseInlineList(line.substring(l+1,r));if(!names.removeIf(remove::equals))return line;return line.substring(0,l+1)+String.join(", ",names)+line.substring(r);}
    private static ArrayList<String> parseInlineList(String s){ArrayList<String> out=new ArrayList<>();for(String p:s.split(",")){String n=unquote(p.trim());if(!n.isEmpty())out.add(n);}return out;}

    static boolean coreAccepts(ProxyRuntimeProfile.Core core,String name){if(name==null)return false;int dot=name.lastIndexOf('.');return dot>0&&core.extensions.contains(name.substring(dot+1).toLowerCase(Locale.ROOT));}
    static String safeName(String name)throws IOException{if(name==null)throw new IOException("配置名称无效");String n=name.trim();if(n.length()<3||n.length()>120||n.contains("/")||n.contains("\\")||n.contains("\u0000")||n.equals(".")||n.equals(".."))throw new IOException("配置名称无效");return n;}
    private static String providerName(String value)throws IOException{String n=value==null?"":value.trim();if(n.isEmpty()||n.length()>40||n.contains(":")||n.contains("[")||n.contains("]")||n.contains("{")||n.contains("}")||n.contains("#")||n.contains("\n")||n.contains("\r")||n.contains(","))throw new IOException("订阅名称无效");return n;}
    private static String subscriptionUrl(String value)throws IOException{String u=value==null?"":value.trim();if(u.length()<10||u.length()>4096||!(u.startsWith("https://")||u.startsWith("http://"))||u.contains("\n")||u.contains("\r"))throw new IOException("请输入有效的 http/https 订阅链接");return u;}
    private static String fileToken(String s){return s.replaceAll("[^A-Za-z0-9\\p{L}._-]","_");}
    private static String yamlSingle(String s){return s.replace("'","''");}
    private static String stripComment(String s){boolean single=false,dbl=false;for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c=='\''&&!dbl)single=!single;else if(c=='\"'&&!single)dbl=!dbl;else if(c=='#'&&!single&&!dbl)return s.substring(0,i).trim();}return s.trim();}
    private static String unquote(String s){String x=s==null?"":s.trim();if(x.length()>=2&&((x.startsWith("'")&&x.endsWith("'"))||(x.startsWith("\"")&&x.endsWith("\""))))x=x.substring(1,x.length()-1);return x.replace("''","'");}
    private static int indent(String s){int n=0;while(n<s.length()&&(s.charAt(n)==' '||s.charAt(n)=='\t'))n++;return n;}
    private static String normalize(String s){return s.replace("\r\n","\n").replace('\r','\n');}
    private static String trimTerminal(String s){while(s.endsWith("\n\n"))s=s.substring(0,s.length()-1);return s;}
    private static final class CollatorHolder{static final java.text.Collator ORDER=java.text.Collator.getInstance(Locale.CHINA);}
}
