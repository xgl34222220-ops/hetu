package io.github.xgl34222220.hetu;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

public final class RootStartupProbeTest {
    private static int checks;
    private static void check(boolean condition,String message){
        if(!condition)throw new AssertionError(message);
        checks++;
    }

    private static RootStartupProbe.Result probe(Path pid,Path core,Path token)throws Exception{
        Process shell=new ProcessBuilder("/bin/sh","-c",RootStartupProbe.command(pid.toString(),core.toString(),token.toString()))
                .redirectErrorStream(true).start();
        if(!shell.waitFor(5,TimeUnit.SECONDS)){shell.destroyForcibly();throw new AssertionError("probe blocked");}
        String output=new String(shell.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        check(shell.exitValue()==0,"read-only combined probe succeeds");
        return RootStartupProbe.parse(true,output);
    }

    public static void main(String[] args)throws Exception{
        Path dir=Files.createTempDirectory("hetu-startup-probe-");
        Path core=dir.resolve("core ' $(printf INJECTED)"),pid=dir.resolve("pid"),token=dir.resolve("token ' ; #");
        Process child=null;
        String expected="asset:mihomo:0123456789abcdef:fmt2-root-keepalive";
        try{
            Files.copy(Paths.get("/bin/sleep"),core);
            check(core.toFile().setExecutable(true,true),"test core executable");
            Files.writeString(token,expected);
            RootStartupProbe.Result stopped=probe(pid,core,token);
            check(stopped.process==ProxyContinuity.ProcessState.DEAD,"stopped core reported dead");
            check(expected.equals(stopped.installedCoreToken),"stopped core binary still reusable");

            child=new ProcessBuilder(core.toString(),"60").start();
            Files.writeString(pid,Long.toString(child.pid()));
            RootStartupProbe.Result running=probe(pid,core,token);
            check(running.process==ProxyContinuity.ProcessState.ALIVE,"running process recognized");
            check(expected.equals(running.installedCoreToken),"single probe also reads token using literal quoted paths");

            Files.delete(core);
            RootStartupProbe.Result removed=probe(pid,core,token);
            check(removed.process==ProxyContinuity.ProcessState.ALIVE,"deleted running core stays alive");
            check(removed.installedCoreToken.isEmpty(),"missing disk binary forces deploy despite retained token");
            check(RootStartupProbe.parse(false,"0\n"+expected).installedCoreToken.isEmpty(),"failed shell cannot skip deployment");
            check(RootStartupProbe.parse(true,"1").process==ProxyContinuity.ProcessState.UNKNOWN,"incomplete framing stays unknown");
            check(RootStartupProbe.parse(true,"0\n"+expected+"\nnoise").installedCoreToken.isEmpty(),"extra output cannot create cache hit");
            check(RootStartupProbe.parse(true,"?\nfile:mihomo:1234:5678\n").process==ProxyContinuity.ProcessState.UNKNOWN,"unknown liveness stays unknown");
        }finally{
            if(child!=null&&child.isAlive()){child.destroyForcibly();child.waitFor(5,TimeUnit.SECONDS);}
            try(var paths=Files.walk(dir)){
                for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);
            }
        }
        System.out.println("RootStartupProbeTest passed: "+checks);
    }
}
