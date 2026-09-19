//go:build !hetu_root

package keepalive

import "testing"

func TestHetuPreservesAndroidVpnKeepAlivePolicy(t *testing.T) {
	for _, requested := range []bool{false, true} {
		if !hetuDisableKeepAlive(requested, "android") {
			t.Fatal("JNI Android build must retain the upstream disabled policy")
		}
		if got := hetuDisableKeepAlive(requested, "linux"); got != requested {
			t.Fatalf("non-Android behavior changed: requested=%v got=%v", requested, got)
		}
	}
}
