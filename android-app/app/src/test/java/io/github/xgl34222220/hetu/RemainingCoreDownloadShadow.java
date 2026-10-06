package io.github.xgl34222220.hetu;
import java.util.Arrays;
import java.util.List;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.jvm.functions.Function1;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Resetter;
/** In-memory suspend boundary only: cannot resolve a URL, write a core, or reach a backend. */
@Implements(value = ProxyCoreDownloadManager.class, isInAndroidSdk = false, callThroughByDefault = false)
public class RemainingCoreDownloadShadow {
    private static Continuation<? super ProxyCoreRemoteStatus> pending;
    private static Function1<? super String, Unit> progressCallback;
    public static int calls;
    @Resetter public static void reset() { pending=null; progressCallback=null; calls=0; }
    @Implementation public Object statuses(boolean network, Continuation<? super List<ProxyCoreRemoteStatus>> done) {
        if (network) throw new SecurityException("Network core status is forbidden in this fixture");
        return Arrays.asList(
            new ProxyCoreRemoteStatus("mihomo","Mihomo",true,false,"1.19.10","1.19.12",true,true,true,"fixture",""),
            new ProxyCoreRemoteStatus("xray","Xray",false,false,"","25.9.11",false,true,false,"fixture",""),
            new ProxyCoreRemoteStatus("sing-box","sing-box",false,false,"","1.12.4",false,true,false,"fixture", ""));
    }
    @Implementation public Object downloadOrUpdate(ProxyRuntimeProfile.Core core, Function1<? super String, Unit> progress, Continuation<? super ProxyCoreRemoteStatus> done) {
        if (pending != null) throw new SecurityException("Duplicate fixture download");
        calls++; pending=done; progressCallback=progress;
        emitProgress(6_400_000L, 12_800_000L);
        return kotlin.coroutines.intrinsics.IntrinsicsKt.getCOROUTINE_SUSPENDED();
    }
    public static void emitProgress(long downloadedBytes, long totalBytes) {
        if (pending == null || progressCallback == null) throw new IllegalStateException("No controlled download is pending");
        progressCallback.invoke(ProxyCoreDownloadManagerKt.coreDownloadProgressText(downloadedBytes, totalBytes));
    }
    public static void cancelFixture() {
        Continuation<? super ProxyCoreRemoteStatus> done=pending; pending=null; progressCallback=null;
        if (done!=null) done.resumeWith(kotlin.ResultKt.createFailure(new java.util.concurrent.CancellationException("Controlled fixture ended")));
    }
}
