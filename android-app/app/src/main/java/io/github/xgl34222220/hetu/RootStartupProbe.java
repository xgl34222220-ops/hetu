package io.github.xgl34222220.hetu;

/** One read-only Root round trip for startup liveness and installed-core identity. */
final class RootStartupProbe {
    static final class Result {
        final ProxyContinuity.ProcessState process;
        final String installedCoreToken;
        Result(ProxyContinuity.ProcessState process,String token){
            this.process=process;installedCoreToken=token;
        }
    }

    static String command(String pidFile,String core,String tokenFile){
        return ProxyContinuity.coreProbeCommand(pidFile,core)
                +"; printf '\\n'; if [ -x "+quote(core)+" ] && [ -r "+quote(tokenFile)
                +" ]; then cat "+quote(tokenFile)+" 2>/dev/null; fi; printf '\\n'";
    }

    static Result parse(boolean success,String output){
        if(!success||output==null)return new Result(ProxyContinuity.ProcessState.UNKNOWN,"");
        int newline=output.indexOf('\n');
        if(newline<0)return new Result(ProxyContinuity.ProcessState.UNKNOWN,"");
        ProxyContinuity.ProcessState state=ProxyContinuity.processState(true,output.substring(0,newline));
        String token=output.substring(newline+1).trim();
        // Only tokens produced by RootProxyManager can suppress redeployment. No
        // partial, truncated or multi-line shell response becomes a cache hit.
        if(!token.matches("(?:asset|file):[A-Za-z0-9_.:-]{1,160}"))token="";
        return new Result(state,token);
    }

    private static String quote(String value){
        if(value==null||value.indexOf('\0')>=0)throw new IllegalArgumentException("Invalid startup probe path");
        return "'"+value.replace("'","'\\''")+"'";
    }
}
