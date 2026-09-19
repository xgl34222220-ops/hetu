//go:build hetu_root

package keepalive

import (
	"net"
	"testing"
	"time"
)

func TestHetuRootHonorsKeepAliveConfiguration(t *testing.T) {
	for _, platform := range []string{"android", "linux"} {
		for _, requested := range []bool{false, true} {
			if got := hetuDisableKeepAlive(requested, platform); got != requested {
				t.Fatalf("%s requested=%v got=%v", platform, requested, got)
			}
		}
	}
}

func TestHetuRootDialerRetainsUserTimingAndExplicitDisable(t *testing.T) {
	oldDisabled, oldIdle, oldInterval := DisableKeepAlive(), KeepAliveIdle(), KeepAliveInterval()
	t.Cleanup(func() {
		SetDisableKeepAlive(oldDisabled)
		SetKeepAliveIdle(oldIdle)
		SetKeepAliveInterval(oldInterval)
	})
	SetKeepAliveIdle(120 * time.Second)
	SetKeepAliveInterval(60 * time.Second)
	SetDisableKeepAlive(false)
	dialer := &net.Dialer{}
	SetNetDialer(dialer)
	if !dialer.KeepAliveConfig.Enable || dialer.KeepAliveConfig.Idle != 120*time.Second || dialer.KeepAliveConfig.Interval != 60*time.Second {
		t.Fatalf("Root dialer lost configured keepalive: %+v", dialer.KeepAliveConfig)
	}
	SetDisableKeepAlive(true)
	dialer = &net.Dialer{}
	SetNetDialer(dialer)
	if dialer.KeepAlive >= 0 || dialer.KeepAliveConfig.Enable {
		t.Fatal("explicit disable-keep-alive:true must still disable probes")
	}
}
