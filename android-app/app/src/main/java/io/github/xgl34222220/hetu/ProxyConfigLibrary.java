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
    private static final Object WRITE_LOCK = new Object();
    private final File root;
    private final SharedPreferences prefs;
    ProxyConfigLibrary(Context c){
        Context app=c.getApplicationContext();
        File legacy=new File(app.getFilesDir(),"proxy/configs");
        root=new File(app.getFilesDir(),"hetu/configs");
        if(!root.exists()&&legacy.isDirectory()){
            File parent=root.getParentFile();
            if(parent!=null&&!parent.isDirectory())parent.mkdirs();
            legacy.renameTo(root);
        }
        prefs=app.getSharedPreferences("hetu",Context.MODE_PRIVATE);
    }

    static final class Entry {final ProxyRuntimeProfile.Core core;final String name;final File file;Entry(ProxyRuntimeProfile.Core c,String n,File f){core=c;name=n;file=f;}}
    static final class Subscription {final String name,url;final boolean placeholder;Subscription(String n,String u){name=n;url=u;placeholder=u.contains(PLACEHOLDER);}}
    private static final class Section {final int start,end;Section(int s,int e){start=s;end=e;}}
    private static final class CollatorHolder {static final java.text.Collator ORDER=java.text.Collator.getInstance(Locale.CHINA);}

    private File dir(ProxyRuntimeProfile.Core core){return new File(root,core.id);}
    private String key(ProxyRuntimeProfile.Core core){return "proxySelectedConfig."+core.id;}

    private void ensureBundled(ProxyRuntimeProfile.Core core){
        synchronized (WRITE_LOCK) {
            if(core!=ProxyRuntimeProfile.Core.MIHOMO&&core!=ProxyRuntimeProfile.Core.MIHOMO_SMART)return;
            try{
            File d=dir(core);if(!d.isDirectory()&&!d.mkdirs())return;File target=new File(d,BUNDLED_NAME);
            if(!target.isFile())try(InputStream in=BundledProxyConfig.open()){writeStreamAtomic(target,in);}
            String selected=prefs.getString(key(core),"");if(selected.isEmpty()||!new File(d,selected).isFile())prefs.edit().putString(key(core),BUNDLED_NAME).apply();
            }catch(Exception ignored){}
        }
    }

    List<Entry> list(ProxyRuntimeProfile.Core core){
        synchronized (WRITE_LOCK) {
            ensureBundled(core);File[] files=dir(core).listFiles(File::isFile);ArrayList<Entry> out=new ArrayList<>();if(files!=null)for(File f:files)if(coreAccepts(core,f.getName()))out.add(new Entry(core,f.getName(),f));out.sort(Comparator.comparing(e->e.name,CollatorHolder.ORDER));return out;
        }
    }
    Entry selected(ProxyRuntimeProfile.Core core){
        synchronized (WRITE_LOCK) {
            ensureBundled(core);String n=prefs.getString(key(core),"");if(n.isEmpty())return null;File f=new File(dir(core),n);return f.isFile()&&coreAccepts(core,n)?new Entry(core,n,f):null;
        }
    }
    void select(ProxyRuntimeProfile.Core core,String name)throws IOException{
        synchronized (WRITE_LOCK) {
            ensureBundled(core);String n=safeName(name);File f=new File(dir(core),n);if(!f.isFile()||!coreAccepts(core,n))throw new IOException("配置不存在或格式不属于当前核心");prefs.edit().putString(key(core),n).apply();
        }
    }

    Entry importConfig(ProxyRuntimeProfile.Core core,String requestedName,InputStream source)throws IOException{
        synchronized (WRITE_LOCK) {
            if(source==null)throw new IOException("无法读取配置文件");
            File d=dir(core);if(!d.isDirectory()&&!d.mkdirs())throw new IOException("无法创建配置目录");
            String requested=safeName(requestedName);
            if(!coreAccepts(core,requested))throw new IOException(core.label+" 不支持该配置格式");
            String n=uniqueName(d,requested);
            File target=new File(d,n);
            writeStreamAtomic(target,source);
            prefs.edit().putString(key(core),n).apply();
            return new Entry(core,n,target);
        }
    }

    static final class RestoreItem {
        final ProxyRuntimeProfile.Core core;
        final String name;
        final byte[] bytes;
        RestoreItem(ProxyRuntimeProfile.Core core, String name, byte[] bytes) {
            this.core=core;this.name=name;this.bytes=bytes.clone();
        }
    }
    interface RestoreCommit {
        void commit(Map<ProxyRuntimeProfile.Core,String> selections) throws IOException;
    }
    static final class RestoreCommitUncertain extends IOException {
        RestoreCommitUncertain(String message,Throwable cause){super(message,cause);}
    }
    private static final class RestoreWrite {
        final RestoreItem item;final File target;final Path staged;
        boolean installed;
        RestoreWrite(RestoreItem item,File target,Path staged){this.item=item;this.target=target;this.staged=staged;}
    }

    /** Restore without replacing existing sources or selecting each imported file.
     * All validation/staging precedes installation. Ordinary library operations share
     * this lock, including CAS and rename. A crash can leave extra restored/staged
     * files: this is not a crash-atomic transaction across files and preferences.
     * Original files are never overwritten, so they do not depend on rollback.
     */
    void restoreBatch(List<RestoreItem> items, Map<ProxyRuntimeProfile.Core,String> selections,
                      RestoreCommit commit) throws IOException {
        synchronized (WRITE_LOCK) {
            Set<String> identities=new HashSet<>();
            Map<ProxyRuntimeProfile.Core,Map<String,String>> names=new EnumMap<>(ProxyRuntimeProfile.Core.class);
            List<RestoreItem> copies=new ArrayList<>();List<File> targets=new ArrayList<>();
            Set<File> reserved=new HashSet<>();
            for(RestoreItem item:items){
                if(item==null||item.core==null||!safeName(item.name).equals(item.name)||!coreAccepts(item.core,item.name))
                    throw new IOException("备份配置名称或格式无效");
                if(!identities.add(item.core.id+"/"+item.name))throw new IOException("备份包含重复配置");
                if(item.bytes.length==0||item.bytes.length>LIMIT)throw new IOException("备份配置大小无效");
                try{if(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(item.bytes)).toString().trim().isEmpty())throw new IOException("备份配置为空");}
                catch(CharacterCodingException invalid){throw new IOException("配置必须是 UTF-8 文本",invalid);}
                File target=new File(dir(item.core),item.name);
                boolean identical=sameBytes(target,item.bytes);
                if(!identical){
                    target=restoreTarget(dir(item.core),item.name,item.bytes,reserved);
                    if(!sameBytes(target,item.bytes)){copies.add(item);targets.add(target);reserved.add(target);}
                }
                names.computeIfAbsent(item.core,ignored->new HashMap<>()).put(item.name,target.getName());
            }
            Map<ProxyRuntimeProfile.Core,String> resolved=new EnumMap<>(ProxyRuntimeProfile.Core.class);
            for(Map.Entry<ProxyRuntimeProfile.Core,String> selection:selections.entrySet()){
                ProxyRuntimeProfile.Core core=selection.getKey();String name=selection.getValue();
                if(core==null||name==null)throw new IOException("备份中的已选配置无效");
                if(name.isEmpty()){resolved.put(core,name);continue;}
                if(!safeName(name).equals(name)||!coreAccepts(core,name))throw new IOException("备份中的已选配置无效");
                String mapped=names.getOrDefault(core,Collections.emptyMap()).get(name);
                if(mapped==null){
                    File target=new File(dir(core),name);
                    if(!target.isFile()||Files.isSymbolicLink(target.toPath()))throw new IOException("备份中的已选配置不存在："+name);
                    mapped=name;
                }
                resolved.put(core,mapped);
            }
            if(copies.isEmpty()){commit.commit(Collections.unmodifiableMap(resolved));return;}
            if(!root.isDirectory()&&!root.mkdirs())throw new IOException("无法创建配置目录");
            Path staging=Files.createTempDirectory(root.toPath(),".restore-");
            List<RestoreWrite> writes=new ArrayList<>();
            try{
                for(int i=0;i<copies.size();i++){
                    RestoreItem item=copies.get(i);File target=targets.get(i);
                    File parent=target.getParentFile();
                    if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("无法创建配置目录");
                    Path staged=staging.resolve(Integer.toString(i));
                    writes.add(new RestoreWrite(item,target,staged));
                    try(FileOutputStream out=new FileOutputStream(staged.toFile())){out.write(item.bytes);out.getFD().sync();}
                }
                for(RestoreWrite write:writes){
                    // A hard link atomically creates a new name and fails if anyone has
                    // taken it. ATOMIC_MOVE can replace an existing target on Unix.
                    Files.createLink(write.target.toPath(),write.staged);
                    write.installed=true;
                }
                commit.commit(Collections.unmodifiableMap(resolved));
            }catch(IOException|RuntimeException failure){
                for(int i=writes.size()-1;!(failure instanceof RestoreCommitUncertain)&&i>=0;i--){
                    RestoreWrite write=writes.get(i);if(!write.installed)continue;
                    try{
                        if(!Files.isSymbolicLink(write.target.toPath())&&Files.isSameFile(write.staged,write.target.toPath())
                                &&sameBytes(write.target,write.item.bytes))
                            Files.delete(write.target.toPath());
                        else failure.addSuppressed(new IOException("恢复期间配置已变化，保留文件："+write.target.getName()));
                    }catch(IOException rollback){failure.addSuppressed(rollback);}
                }
                throw failure;
            }finally{
                // These are private staging links only. Failed cleanup leaves copies,
                // never the only copy of a pre-existing user configuration.
                for(RestoreWrite write:writes)try{Files.deleteIfExists(write.staged);}catch(IOException ignored){}
                try{Files.deleteIfExists(staging);}catch(IOException ignored){}
            }
        }
    }

    private static boolean sameBytes(File target,byte[] bytes)throws IOException {
        if(bytes.length>LIMIT||!target.isFile()||Files.isSymbolicLink(target.toPath())||target.length()!=bytes.length)return false;
        // A concurrent in-place edit can grow the file after length() is checked.
        // Compare at most the expected (bounded) bytes plus one EOF probe.
        try(InputStream in=Files.newInputStream(target.toPath(),LinkOption.NOFOLLOW_LINKS)){
            byte[] buffer=new byte[8192];int offset=0;
            while(offset<bytes.length){
                int count=in.read(buffer,0,Math.min(buffer.length,bytes.length-offset));
                if(count<0)return false;
                for(int i=0;i<count;i++)if(buffer[i]!=bytes[offset+i])return false;
                offset+=count;
            }
            return in.read()==-1;
        }
    }

    private static File restoreTarget(File dir,String requested,byte[] bytes,Set<File> reserved)throws IOException {
        File target=new File(dir,requested);
        if(!reserved.contains(target)&&(!Files.exists(target.toPath(),LinkOption.NOFOLLOW_LINKS)||sameBytes(target,bytes)))return target;
        int dot=requested.lastIndexOf('.');String base=requested.substring(0,dot),ext=requested.substring(dot);
        for(int i=2;i<=999;i++){
            String suffix=" ("+i+")"+ext;
            String candidate=base.substring(0,Math.min(base.length(),120-suffix.length()))+suffix;
            target=new File(dir,candidate);
            if(!reserved.contains(target)&&(!Files.exists(target.toPath(),LinkOption.NOFOLLOW_LINKS)||sameBytes(target,bytes)))return target;
        }
        throw new IOException("同名配置过多，请先整理配置库");
    }

    String read(Entry e)throws IOException{
        synchronized (WRITE_LOCK) {
            if(e==null||!e.file.isFile())throw new IOException("尚未选择配置");byte[] b=Files.readAllBytes(e.file.toPath());if(b.length>LIMIT)throw new IOException("配置超过 4 MiB");try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(b)).toString();}catch(CharacterCodingException x){throw new IOException("配置必须是 UTF-8 文本");}
        }
    }
    void write(Entry e,String text)throws IOException {
        synchronized (WRITE_LOCK) {if(e==null)throw new IOException("尚未选择配置");if(text==null||text.trim().isEmpty())throw new IOException("配置不能为空");byte[] b=text.getBytes(StandardCharsets.UTF_8);if(b.length>LIMIT)throw new IOException("配置超过 4 MiB");File d=e.file.getParentFile();if(d!=null&&!d.isDirectory()&&!d.mkdirs())throw new IOException("无法创建配置目录");File tmp=new File(d,e.file.getName()+".new");try(FileOutputStream out=new FileOutputStream(tmp,false)){out.write(b);out.getFD().sync();}catch(IOException x){tmp.delete();throw x;}replaceAtomic(tmp,e.file);}
    }

    /** Compare and replace under the same lock used by ordinary in-process writes. */
    void writeIfUnchanged(Entry entry, ConfigEditSnapshot snapshot, String text) throws IOException {
        synchronized (WRITE_LOCK) {
            if (entry == null) throw new IOException("尚未选择配置");
            snapshot.requireUnchanged(entry.core.id, entry.name, read(entry));
            write(entry, text);
        }
    }

    void delete(Entry e)throws IOException{
        synchronized (WRITE_LOCK) {
            if(e==null)return;
            if(BUNDLED_NAME.equals(e.name))throw new IOException("内置配置不能删除，可以切换到其他配置");
            if(e.file.exists()&&!e.file.delete())throw new IOException("无法删除配置");
            if(e.name.equals(prefs.getString(key(e.core),"")))prefs.edit().remove(key(e.core)).apply();
            ensureBundled(e.core);
        }
    }
    Entry rename(Entry e,String requestedName)throws IOException{
        synchronized (WRITE_LOCK) {
        if(e==null||!e.file.isFile())throw new IOException("配置不存在");
        if(BUNDLED_NAME.equals(e.name))throw new IOException("内置配置不能重命名");
        String n=safeName(requestedName);
        if(!coreAccepts(e.core,n))throw new IOException(e.core.label+" 不支持该配置格式");
        if(n.equals(e.name))return e;
        File target=new File(e.file.getParentFile(),n);
        if(target.exists())throw new IOException("同名配置已存在");
        try{Files.move(e.file.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE);}
        catch(Exception first){try{Files.move(e.file.toPath(),target.toPath());}catch(Exception second){throw new IOException("无法重命名配置");}}
        if(e.name.equals(prefs.getString(key(e.core),"")))prefs.edit().putString(key(e.core),n).apply();
        return new Entry(e.core,n,target);
            }
    }

    List<Subscription> subscriptions(Entry e)throws IOException{
        synchronized (WRITE_LOCK) {
            YamlSource source=new YamlSource(read(e));
            ArrayList<Subscription> out=new ArrayList<>();
            for(Provider provider:source.providers()){
                Field field=provider.field("url");
                if(field==null)continue;
                String url=source.scalar(field);
                if(!url.isEmpty())out.add(new Subscription(provider.name,url));
            }
            return out;
        }
    }
    boolean hasConfiguredSubscription(Entry e)throws IOException{
        synchronized (WRITE_LOCK) {
            List<Subscription> s=subscriptions(e);if(s.isEmpty())return true;for(Subscription x:s)if(!x.placeholder)return true;return false;
        }
    }

    void updateSubscription(Entry e,String name,String url)throws IOException{
        synchronized (WRITE_LOCK) {
            String n=providerName(name),u=subscriptionUrl(url);
            YamlSource source=new YamlSource(read(e));
            Provider provider=source.provider(n);
            Field field=provider.field("url");
            if(field==null)throw unsupported("订阅没有直接 url 字段（可能来自模板继承）");
            // Replace this field's spelling, including a scalar alias, never its anchor.
            source.scalar(field);
            source.edit(field.start,field.end,"'"+yamlSingle(u)+"'");
            write(e,source.result());
        }
    }

    void addSubscription(Entry e,String name,String url)throws IOException{
        synchronized (WRITE_LOCK) {
            String n=providerName(name),u=subscriptionUrl(url);
            YamlSource source=new YamlSource(read(e));
            List<Provider> providers=source.providers();
            LinkedHashSet<String> existing=new LinkedHashSet<>();
            for(Provider provider:providers){if(provider.name.equals(n))throw new IOException("订阅名称已存在");existing.add(provider.name);}
            Section section=source.section("proxy-providers");
            if(section==null)throw new IOException("当前配置没有 proxy-providers 段");
            source.editUses(existing,n,false);
            int depth=providers.isEmpty()?2:source.lines.get(providers.get(0).begin).indent;
            String p=spaces(depth),q=spaces(depth+2),r=spaces(depth+4),nl=source.newline;
            String block=p+"'"+yamlSingle(n)+"':"+nl;
            if(source.hasBaseProvider())block+=q+"<<: *BaseProvider"+nl;
            else block+=q+"type: http"+nl+q+"interval: 86400"+nl;
            block+=q+"url: '"+yamlSingle(u)+"'"+nl+q+"path: './proxy_provider/"+yamlSingle(fileToken(n))+".yaml'"+nl
                    +q+"override:"+nl+r+"skip-cert-verify: false"+nl+r+"udp: true"+nl+r+"additional-suffix: ' ["+yamlSingle(n)+"]'"+nl;
            Field header=source.lines.get(section.start).field();
            if("{}".equals(header.value))source.edit(header.start,header.end,"");
            int at=section.end==source.lines.size()?source.text.length():source.lines.get(section.end).start;
            source.edit(at,at,(at>0&&source.text.charAt(at-1)!='\n'&&source.text.charAt(at-1)!='\r'?nl:"")+block);
            write(e,source.result());
        }
    }

    void deleteSubscription(Entry e,String name)throws IOException{
        synchronized (WRITE_LOCK) {
            String n=providerName(name);YamlSource source=new YamlSource(read(e));
            Provider provider=source.provider(n);
            if(subscriptions(e).size()<=1)throw new IOException("至少保留一个订阅槽位；也可以直接使用 YAML 编辑器重构配置");
            if(source.hasReferencedAnchor(provider.begin,provider.end))
                throw unsupported("其他配置仍引用这个订阅的锚点");
            source.editUses(Collections.singleton(n),n,true);
            for(int i=provider.begin;i<provider.end;i++)source.removeLine(source.lines.get(i));
            write(e,source.result());
        }
    }

    private static String uniqueName(File dir,String requested)throws IOException{
        File direct=new File(dir,requested);if(!direct.exists())return requested;
        int dot=requested.lastIndexOf('.');
        String base=dot>0?requested.substring(0,dot):requested;
        String ext=dot>0?requested.substring(dot):"";
        for(int i=2;i<=999;i++){
            String candidate=base+" ("+i+")"+ext;
            if(!new File(dir,candidate).exists())return candidate;
        }
        throw new IOException("同名配置过多，请先整理配置库");
    }

    private void writeStreamAtomic(File target,InputStream source)throws IOException{
        synchronized (WRITE_LOCK) {
            File d=target.getParentFile();if(d!=null&&!d.isDirectory()&&!d.mkdirs())throw new IOException("无法创建配置目录");File tmp=new File(d,target.getName()+".new");int total=0;try(InputStream in=source;FileOutputStream out=new FileOutputStream(tmp,false)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){total+=n;if(total>LIMIT)throw new IOException("配置超过 4 MiB");out.write(b,0,n);}out.getFD().sync();}catch(IOException x){tmp.delete();throw x;}if(total==0){tmp.delete();throw new IOException("配置为空");}replaceAtomic(tmp,target);
        }
    }
    private static void replaceAtomic(File tmp,File target)throws IOException{try{Files.move(tmp.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(Exception a){try{Files.move(tmp.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);}catch(Exception b){tmp.delete();throw new IOException("无法保存配置");}}}

    private static IOException unsupported(String detail){return new IOException(detail+"；请使用 YAML 编辑器修改，源文件未改动");}
    private static String spaces(int count){return String.join("",Collections.nCopies(count," "));}
    private static final class Edit {
        final int start,end;final String value;
        Edit(int start,int end,String value){this.start=start;this.end=end;this.value=value;}
    }
    private static final class Field {
        final String key,value;final int start,end,colon;
        Field(String key,String value,int start,int end,int colon){this.key=key;this.value=value;this.start=start;this.end=end;this.colon=colon;}
    }
    private static final class Line {
        final String text;final int start,end,indent;
        Line(String text,int start,int end){this.text=text;this.start=start;this.end=end;int n=0;while(n<text.length()&&text.charAt(n)==' ')n++;indent=n;}
        boolean empty(){return text.trim().isEmpty()||text.trim().startsWith("#");}
        Field field()throws IOException{return field(indent);}
        Field field(int offset)throws IOException{
            String code=text.substring(0,comment(text));int colon=separator(code,':',offset);
            if(colon<0)return null;
            String key=literal(code.substring(offset,colon));int a=colon+1,b=code.length();
            while(a<b&&Character.isWhitespace(code.charAt(a)))a++;
            while(b>a&&Character.isWhitespace(code.charAt(b-1)))b--;
            return new Field(key,code.substring(a,b),start+a,start+b,start+colon);
        }
    }
    private static final class Provider {
        final String name,anchor;final int begin,end;final List<Field> fields;
        Provider(String name,String anchor,int begin,int end,List<Field> fields){this.name=name;this.anchor=anchor;this.begin=begin;this.end=end;this.fields=fields;}
        Field field(String key)throws IOException{Field found=null;for(Field f:fields)if(f.key.equals(key)){if(found!=null)throw unsupported("订阅包含重复字段");found=f;}return found;}
    }

    /** Narrow lossless editor for block provider mappings and scalar use sequences.
     * Mapping keys, quoted scalars and flow collections must fit on one line;
     * block scalars and duplicate anchors are deliberately rejected. Unsupported
     * shapes fail before write; the full source is never reserialized.
     */
    private static final class YamlSource {
        final String text,newline;final List<Line> lines=new ArrayList<>();final List<Edit> edits=new ArrayList<>();
        YamlSource(String text)throws IOException{
            this.text=text;String nl="\n";int start=0;
            for(int i=0;i<text.length();i++)if(text.charAt(i)=='\r'||text.charAt(i)=='\n'){
                int end=i+1;if(text.charAt(i)=='\r'&&end<text.length()&&text.charAt(end)=='\n')end++;
                if(lines.isEmpty())nl=text.substring(i,end);
                lines.add(new Line(text.substring(start,i),start,end));start=end;i=end-1;
            }
            if(start<text.length())lines.add(new Line(text.substring(start),start,text.length()));newline=nl;
            Set<String> anchors=new HashSet<>();
            java.util.regex.Pattern declarations=java.util.regex.Pattern.compile("(?:^|[\\s\\[\\{,:])&([^\\s,\\[\\]{}]+)");
            for(Line line:lines){
                if(line.indent<line.text.length()&&line.text.charAt(line.indent)=='\t')throw unsupported("暂不支持制表符缩进");
                if(line.empty())continue;
                String code=line.text.substring(0,comment(line.text));
                // This editor has no multiline lexical state. Refuse the header
                // before any following scalar text can be mistaken for structure.
                separator(code,'\0',0);
                String visible=unquoted(code);ArrayDeque<Character> brackets=new ArrayDeque<>();
                for(int i=0;i<visible.length();i++){
                    char c=visible.charAt(i);
                    if(c=='['||c=='{')brackets.push(c);
                    else if(c==']'||c=='}'){
                        if(brackets.isEmpty()||brackets.pop()!=(c==']'?'[':'{'))throw unsupported("暂不支持跨行或不匹配的行内集合");
                    }
                }
                if(!brackets.isEmpty())throw unsupported("暂不支持跨行的行内集合");
                String keyCode=code.trim();while(keyCode.startsWith("- "))keyCode=keyCode.substring(2).trim();
                if(keyCode.equals("?")||keyCode.startsWith("? "))throw unsupported("暂不支持显式或跨行 YAML 键");
                Field field=line.field();String value=field==null?code.trim():field.value;
                while(value.startsWith("- "))value=value.substring(2).trim();
                while(value.startsWith("&")||value.startsWith("!")){
                    int space=value.indexOf(' ');if(space<0)break;value=value.substring(space+1).trim();
                }
                if(value.startsWith("|")||value.startsWith(">"))throw unsupported("暂不支持含跨行块标量的配置");
                java.util.regex.Matcher declaration=declarations.matcher(visible);
                while(declaration.find())if(!anchors.add(declaration.group(1)))throw unsupported("配置包含重复锚点");
            }
        }
        Section section(String key)throws IOException{
            Section found=null;
            for(int i=0;i<lines.size();i++){
                Line line=lines.get(i);if(line.empty()||line.indent!=0)continue;Field field=line.field();
                if(field==null||!field.key.equals(key))continue;
                if(found!=null)throw unsupported("配置包含重复段落");
                int end=i+1;while(end<lines.size()&&(lines.get(end).empty()||lines.get(end).indent>0))end++;
                // YAML permits indentless sequences, including proxy-groups.
                while(end<lines.size()&&lines.get(end).text.startsWith("- ")){
                    end++;while(end<lines.size()&&(lines.get(end).empty()||lines.get(end).indent>0))end++;
                }
                found=new Section(i,end);
            }return found;
        }
        List<Provider> providers()throws IOException{
            Section section=section("proxy-providers");ArrayList<Provider> out=new ArrayList<>();if(section==null)return out;
            String header=lines.get(section.start).field().value;
            if(!header.isEmpty()&&!header.equals("{}")&&!header.matches("&[A-Za-z0-9_-]+"))throw unsupported("暂不支持行内或别名 proxy-providers 映射");
            int depth=-1;
            for(int i=section.start+1;i<section.end;){
                Line line=lines.get(i);if(line.empty()){i++;continue;}
                if(depth<0)depth=line.indent;
                if(depth==0||line.indent!=depth)throw unsupported("无法安全定位订阅映射缩进");
                Field heading=line.field();if(heading==null)throw unsupported("无法安全定位订阅名称");
                String anchor="";
                if(!heading.value.isEmpty()){
                    if(!heading.value.matches("&[A-Za-z0-9_-]+"))throw unsupported("暂不支持行内或别名订阅映射");
                    anchor=heading.value.substring(1);
                }
                for(Provider previous:out)if(previous.name.equals(heading.key))throw unsupported("配置包含重复订阅名称");
                int end=i+1;while(end<section.end&&(lines.get(end).empty()||lines.get(end).indent>depth))end++;
                out.add(new Provider(heading.key,anchor,i,end,fields(i+1,end,depth)));i=end;
            }return out;
        }
        List<Field> fields(int begin,int end,int parentDepth)throws IOException{
            ArrayList<Field> out=new ArrayList<>();int depth=Integer.MAX_VALUE;
            for(int i=begin;i<end;i++){Line line=lines.get(i);if(!line.empty()&&line.indent>parentDepth)depth=Math.min(depth,line.indent);}
            for(int i=begin;i<end;i++){Line line=lines.get(i);if(line.empty()||line.indent!=depth)continue;Field field=line.field();if(field==null)throw unsupported("无法安全定位 YAML 字段");out.add(field);}
            return out;
        }
        Provider provider(String name)throws IOException{for(Provider p:providers())if(p.name.equals(name))return p;throw new IOException("订阅不存在："+name);}
        String scalar(Field field)throws IOException{
            for(int i=0;i<lines.size();i++)if(field.start>=lines.get(i).start&&field.start<=lines.get(i).end){
                int next=i+1;while(next<lines.size()&&lines.get(next).empty())next++;
                if(next<lines.size()&&lines.get(next).indent>lines.get(i).indent)throw unsupported("暂不支持跨行 URL 标量");
                break;
            }
            String value=field.value;
            if(value.startsWith("*")){
                String anchor=value.substring(1);String found=null;
                if(!anchor.matches("[A-Za-z0-9_-]+"))throw unsupported("无法安全解析 URL 别名");
                for(Line line:lines){if(line.empty())continue;Field f=line.field();if(f!=null&&f.value.startsWith("&"+anchor+" ")){
                    if(found!=null)throw unsupported("配置包含重复锚点");found=literal(f.value.substring(anchor.length()+2));
                }}
                if(found==null)throw unsupported("无法安全解析 URL 锚点");return found;
            }
            if(value.startsWith("&"))throw unsupported("URL 本身声明了公共锚点");
            return literal(value);
        }
        boolean hasBaseProvider()throws IOException{
            Section section=section("BaseProvider");return section!=null&&section.start<section("proxy-providers").start
                    &&lines.get(section.start).field().value.equals("&BaseProvider")&&!fields(section.start+1,section.end,0).isEmpty();
        }
        boolean hasReferencedAnchor(int begin,int end)throws IOException{
            java.util.regex.Pattern pattern=java.util.regex.Pattern.compile("&([^\\s,\\[\\]{}]+)");
            for(int i=begin;i<end;i++){
                java.util.regex.Matcher matcher=pattern.matcher(unquoted(lines.get(i).text));
                while(matcher.find())if(hasAliasOutside(matcher.group(1),begin,end))return true;
            }return false;
        }
        boolean hasAliasOutside(String anchor,int begin,int end)throws IOException{
            for(int i=0;i<lines.size();i++)if(i<begin||i>=end){String code=unquoted(lines.get(i).text);if(java.util.regex.Pattern.compile("\\*"+java.util.regex.Pattern.quote(anchor)+"(?=$|[\\s,\\]\\}])").matcher(code).find())return true;}return false;
        }
        void edit(int start,int end,String value){edits.add(new Edit(start,end,value));}
        void removeLine(Line line)throws IOException{
            if(line.empty())return;
            int at=comment(line.text);String keep=at<line.text.length()?spaces(line.indent)+line.text.substring(at)+text.substring(line.start+line.text.length(),line.end):"";
            edit(line.start,line.end,keep);
        }
        void editUses(Set<String> existing,String name,boolean remove)throws IOException{
            LinkedHashSet<String> templates=new LinkedHashSet<>();
            Set<Integer> mergeLines=new HashSet<>();
            Section groups=section("proxy-groups");
            if(groups!=null){
                Field header=lines.get(groups.start).field();if(!header.value.isEmpty()&&!header.value.equals("[]"))throw unsupported("暂不支持行内或别名 proxy-groups");
                int itemDepth=-1,fieldDepth=-1;
                for(int i=groups.start+1;i<groups.end;i++){
                    Line line=lines.get(i);if(line.empty())continue;
                    String code=line.text.substring(line.indent);
                    if(itemDepth<0)itemDepth=line.indent;
                    if(line.indent==itemDepth){
                        if(!code.startsWith("- "))throw unsupported("无法安全定位策略组");
                        fieldDepth=itemDepth+2;
                        while(fieldDepth<line.text.length()&&line.text.charAt(fieldDepth)==' ')fieldDepth++;
                        Field first=line.field(fieldDepth);if(first==null||code.startsWith("- {"))throw unsupported("暂不支持行内或别名策略组");
                        if(first.key.equals("use"))editUse(i,first,fieldDepth,existing,name,remove);
                        if(first.key.equals("<<")){mergeAliases(first.value,templates);mergeLines.add(i);}
                    }else if(line.indent==fieldDepth){Field field=line.field();if(field!=null){
                        if(field.key.equals("use"))editUse(i,field,fieldDepth,existing,name,remove);
                        if(field.key.equals("<<")){mergeAliases(field.value,templates);mergeLines.add(i);}
                    }}
                }
            }
            // Follow only templates actually merged by groups. Unrelated anchored
            // data can also have a key named `use` and must not be modified.
            Set<String> visited=new HashSet<>();
            while(!templates.isEmpty()){
                String anchor=templates.iterator().next();templates.remove(anchor);if(!visited.add(anchor))continue;
                int match=-1;Field heading=null;
                for(int i=0;i<lines.size();i++){
                    Line line=lines.get(i);if(line.empty()||line.indent!=0)continue;Field candidate=line.field();
                    if(candidate!=null&&(candidate.value.equals("&"+anchor)||candidate.value.startsWith("&"+anchor+" "))){
                        if(match>=0)throw unsupported("策略模板包含重复锚点");match=i;heading=candidate;
                    }
                }
                if(match<0)throw unsupported("无法安全定位策略组继承模板");
                if(heading.value.equals("&"+anchor)){
                    Section section=section(heading.key);for(Field field:fields(match+1,section.end,0)){
                        if(field.key.equals("<<")){
                            mergeAliases(field.value,templates);
                            for(int j=match+1;j<section.end;j++)if(field.start>=lines.get(j).start&&field.start<=lines.get(j).end){mergeLines.add(j);break;}
                        }
                        if(field.key.equals("use"))for(int j=match+1;j<section.end;j++)if(field.start>=lines.get(j).start&&field.start<=lines.get(j).end){editUse(j,field,lines.get(j).indent,existing,name,remove);break;}
                    }
                }else {
                    String value=heading.value.substring(anchor.length()+1).trim();
                    if(!value.startsWith("{")||!value.endsWith("}")||unquoted(value).matches(".*(?:\\buse\\s*:|<<\\s*:).*")
                            ||java.util.regex.Pattern.compile("['\"][^'\"]*['\"]\\s*:").matcher(value).find())
                        throw unsupported("暂不支持策略模板中的复杂行内映射");
                }
            }
            // A shared template is safe to edit only when all its alias users
            // are the group merges (or their template merges) visited above.
            for(String anchor:visited){
                java.util.regex.Pattern alias=java.util.regex.Pattern.compile("\\*"+java.util.regex.Pattern.quote(anchor)+"(?=$|[\\s,\\]\\}])");
                for(int i=0;i<lines.size();i++)if(!mergeLines.contains(i)&&alias.matcher(unquoted(lines.get(i).text)).find())
                    throw unsupported("策略模板也被其他配置引用");
            }
        }
        void mergeAliases(String value,Set<String> templates)throws IOException{
            List<String> aliases=value.startsWith("[")&&value.endsWith("]")?splitList(value.substring(1,value.length()-1)):Collections.singletonList(value);
            for(String alias:aliases){String item=alias.trim();if(!item.matches("\\*[A-Za-z0-9_-]+"))throw unsupported("暂不支持该策略组继承结构");templates.add(item.substring(1));}
        }
        void editUse(int index,Field field,int depth,Set<String> existing,String name,boolean remove)throws IOException{
            String value=field.value;
            if(value.startsWith("[")){
                if(!value.endsWith("]"))throw unsupported("暂不支持跨行的行内 use 列表");
                String body=value.substring(1,value.length()-1);List<String> raw=splitList(body);List<String> kept=new ArrayList<>();boolean managed=false,contains=false;
                for(String part:raw){if(part.trim().isEmpty())continue;String item=literal(part);managed|=existing.contains(item);contains|=item.equals(name);if(!remove||!item.equals(name))kept.add(part);}
                if(remove&&contains)edit(field.start+1,field.end-1,String.join(",",kept));
                else if(!remove&&managed&&!contains){String separator=body.trim().isEmpty()?"":body.trim().endsWith(",")?" ":", ";edit(field.end-1,field.end-1,separator+"'"+yamlSingle(name)+"'");}
                return;
            }
            if(!value.isEmpty())throw unsupported("暂不支持别名或非列表 use 字段");
            ArrayList<Line> items=new ArrayList<>(),matches=new ArrayList<>();boolean managed=false,contains=false;int itemDepth=-1;
            for(int i=index+1;i<lines.size();i++){
                Line line=lines.get(i);if(line.empty())continue;String code=line.text.substring(line.indent);
                if(line.indent<depth||line.indent==depth&&!code.startsWith("- "))break;
                if(!code.startsWith("- "))throw unsupported("暂不支持 use 中的复杂节点");
                if(itemDepth<0)itemDepth=line.indent;else if(itemDepth!=line.indent)throw unsupported("无法安全定位 use 列表缩进");
                String item=literal(code.substring(2,comment(code)));items.add(line);managed|=existing.contains(item);contains|=item.equals(name);if(item.equals(name))matches.add(line);
            }
            if(remove&&!matches.isEmpty()){
                for(Line line:matches)removeLine(line);
                if(matches.size()==items.size())edit(field.colon+1,field.colon+1," []");
            }else if(!remove&&managed&&!contains){Line last=items.get(items.size()-1);int at=last.end;edit(at,at,(at>0&&text.charAt(at-1)!='\n'&&text.charAt(at-1)!='\r'?newline:"")+spaces(itemDepth)+"- '"+yamlSingle(name)+"'"+newline);}
        }
        String result()throws IOException{
            edits.sort((a,b)->Integer.compare(b.start,a.start));StringBuilder out=new StringBuilder(text);int boundary=text.length();
            for(Edit edit:edits){if(edit.end>boundary)throw unsupported("订阅和引用修改位置重叠");out.replace(edit.start,edit.end,edit.value);boundary=edit.start;}return out.toString();
        }
    }
    private static int comment(String value)throws IOException{
        boolean single=false,dbl=false;
        for(int i=0;i<value.length();i++){char c=value.charAt(i);
            if(dbl&&c=='\\'){i++;continue;}
            if(c=='\''&&!dbl){if(single&&i+1<value.length()&&value.charAt(i+1)=='\''){i++;continue;}single=!single;}
            else if(c=='"'&&!single)dbl=!dbl;
            else if(c=='#'&&!single&&!dbl&&(i==0||Character.isWhitespace(value.charAt(i-1))))return i;
        }return value.length();
    }
    private static int separator(String value,char wanted,int start)throws IOException{
        boolean single=false,dbl=false;
        for(int i=start;i<value.length();i++){char c=value.charAt(i);
            if(dbl&&c=='\\'){i++;continue;}
            if(c=='\''&&!dbl){if(single&&i+1<value.length()&&value.charAt(i+1)=='\''){i++;continue;}single=!single;}
            else if(c=='"'&&!single)dbl=!dbl;
            else if(c==wanted&&!single&&!dbl&&(wanted!= ':'||i+1==value.length()||Character.isWhitespace(value.charAt(i+1))))return i;
        }if(single||dbl)throw unsupported("暂不支持跨行引号标量");return -1;
    }
    private static List<String> splitList(String value)throws IOException{
        ArrayList<String> out=new ArrayList<>();int start=0,next;
        while((next=separator(value,',',start))>=0){out.add(value.substring(start,next));start=next+1;}out.add(value.substring(start));return out;
    }
    private static String unquoted(String value)throws IOException{
        StringBuilder out=new StringBuilder();boolean single=false,dbl=false;int end=comment(value);
        for(int i=0;i<end;i++){char c=value.charAt(i);if(dbl&&c=='\\'){i++;continue;}if(c=='\''&&!dbl){if(single&&i+1<end&&value.charAt(i+1)=='\''){i++;continue;}single=!single;}else if(c=='"'&&!single)dbl=!dbl;else if(!single&&!dbl)out.append(c);}return out.toString();
    }
    private static String literal(String value)throws IOException{
        String text=value.trim();if(text.isEmpty())return "";
        if(text.charAt(0)=='\''){
            if(text.length()<2||!text.endsWith("'"))throw unsupported("暂不支持跨行引号标量");
            String body=text.substring(1,text.length()-1);for(int i=0;i<body.length();i++)if(body.charAt(i)=='\''&&(++i>=body.length()||body.charAt(i)!='\''))throw unsupported("无法安全解析单引号标量");return body.replace("''","'");
        }
        if(text.charAt(0)=='"'){
            if(text.length()<2||!text.endsWith("\""))throw unsupported("暂不支持跨行引号标量");
            StringBuilder out=new StringBuilder();for(int i=1;i<text.length()-1;i++){char c=text.charAt(i);if(c=='"')throw unsupported("无法安全解析双引号标量");if(c!='\\'){out.append(c);continue;}if(++i>=text.length()-1)throw unsupported("无法安全解析转义");char escape=text.charAt(i);if(escape=='"'||escape=='\\'||escape=='/')out.append(escape);else if(escape=='x'||escape=='u'||escape=='U'){int length=escape=='x'?2:escape=='u'?4:8;try{int cp=Integer.parseInt(text.substring(i+1,i+1+length),16);out.appendCodePoint(cp);i+=length;}catch(RuntimeException invalid){throw unsupported("无法安全解析字符转义");}}else throw unsupported("暂不支持该双引号转义");}return out.toString();
        }
        if("[*&!{|>".indexOf(text.charAt(0))>=0||text.contains("\t")||separator(text,':',0)>=0)throw unsupported("暂不支持复杂 YAML 标量");return text;
    }

    static boolean coreAccepts(ProxyRuntimeProfile.Core core,String name){if(name==null)return false;int dot=name.lastIndexOf('.');return dot>0&&core.extensions.contains(name.substring(dot+1).toLowerCase(Locale.ROOT));}
    static String safeName(String name)throws IOException{String n=name==null?"":name.trim();if(n.length()<3||n.length()>120||n.contains("/")||n.contains("\\")||n.contains("\u0000")||n.equals(".")||n.equals(".."))throw new IOException("配置名称无效");return n;}
    private static String providerName(String v)throws IOException{String n=v==null?"":v.trim();if(n.isEmpty()||n.length()>40||n.chars().anyMatch(Character::isISOControl))throw new IOException("订阅名称无效");return n;}
    private static String subscriptionUrl(String v)throws IOException{String u=v==null?"":v.trim();if(u.length()<10||u.length()>4096||!(u.startsWith("https://")||u.startsWith("http://"))||u.chars().anyMatch(c->c<=32||c==127))throw new IOException("请输入有效的 http/https 订阅链接");return u;}
    private static String fileToken(String s){return s.replaceAll("[^A-Za-z0-9\\p{L}._-]","_");}
    private static String yamlSingle(String s){return s.replace("'","''");}
}
