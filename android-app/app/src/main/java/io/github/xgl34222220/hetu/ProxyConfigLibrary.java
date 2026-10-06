package io.github.xgl34222220.hetu;

import android.content.*;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.nodes.*;

/** Multiple source configurations per core. Runtime generation never rewrites the selected source file. */
final class ProxyConfigLibrary {
    static final String BUNDLED_NAME="河图内置-TPROXY.yaml";
    private static final String PLACEHOLDER="example.invalid/hetu-subscription-";
    private static final int LIMIT=4*1024*1024;
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

    String read(Entry e)throws IOException{if(e==null||!e.file.isFile())throw new IOException("尚未选择配置");byte[] b=Files.readAllBytes(e.file.toPath());if(b.length>LIMIT)throw new IOException("配置超过 4 MiB");try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(b)).toString();}catch(CharacterCodingException x){throw new IOException("配置必须是 UTF-8 文本");}}
    void write(Entry e,String text)throws IOException{if(e==null)throw new IOException("尚未选择配置");if(text==null||text.trim().isEmpty())throw new IOException("配置不能为空");byte[] b=text.getBytes(StandardCharsets.UTF_8);if(b.length>LIMIT)throw new IOException("配置超过 4 MiB");File d=e.file.getParentFile();if(d!=null&&!d.isDirectory()&&!d.mkdirs())throw new IOException("无法创建配置目录");File tmp=new File(d,e.file.getName()+".new");try(FileOutputStream out=new FileOutputStream(tmp,false)){out.write(b);out.getFD().sync();}catch(IOException x){tmp.delete();throw x;}replaceAtomic(tmp,e.file);}
    void delete(Entry e)throws IOException{
        if(e==null)return;
        if(BUNDLED_NAME.equals(e.name))throw new IOException("内置配置不能删除，可以切换到其他配置");
        if(e.file.exists()&&!e.file.delete())throw new IOException("无法删除配置");
        if(e.name.equals(prefs.getString(key(e.core),"")))prefs.edit().remove(key(e.core)).apply();
        ensureBundled(e.core);
    }
    Entry rename(Entry e,String requestedName)throws IOException{
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

    List<Subscription> subscriptions(Entry e)throws IOException{
        SubscriptionYaml yaml=new SubscriptionYaml(read(e));ArrayList<Subscription> out=new ArrayList<>();if(yaml.providers==null)return out;
        for(NodeTuple entry:yaml.providers.getValue()){String name=scalar(entry.getKeyNode()),url=scalar(RuntimeYaml15.inherited(entry.getValueNode(),"url"));if(!name.isEmpty()&&!url.isEmpty())out.add(new Subscription(name,url));}
        return out;
    }
    boolean hasConfiguredSubscription(Entry e)throws IOException{List<Subscription> s=subscriptions(e);if(s.isEmpty())return true;for(Subscription x:s)if(!x.placeholder)return true;return false;}

    void updateSubscription(Entry e,String name,String url)throws IOException{
        String n=providerName(name),u=subscriptionUrl(url);SubscriptionYaml yaml=new SubscriptionYaml(read(e));NodeTuple provider=yaml.provider(n);NodeTuple urlField=field(provider.getValueNode(),"url");
        if(urlField==null||!(urlField.getValueNode() instanceof ScalarNode))throw new IOException("订阅缺少独立 url 字段，请在 YAML 编辑器修改模板");
        Node value=urlField.getValueNode();yaml.local(value,provider.getValueNode());if(value.getStartMark().getLine()!=value.getEndMark().getLine()||yaml.references.getOrDefault(provider.getValueNode(),0)>1||yaml.references.getOrDefault(value,0)>1||yaml.index(provider.getValueNode().getStartMark())<yaml.index(provider.getKeyNode().getEndMark())||yaml.index(value.getStartMark())<yaml.index(urlField.getKeyNode().getEndMark()))throw unsupported();
        yaml.edits.add(new Edit(yaml.index(value.getStartMark()),yaml.index(value.getEndMark()),quote(u)));saveSubscription(e,yaml);
    }

    void addSubscription(Entry e,String name,String url)throws IOException{
        String n=providerName(name),u=subscriptionUrl(url);SubscriptionYaml yaml=new SubscriptionYaml(read(e));yaml.blockProviders();
        Set<String> existing=new LinkedHashSet<>();for(NodeTuple provider:yaml.providers.getValue())existing.add(scalar(provider.getKeyNode()));if(existing.contains(n))throw new IOException("订阅名称已存在");
        int at=yaml.index(yaml.providers.getEndMark());Node base=yaml.anchor("BaseProvider");
        if(base!=null&&(!(base instanceof MappingNode)||!"http".equals(scalar(RuntimeYaml15.inherited(base,"type")))||yaml.index(base.getEndMark())>at))throw unsupported();
        String indent=spaces(yaml.providers.getValue().get(0).getKeyNode().getStartMark().getColumn());String child=indent+"  ",newline=yaml.newline;
        String block=indent+quote(n)+":"+newline+child+(base==null?"type: http"+newline+child+"interval: 3600":"<<: *BaseProvider")+newline+child+"url: "+quote(u)+newline+child+"path: "+quote(yaml.path(n))+newline+child+"override:"+newline+child+"  skip-cert-verify: false"+newline+child+"  udp: true"+newline+child+"  additional-suffix: "+quote(" ["+n+"]")+newline;
        yaml.edits.add(new Edit(at,at,(at>0&&yaml.source.charAt(at-1)!='\n'&&yaml.source.charAt(at-1)!='\r'?newline:"")+block));
        yaml.changeGroupUses(existing,n,false);saveSubscription(e,yaml);
    }

    void deleteSubscription(Entry e,String name)throws IOException{
        String n=providerName(name);SubscriptionYaml yaml=new SubscriptionYaml(read(e));yaml.blockProviders();NodeTuple provider=yaml.provider(n);
        int subscriptions=0;for(NodeTuple entry:yaml.providers.getValue())if(!scalar(RuntimeYaml15.inherited(entry.getValueNode(),"url")).isEmpty())subscriptions++;
        if(subscriptions<=1)throw new IOException("至少保留一个订阅槽位；也可以直接使用 YAML 编辑器重构配置");
        int begin=yaml.lineStart(yaml.index(provider.getKeyNode().getStartMark())),end=yaml.entryEnd(provider);yaml.edits.add(new Edit(begin,end,""));
        yaml.changeGroupUses(Collections.singleton(n),n,true);saveSubscription(e,yaml);
    }

    private void saveSubscription(Entry entry,SubscriptionYaml yaml)throws IOException{
        String result=yaml.result();new SubscriptionYaml(result);
        if(!read(entry).equals(yaml.source))throw new IOException("配置已变化，请重新打开后重试；未修改源文件");
        write(entry,result);
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

    private void writeStreamAtomic(File target,InputStream source)throws IOException{File d=target.getParentFile();if(d!=null&&!d.isDirectory()&&!d.mkdirs())throw new IOException("无法创建配置目录");File tmp=new File(d,target.getName()+".new");int total=0;try(InputStream in=source;FileOutputStream out=new FileOutputStream(tmp,false)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){total+=n;if(total>LIMIT)throw new IOException("配置超过 4 MiB");out.write(b,0,n);}out.getFD().sync();}catch(IOException x){tmp.delete();throw x;}if(total==0){tmp.delete();throw new IOException("配置为空");}replaceAtomic(tmp,target);}
    private static void replaceAtomic(File tmp,File target)throws IOException{try{Files.move(tmp.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(Exception a){try{Files.move(tmp.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);}catch(Exception b){tmp.delete();throw new IOException("无法保存配置");}}}

    private static NodeTuple field(Node node,String name){if(node instanceof MappingNode)for(NodeTuple tuple:((MappingNode)node).getValue())if(name.equals(scalar(tuple.getKeyNode())))return tuple;return null;}
    private static String scalar(Node node){return node instanceof ScalarNode?((ScalarNode)node).getValue():"";}
    private static String quote(String value){return "'"+yamlSingle(value)+"'";}
    private static String spaces(int count){char[] value=new char[count];Arrays.fill(value,' ');return new String(value);}
    private static IOException unsupported(){return new IOException("此 YAML 使用共享别名或不能安全编辑的结构，请使用 YAML 编辑器；未修改源文件");}
    private static final class Edit {final int start,end;final String value;Edit(int s,int e,String v){start=s;end=e;value=v;}}

    /** Compose-only editing: marks locate the changed tokens, never dump or expand the document. */
    private static final class SubscriptionYaml {
        final String source,newline;final MappingNode root,providers;final List<Edit> edits=new ArrayList<>();
        final Map<Node,Integer> references=new IdentityHashMap<>();final Set<Node> nodes=Collections.newSetFromMap(new IdentityHashMap<>());
        SubscriptionYaml(String text)throws IOException{
            source=text;newline=text.contains("\r\n")?"\r\n":text.indexOf('\r')>=0?"\r":"\n";root=(MappingNode)RuntimeYaml15.compose(text);
            Deque<Node> pending=new ArrayDeque<>();pending.add(root);references.put(root,1);
            while(!pending.isEmpty()){
                Node node=pending.pop();if(!nodes.add(node))continue;
                if(node instanceof MappingNode){Set<String> keys=new HashSet<>();for(NodeTuple tuple:((MappingNode)node).getValue()){
                    if(tuple.getKeyNode() instanceof ScalarNode&&!keys.add(scalar(tuple.getKeyNode())))throw new IOException("配置包含重复 YAML 字段，请先在编辑器修正；未修改源文件");
                    visit(tuple.getKeyNode(),pending);visit(tuple.getValueNode(),pending);
                }}else if(node instanceof SequenceNode)for(Node child:((SequenceNode)node).getValue())visit(child,pending);
            }
            NodeTuple tuple=field(root,"proxy-providers");if(tuple==null)providers=null;else if(tuple.getValueNode() instanceof MappingNode)providers=(MappingNode)tuple.getValueNode();else throw unsupported();
            if(providers!=null)for(NodeTuple entry:providers.getValue())if(!(entry.getKeyNode() instanceof ScalarNode)||!(entry.getValueNode() instanceof MappingNode))throw unsupported();
        }
        private void visit(Node node,Deque<Node> pending){references.put(node,references.getOrDefault(node,0)+1);pending.add(node);}
        int index(Mark mark){return source.offsetByCodePoints(0,mark.getIndex());}
        int lineStart(int at){while(at>0&&source.charAt(at-1)!='\n'&&source.charAt(at-1)!='\r')at--;return at;}
        int lineEnd(int at){while(at<source.length()&&source.charAt(at)!='\n'&&source.charAt(at)!='\r')at++;if(at<source.length()&&source.charAt(at++)=='\r'&&at<source.length()&&source.charAt(at)=='\n')at++;return at;}
        void local(Node node,Node owner)throws IOException{if(index(node.getStartMark())<index(owner.getStartMark())||index(node.getEndMark())>index(owner.getEndMark()))throw unsupported();}
        Node anchor(String name)throws IOException{Node found=null;for(Node node:nodes)if(name.equals(node.getAnchor())){if(found!=null)throw unsupported();found=node;}return found;}
        void blockProviders()throws IOException{
            if(providers==null)throw new IOException("当前配置没有 proxy-providers 段");
            NodeTuple tuple=field(root,"proxy-providers");
            if(root.getFlowStyle()!=DumperOptions.FlowStyle.BLOCK||providers.getFlowStyle()!=DumperOptions.FlowStyle.BLOCK||references.getOrDefault(providers,0)>1||index(providers.getStartMark())<index(tuple.getKeyNode().getEndMark()))throw unsupported();
            for(NodeTuple entry:providers.getValue())if(!source.substring(lineStart(index(entry.getKeyNode().getStartMark())),index(entry.getKeyNode().getStartMark())).trim().isEmpty())throw unsupported();
        }
        NodeTuple provider(String name)throws IOException{if(providers==null)throw new IOException("当前配置没有 proxy-providers 段");for(NodeTuple entry:providers.getValue())if(name.equals(scalar(entry.getKeyNode())))return entry;throw new IOException("订阅不存在："+name);}
        String path(String name){Set<String> paths=new HashSet<>();for(NodeTuple entry:providers.getValue())paths.add(scalar(RuntimeYaml15.inherited(entry.getValueNode(),"path")));String token=fileToken(name),candidate="./proxy_provider/"+token+".yaml";for(int i=2;paths.contains(candidate);i++)candidate="./proxy_provider/"+token+"_"+i+".yaml";return candidate;}
        int entryEnd(NodeTuple entry)throws IOException{
            Node key=entry.getKeyNode(),value=entry.getValueNode();int begin=lineStart(index(key.getStartMark())),mark=index(value.getEndMark());
            int bound=mark<index(key.getEndMark())?lineEnd(index(key.getEndMark())):value instanceof MappingNode&&((MappingNode)value).getFlowStyle()==DumperOptions.FlowStyle.BLOCK?(mark==source.length()?mark:lineStart(mark)):lineEnd(mark);
            int end=begin;for(int at=begin;at<bound;){int next=Math.min(lineEnd(at),bound);String line=source.substring(at,next).trim();if(!line.isEmpty()&&!line.startsWith("#"))end=next;at=next;}
            if(end<=begin)throw unsupported();return end;
        }
        void changeGroupUses(Set<String> managed,String name,boolean remove)throws IOException{
            NodeTuple groupsField=field(root,"proxy-groups");if(groupsField==null)return;Node groups=groupsField.getValueNode();if(!(groups instanceof SequenceNode))throw unsupported();
            for(Node group:((SequenceNode)groups).getValue()){
                Node inherited=RuntimeYaml15.inherited(group,"use");if(inherited==null)continue;if(!(inherited instanceof SequenceNode))throw unsupported();
                SequenceNode use=(SequenceNode)inherited;boolean affected=false;for(Node item:use.getValue())if(managed.contains(scalar(item)))affected=true;if(!affected)continue;
                NodeTuple own=field(group,"use");if(own==null||own.getValueNode()!=use||references.getOrDefault(use,0)>1||references.getOrDefault(group,0)>1||index(use.getStartMark())<index(own.getKeyNode().getEndMark()))throw unsupported();
                local(group,groups);local(use,group);
                for(Node item:use.getValue())if(!(item instanceof ScalarNode)||item.getStartMark().getLine()!=item.getEndMark().getLine()||index(item.getStartMark())<index(use.getStartMark())||index(item.getEndMark())>index(use.getEndMark()))throw unsupported();
                if(remove)removeUse(use,name);else if(use.getValue().stream().noneMatch(item->name.equals(scalar(item))))appendUse(use,name);
            }
        }
        private void appendUse(SequenceNode use,String name)throws IOException{
            List<Node> items=use.getValue();if(items.isEmpty())throw unsupported();
            Node last=items.get(items.size()-1);int end=index(last.getEndMark());
            if(use.getFlowStyle()==DumperOptions.FlowStyle.FLOW)edits.add(new Edit(end,end,", "+quote(name)));
            else {int at=lineEnd(end),column=use.getStartMark().getColumn();if(!source.substring(lineStart(index(last.getStartMark())),index(last.getStartMark())).trim().equals("-"))throw unsupported();edits.add(new Edit(at,at,(at>0&&source.charAt(at-1)!='\n'&&source.charAt(at-1)!='\r'?newline:"")+spaces(column)+"- "+quote(name)+newline));}
        }
        private void removeUse(SequenceNode use,String name)throws IOException{
            List<Node> items=use.getValue();List<Integer> keep=new ArrayList<>();for(int i=0;i<items.size();i++)if(!name.equals(scalar(items.get(i))))keep.add(i);
            if(use.getFlowStyle()==DumperOptions.FlowStyle.FLOW){
                Set<Integer> commas=new HashSet<>();for(int i=0;i<keep.size()-1;i++)commas.add(keep.get(i));
                for(int i=0;i<items.size();i++){
                    Node item=items.get(i);if(name.equals(scalar(item)))edits.add(new Edit(index(item.getStartMark()),index(item.getEndMark()),""));
                    int from=index(item.getEndMark()),to=i+1<items.size()?index(items.get(i+1).getStartMark()):index(use.getEndMark())-1;
                    int comma=comma(from,to);if(i+1<items.size()&&comma<0)throw unsupported();
                    if(comma>=0&&(i+1<items.size()&&!commas.contains(i)||i+1==items.size()&&keep.isEmpty()))edits.add(new Edit(comma,comma+1,""));
                }
            }else {
                boolean empty=keep.isEmpty();for(Node item:items)if(name.equals(scalar(item))){int start=lineStart(index(item.getStartMark())),end=index(item.getEndMark());String prefix=source.substring(start,index(item.getStartMark()));int dash=prefix.indexOf('-');if(dash<0||!prefix.substring(0,dash).trim().isEmpty()||!prefix.substring(dash+1).trim().isEmpty())throw unsupported();edits.add(new Edit(start+dash,end,empty?"[]":""));empty=false;}
            }
        }
        private int comma(int from,int to)throws IOException{int comma=-1;boolean comment=false;for(int i=from;i<to;i++){char c=source.charAt(i);if(c=='\n'||c=='\r')comment=false;else if(!comment&&c=='#')comment=true;else if(!comment&&c==','){if(comma>=0)throw unsupported();comma=i;}else if(!comment&&!Character.isWhitespace(c))throw unsupported();}return comma;}
        String result()throws IOException{
            edits.sort(Comparator.comparingInt((Edit edit)->edit.start).thenComparingInt(edit->edit.end));int end=-1;for(Edit edit:edits){if(edit.start<end||edit.start<0||edit.end<edit.start||edit.end>source.length())throw unsupported();end=edit.end;}
            StringBuilder result=new StringBuilder(source);for(int i=edits.size()-1;i>=0;i--){Edit edit=edits.get(i);result.replace(edit.start,edit.end,edit.value);}return result.toString();
        }
    }

    static boolean coreAccepts(ProxyRuntimeProfile.Core core,String name){if(name==null)return false;int dot=name.lastIndexOf('.');return dot>0&&core.extensions.contains(name.substring(dot+1).toLowerCase(Locale.ROOT));}
    static String safeName(String name)throws IOException{String n=name==null?"":name.trim();if(n.length()<3||n.length()>120||n.contains("/")||n.contains("\\")||n.contains("\u0000")||n.equals(".")||n.equals(".."))throw new IOException("配置名称无效");return n;}
    private static String providerName(String v)throws IOException{String n=v==null?"":v.trim();if(n.isEmpty()||n.length()>40||n.chars().anyMatch(c->c<32||c==127))throw new IOException("订阅名称无效");return n;}
    private static String subscriptionUrl(String v)throws IOException{String u=v==null?"":v.trim();if(u.length()<10||u.length()>4096||!(u.startsWith("https://")||u.startsWith("http://"))||u.contains("\n")||u.contains("\r"))throw new IOException("请输入有效的 http/https 订阅链接");return u;}
    private static String fileToken(String s){return s.replaceAll("[^A-Za-z0-9\\p{L}._-]","_");}
    private static String yamlSingle(String s){return s.replace("'","''");}
}
