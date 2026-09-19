//go:build hetu_root && (linux || android)

package keepalive

import (
	"context"
	"net"
	"testing"
	"time"

	"golang.org/x/sys/unix"
)

func TestHetuRootKeepAliveReachesOutboundSocket(t *testing.T) {
	oldDisabled, oldIdle, oldInterval := DisableKeepAlive(), KeepAliveIdle(), KeepAliveInterval()
	t.Cleanup(func() {
		SetDisableKeepAlive(oldDisabled)
		SetKeepAliveIdle(oldIdle)
		SetKeepAliveInterval(oldInterval)
	})
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	SetKeepAliveIdle(120 * time.Second)
	SetKeepAliveInterval(60 * time.Second)
	for _, disabled := range []bool{false, true} {
		SetDisableKeepAlive(disabled)
		dialer := &net.Dialer{Timeout: time.Second}
		SetNetDialer(dialer)
		connection, err := dialer.DialContext(context.Background(), "tcp4", listener.Addr().String())
		if err != nil {
			t.Fatal(err)
		}
		tcp := connection.(*net.TCPConn)
		raw, err := tcp.SyscallConn()
		if err != nil {
			tcp.Close()
			t.Fatal(err)
		}
		var enabled, idle, interval int
		var socketErr error
		err = raw.Control(func(fd uintptr) {
			enabled, socketErr = unix.GetsockoptInt(int(fd), unix.SOL_SOCKET, unix.SO_KEEPALIVE)
			if socketErr != nil || disabled {
				return
			}
			idle, socketErr = unix.GetsockoptInt(int(fd), unix.IPPROTO_TCP, unix.TCP_KEEPIDLE)
			if socketErr == nil {
				interval, socketErr = unix.GetsockoptInt(int(fd), unix.IPPROTO_TCP, unix.TCP_KEEPINTVL)
			}
		})
		tcp.Close()
		if err != nil || socketErr != nil {
			t.Fatalf("inspect socket: %v %v", err, socketErr)
		}
		if disabled {
			if enabled != 0 {
				t.Fatal("explicit disable was not applied to the outbound socket")
			}
		} else if enabled != 1 || idle != 120 || interval != 60 {
			t.Fatalf("outbound socket keepalive differs from config: enabled=%d idle=%d interval=%d", enabled, idle, interval)
		}
	}
}
