package io.github.xgl34222220.hetu;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Implementation;
/** Controlled test-only boundary for init/validation, no real Root or core executable. */
@Implements(value = RootProxyManager.class, isInAndroidSdk = false)
public class RemainingRootManagerShadow {
    @Implementation public String diagnostics() { return "受控诊断快照：无实际 Root 或网络操作"; }
    @Implementation public String ensureRuntimeBase(ProxyRuntimeProfile.Core core) { return "/data/adb/hetu"; }
    @Implementation public void validateConfigText(String text) throws java.io.IOException {
        throw new java.io.IOException("测试夹具：第 1 行 YAML 语法错误");
    }
}
