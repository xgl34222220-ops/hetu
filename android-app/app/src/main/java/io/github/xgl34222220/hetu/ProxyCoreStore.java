package io.github.xgl34222220.hetu;

import android.content.Context;
import java.io.*;
import java.nio.file.Files;
import java.util.*;

/** Private core files. Download/update/import are separate from configuration files. */
final class ProxyCoreStore {
    private static final long MAX_CORE=128L*1024L*1024L;
    private final File root;
    ProxyCoreStore(Context c){root=new File(c.getFilesDir(),"proxy/cores");}

    File file(ProxyRuntimeProfile.Core core){return new File(root,core.id);}
    boolean installed(ProxyRuntimeProfile.Core core){File f=file(core);return f.isFile()&&f.length()>0&&f.canRead();}
    long size(ProxyRuntimeProfile.Core core){File f=file(core);return f.isFile()?f.length():0;}

    void importCore(ProxyRuntimeProfile.Core core, InputStream source)throws IOException{
        if(source==null)throw new IOException("无法读取核心文件");
        if(!root.isDirectory()&&!root.mkdirs())throw new IOException("无法创建核心目录");
        File target=file(core),tmp=new File(root,core.id+".new");
        long total=0;
        try(InputStream in=source;FileOutputStream out=new FileOutputStream(tmp,false)){
            byte[] b=new byte[64*1024];int n;
            while((n=in.read(b))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("核心导入已取消");total+=n;if(total>MAX_CORE)throw new IOException("核心文件超过 128 MiB");out.write(b,0,n);}out.getFD().sync();
        }catch(IOException e){tmp.delete();throw e;}
        if(total<1024){tmp.delete();throw new IOException("核心文件过小或无效");}
        if(!tmp.setReadable(true,true)||!tmp.setExecutable(true,true)){tmp.delete();throw new IOException("无法设置核心执行权限");}
        try{Files.move(tmp.toPath(),target.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);}catch(Exception atomic){if(!tmp.renameTo(target)){tmp.delete();throw new IOException("无法保存核心文件");}}
        target.setReadable(true,true);target.setExecutable(true,true);
    }

    void remove(ProxyRuntimeProfile.Core core)throws IOException{
        File f=file(core);if(f.exists()&&!f.delete())throw new IOException("无法删除核心文件");
    }

    List<ProxyRuntimeProfile.Core> installed(){
        ArrayList<ProxyRuntimeProfile.Core> out=new ArrayList<>();for(ProxyRuntimeProfile.Core c:ProxyRuntimeProfile.Core.values())if(installed(c))out.add(c);return out;
    }

    String summary(ProxyRuntimeProfile.Core core){return installed(core)?human(size(core))+" · 已安装":"未安装";}
    private static String human(long n){if(n<1024)return n+" B";if(n<1048576)return String.format(Locale.ROOT,"%.1f KiB",n/1024d);return String.format(Locale.ROOT,"%.1f MiB",n/1048576d);}
}
