package io.github.xgl34222220.hetu;

import android.content.*;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

/** Multiple source configurations per core. Runtime generation never rewrites the selected source file. */
final class ProxyConfigLibrary {
    static final String BUNDLED_NAME="河图内置-TPROXY.yaml";
    private static final String PLACEHOLDER="example.invalid/hetu-subscription-";
    private static final int LIMIT=4*1024*1024;
    private final File root;
    private final SharedPreferences prefs;
    ProxyConfigLibrary(Context c){Context app=c.getApplicationContext();root=new File(app.getFilesDir(),"proxy/configs");prefs=app.getSharedPreferences("hetu",Context.MODE_PRIVATE);}

    static final class Entry {final ProxyRuntimeProfile.Core core;final String name;final File file;Entry(ProxyRuntimeProfile.Core c,String n,File f){core=c;name=n;file=f;}}
    static final class Subscription {final String name,url;final boolean placeholder;Subscription(String n,String u){name=n;url=u;placeholder=u.contains(PLACEHOLDER);}}
    private static final class Section {final int start,end;Section(int s,int e){start=s;end=e;}}
    private static final class CollatorHolder {static final java.text.Collator ORDER=java.text.Collator.getInstance(Locale.CHINA);}

    private File dir(ProxyRuntimeProfile.Core core){return new File(root,core.id);}
    private String key(ProxyRuntimeProfile.Core core){return "proxySelectedConfig."+core.id;}

    private void ensureBundled(ProxyRuntimeProfile.Core core){
        if(core!=ProxyRuntimeProfile.Core.MIHOMO&&core!=ProxyRuntimeProfile.Core.MIHOMO_SMART)return;
        try{
            File d=dir(core);if(!d.isDirectory()&&!d.mkdirs())return;File target=new File(d,BUNDLED_NAME);
            if(!target.isFile())try(InputStream in=BundledProxyConfig.open()){writeStreamAtomic(target,in);}
            String selected=prefs.getString(key(core),"");if(selected.isEmpty()||!new File(d,selected).isFile())prefs.edit().putString(key(core),BUNDLED_NAME).apply();
        }catch(Exception ignored){}
    }

    List<Entry> list(ProxyRuntimeProfile.Core core){ensureBundled(core);File[] files=dir(core).listFiles(File::isFile);ArrayList<Entry> out=new ArrayList<>();if(files!=null)for(File f:files)if(coreAccepts(core,f.getName()))out.add(new Entry(core,f.getName(),f));out.sort(Comparator.comparing(e->e.name,CollatorHolder.ORDER));return out;}
    Entry selected(ProxyRuntimeProfile.Core core){ensureBundled(core);String n=prefs.getString(key(core),"");if(n.isEmpty())return null;File f=new File(dir(core),n);return f.isFile()&&coreAccepts(core,n)?new Entry(core,n,f):null;}
    void select(ProxyRuntimeProfile.Core core,String name)throws IOException{ensureBundled(core);String n=safeName(name);File f=new File(dir(core),n);if(!f.isFile()||!coreAccepts(core,n))throw new IOException("配置不存在或格式不属于当前核心");prefs.edit().putString(key(core),n).apply();}

    Entry importConfig(ProxyRuntimeProfile.Core core,String requestedName,InputStream source)throws IOException{
        if(source==null)throw new IOException("无法读取配置文件");String n=safeName(requestedName);if(!coreAccepts(core,n))throw new IOException(core.label+" 不支持该配置格式");File target=new File(dir(core),n);writeStreamAtomic(target,source);prefs.edit().putString(key(core),n).apply();return new Entry(core,n,target);
    }

    String read(Entry e)throws IOException{if(e==null||!e.file.isFile())throw new IOException("尚未选择配置");byte[] b=Files.readAllBytes(e.file.toPath());if(b.length>LIMIT)throw new IOException("配置超过 4 MiB");try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(b)).toString();}catch(CharacterCodingException x){throw new IOException("配置必须是 UTF-8 文本");}}
    void write(Entry e,String text)throws IOException{if(e==null)throw new IOException("尚未选择配置");if(text==null||text.trim().isEmpty())throw new IOException("配置不能为空");byte[] b=text.getBytes(StandardCharsets.UTF_8);if(b.length>LIMIT)throw new IOException("配置超过 4 MiB");File d=e.file.getParentFile();if(d!=null&&!d.isDirectory()&&!d.mkdirs())throw new IOException("无法创建配置目录");File tmp=new File(d,e.file.getName()+".new");try(FileOutputStream out=new FileOutputStream(tmp,false)){out.write(b);out.getFD().sync();}catch(IOException x){tmp.delete();throw x;}replaceAtomic(tmp,e.file);}
    void delete(Entry e)throws IOException{if(e==null)return;if(BUNDLED_NAME.equals(e.name))throw new IOException("内置配置不能删除，可以直接编辑或导入其他配置");if(e.file.exists()&&!e.file.delete())throw new IOException("无法删除配置");if(e.name.equals(prefs.getString(key(e.core),"")))prefs.edit().remove(key(e.core)).apply();}

    List<Subscription> subscriptions(Entry e)throws IOException{
        String[] lines=normalize(read(e)).split("\n",-1);Section s=providerSection(lines);ArrayList<Subscription> out=new ArrayList<>();if(s==null)return out;
        for(int i=s.start+1;i<s.end;i++){String t=lines[i].trim();if(indent(lines[i])!=2||t.isEmpty()||t.startsWith("#")||!t.endsWith(":"))continue;String name=unquote(t.substring(0,t.length()-1));String url="";for(int j=i+1;j<s.end;j++){String q=lines[j].trim();if(indent(lines[j])==2&&!q.isEmpty()&&!q.startsWith("#")&&q.endsWith(":"))break;if(indent(lines[j])>=4&&q.startsWith("url:")){url=unquote(stripComment(q.substring(4).trim()));break;}}if(!name.isEmpty()&&!url.isEmpty())out.add(new Subscription(name,url));}
        return out;
    }
    boolean hasConfiguredSubscription(Entry e)throws IOException{List<Subscription> s=subscriptions(e);if(s.isEmpty())return true;for(Subscription x:s)if(!x.placeholder)return true;return false;}

    void updateSubscription(Entry e,String name,String url)throws IOException{
        String n=providerName(name),u=subscriptionUrl(url);String[] lines=normalize(read(e)).split("\n",-1);Section s=providerSection(lines);if(s==null)throw new IOException("当前配置没有 proxy-providers 段");int begin=findProvider(lines,s,n);if(begin<0)throw new IOException("订阅不存在："+n);int end=providerEnd(lines,s,begin);boolean found=false;StringBuilder out=new StringBuilder();
        for(int i=0;i<lines.length;i++){String line=lines[i];if(i>begin&&i<end&&indent(line)>=4&&line.trim().startsWith("url:")){out.append(line,0,line.indexOf('u')).append("url: '").append(yamlSingle(u)).append("'\n");found=true;}else out.append(line).append('\n');}
        if(!found)throw new IOException("订阅缺少 url 字段，建议使用 YAML 编辑器修改");write(e,trimTerminal(out.toString()));
    }

    void addSubscription(Entry e,String name,String url)throws IOException{
        String n=providerName(name),u=subscriptionUrl(url),source=normalize(read(e));String[] lines=source.split("\n",-1);Section s=providerSection(lines);if(s==null)throw new IOException("当前配置没有 proxy-providers 段");if(findProvider(lines,s,n)>=0)throw new IOException("订阅名称已存在");LinkedHashSet<String> existing=new LinkedHashSet<>();for(Subscription x:subscriptions(e))existing.add(x.name);
        String block="  "+n+":\n    <<: *BaseProvider\n    url: '"+yamlSingle(u)+"'\n    path: ./proxy_provider/"+fileToken(n)+".yaml\n    override:\n      skip-cert-verify: false\n      udp: true\n      additional-suffix: ' ["+yamlSingle(n)+"]'\n";
        StringBuilder out=new StringBuilder();for(int i=0;i<lines.length;i++){if(i==s.end)out.append(block);out.append(appendProviderToUse(lines[i],existing,n)).append('\n');}if(s.end==lines.length)out.append(block);write(e,trimTerminal(out.toString()));
    }

    void deleteSubscription(Entry e,String name)throws IOException{
        String n=providerName(name);String[] lines=normalize(read(e)).split("\n",-1);Section s=providerSection(lines);if(s==null)throw new IOException("当前配置没有 proxy-providers 段");if(subscriptions(e).size()<=1)throw new IOException("至少保留一个订阅槽位；也可以直接使用 YAML 编辑器重构配置");int begin=findProvider(lines,s,n);if(begin<0)throw new IOException("订阅不存在："+n);int end=providerEnd(lines,s,begin);StringBuilder out=new StringBuilder();for(int i=0;i<lines.length;i++){if(i>=begin&&i<end)continue;out.append(removeProviderFromUse(lines[i],n)).append('\n');}write(e,trimTerminal(out.toString()));
    }

    private void writeStreamAtomic(File target,InputStream source)throws IOException{File d=target.getParentFile();if(d!=null&&!d.isDirectory()&&!d.mkdirs())throw new IOException("无法创建配置目录");File tmp=new File(d,target.getName()+".new");int total=0;try(InputStream in=source;FileOutputStream out=new FileOutputStream(tmp,false)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){total+=n;if(total>LIMIT)throw new IOException("配置超过 4 MiB");out.write(b,0,n);}out.getFD().sync();}catch(IOException x){tmp.delete();throw x;}if(total==0){tmp.delete();throw new IOException("配置为空");}replaceAtomic(tmp,target);}
    private static void replaceAtomic(File tmp,File target)throws IOException{try{Files.move(tmp.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(Exception a){try{Files.move(tmp.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);}catch(Exception b){tmp.delete();throw new IOException("无法保存配置");}}}

    private static Section providerSection(String[] l){int s=-1;for(int i=0;i<l.length;i++)if(indent(l[i])==0&&l[i].trim().startsWith("proxy-providers:")){s=i;break;}if(s<0)return null;int e=l.length;for(int i=s+1;i<l.length;i++){String t=l[i].trim();if(t.isEmpty()||t.startsWith("#"))continue;if(indent(l[i])==0){e=i;break;}}return new Section(s,e);}
    private static int findProvider(String[] l,Section s,String n){for(int i=s.start+1;i<s.end;i++){String t=l[i].trim();if(indent(l[i])==2&&t.endsWith(":")&&unquote(t.substring(0,t.length()-1)).equals(n))return i;}return -1;}
    private static int providerEnd(String[] l,Section s,int b){for(int i=b+1;i<s.end;i++){String t=l[i].trim();if(indent(l[i])==2&&!t.isEmpty()&&!t.startsWith("#")&&t.endsWith(":"))return i;}return s.end;}
    private static String appendProviderToUse(String line,Set<String> existing,String add){if(!line.trim().startsWith("use:"))return line;int a=line.indexOf('['),b=line.indexOf(']',a+1);if(a<0||b<0)return line;ArrayList<String> n=parseInlineList(line.substring(a+1,b));boolean managed=false;for(String x:n)if(existing.contains(x)){managed=true;break;}if(!managed||n.contains(add))return line;n.add(add);return line.substring(0,a+1)+String.join(", ",n)+line.substring(b);}
    private static String removeProviderFromUse(String line,String remove){if(!line.trim().startsWith("use:"))return line;int a=line.indexOf('['),b=line.indexOf(']',a+1);if(a<0||b<0)return line;ArrayList<String> n=parseInlineList(line.substring(a+1,b));if(!n.removeIf(remove::equals))return line;return line.substring(0,a+1)+String.join(", ",n)+line.substring(b);}
    private static ArrayList<String> parseInlineList(String s){ArrayList<String> o=new ArrayList<>();for(String p:s.split(",")){String n=unquote(p);if(!n.isEmpty())o.add(n);}return o;}

    static boolean coreAccepts(ProxyRuntimeProfile.Core core,String name){if(name==null)return false;int dot=name.lastIndexOf('.');return dot>0&&core.extensions.contains(name.substring(dot+1).toLowerCase(Locale.ROOT));}
    static String safeName(String name)throws IOException{String n=name==null?"":name.trim();if(n.length()<3||n.length()>120||n.contains("/")||n.contains("\\")||n.contains("\u0000")||n.equals(".")||n.equals(".."))throw new IOException("配置名称无效");return n;}
    private static String providerName(String v)throws IOException{String n=v==null?"":v.trim();if(n.isEmpty()||n.length()>40||n.matches(".*[:\\[\\]{}#,\\r\\n].*"))throw new IOException("订阅名称无效");return n;}
    private static String subscriptionUrl(String v)throws IOException{String u=v==null?"":v.trim();if(u.length()<10||u.length()>4096||!(u.startsWith("https://")||u.startsWith("http://"))||u.contains("\n")||u.contains("\r"))throw new IOException("请输入有效的 http/https 订阅链接");return u;}
    private static String fileToken(String s){return s.replaceAll("[^A-Za-z0-9\\p{L}._-]","_");}
    private static String yamlSingle(String s){return s.replace("'","''");}
    private static String stripComment(String s){boolean q1=false,q2=false;for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c=='\''&&!q2)q1=!q1;else if(c=='\"'&&!q1)q2=!q2;else if(c=='#'&&!q1&&!q2)return s.substring(0,i).trim();}return s.trim();}
    private static String unquote(String s){String x=s==null?"":s.trim();if(x.length()>=2&&((x.startsWith("'")&&x.endsWith("'"))||(x.startsWith("\"")&&x.endsWith("\""))))x=x.substring(1,x.length()-1);return x.replace("''","'");}
    private static int indent(String s){int n=0;while(n<s.length()&&(s.charAt(n)==' '||s.charAt(n)=='\t'))n++;return n;}
    private static String normalize(String s){return s.replace("\r\n","\n").replace('\r','\n');}
    private static String trimTerminal(String s){while(s.endsWith("\n\n"))s=s.substring(0,s.length()-1);return s;}
}
