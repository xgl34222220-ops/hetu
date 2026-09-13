package main
import("testing";tun "github.com/metacubex/sing-tun")
func TestRequiredAndroidStack(t *testing.T){if !tun.WithGVisor{t.Fatal("Android VPN requires -tags with_gvisor; a compilable stub is not a working tunnel")}}
