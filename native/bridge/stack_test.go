package main
import("testing";tun "github.com/metacubex/sing-tun";"github.com/metacubex/mihomo/constant/features")
func TestRequiredAndroidStack(t *testing.T){
 if !tun.WithGVisor{t.Fatal("Android VPN requires -tags with_gvisor; a compilable stub is not a working tunnel")}
 if !features.CMFA{t.Fatal("Android application builds require cmfa: routing and package exclusion are owned by Android VpnService, not privileged /data/system/packages.xml access")}
}
