package main

import (
	"encoding/binary"
	"net/netip"
	"os"
	"strings"
	"syscall"
	"testing"
)

func TestParseUIDsMatchesShellRules(t *testing.T) {
	got, err := parseUIDs("10123,10200-10202")
	if err != nil || len(got) != 4 || got[0] != 10123 || got[3] != 10202 {
		t.Fatalf("got %v %v", got, err)
	}
	for _, bad := range []string{"9999", "10002-10001", "abc", "10000-", "65534", "10000-20000"} {
		if _, err := parseUIDs(bad); err == nil {
			t.Fatalf("%q accepted", bad)
		}
	}
	if got, err := parseUIDs(""); err != nil || got != nil {
		t.Fatalf("empty list: %v %v", got, err)
	}
}

func TestUIDFlagsKeepDirectAndListApart(t *testing.T) {
	flags, err := uidFlags("10001,10002", "10002,10003")
	if err != nil {
		t.Fatal(err)
	}
	if flags[10001] != uidFlagDirect || flags[10002] != uidFlagDirect|uidFlagList || flags[10003] != uidFlagList {
		t.Fatalf("flags %v", flags)
	}
}

func TestSplitCIDRsIsStrictAndMasks(t *testing.T) {
	v4, v6, err := splitCIDRs("10.1.2.3/8,2001:db8::1/32,192.0.2.0/24,10.0.0.0/8")
	if err != nil {
		t.Fatal(err)
	}
	if len(v4) != 2 || v4[0] != netip.MustParsePrefix("10.0.0.0/8") || len(v6) != 1 || v6[0] != netip.MustParsePrefix("2001:db8::/32") {
		t.Fatalf("v4 %v v6 %v", v4, v6)
	}
	if _, _, err := splitCIDRs("10.0.0.0/8,not-a-network"); err == nil {
		t.Fatal("bad entry accepted")
	}
}

func TestLPMKeyLayout(t *testing.T) {
	k := lpmKey(netip.MustParsePrefix("192.168.0.0/16"))
	if len(k) != 8 || k[4] != 192 || k[5] != 168 || k[6] != 0 {
		t.Fatalf("key %v", k)
	}
	if len(lpmKey(netip.MustParsePrefix("2001:db8::/32"))) != 20 {
		t.Fatal("v6 key size")
	}
}

func TestReadCIDRFileBoundsAndComments(t *testing.T) {
	text := "# header\n1.0.1.0/24\n1.0.2.0/23 # note\nbad\n::/0\n1.0.1.0/24\n"
	got, err := readCIDRFile(strings.NewReader(text), 4, 1, 10)
	if err != nil || len(got) != 2 {
		t.Fatalf("got %v %v", got, err)
	}
	if _, err := readCIDRFile(strings.NewReader(text), 4, 3, 10); err == nil {
		t.Fatal("count lower bound ignored")
	}
}

func TestShippedCNSnapshotsFitTheMaps(t *testing.T) {
	for _, c := range []struct {
		file          string
		fam, min, max int
	}{{"../../../android-app/app/src/main/assets/cnip/hetu-cn-v4.txt", 4, 1000, 20000}, {"../../../android-app/app/src/main/assets/cnip/hetu-cn-v6.txt", 6, 500, 30000}} {
		f, err := os.Open(c.file)
		if err != nil {
			t.Skip("snapshot not present: ", err)
		}
		got, err := readCIDRFile(f, c.fam, c.min, c.max)
		f.Close()
		if err != nil || len(got) > 65536 {
			t.Fatalf("%s: %d %v", c.file, len(got), err)
		}
	}
}

func TestVendorMapLabelNamesTheNetworkType(t *testing.T) {
	for in, want := range map[string]string{
		"/sys/fs/bpf/map_oplus-netd_app_wlan_socket_uid_limit_map": "wlan",
		"/sys/fs/bpf/map_oplus-netd_app_qcom_socket_uid_limit_map": "qcom",
		"/sys/fs/bpf/map_oplus-netd_app__socket_uid_limit_map":     "map",
	} {
		if got := vendorMapLabel(in); got != want {
			t.Fatalf("%s: %q", in, got)
		}
	}
	if got := vendorUID(nil, []uint32{10100}, true); got != "maps=0 present=0 removed=0 failed=0 names=-" {
		t.Fatalf("no maps: %s", got)
	}
}

// A real pinned map, where this host lets the test create one (root, bpffs): only the given
// UIDs leave it, every other entry (the user's own per-app blocks) stays.
func TestVendorUIDDeletesOnlyTheGivenUIDs(t *testing.T) {
	if os.Geteuid() != 0 || !onBPFFS("/sys/fs/bpf") {
		t.Skip("needs root and /sys/fs/bpf")
	}
	dir, err := os.MkdirTemp("/sys/fs/bpf", "hetu-vendor-test-")
	if err != nil {
		t.Skip("cannot pin: ", err)
	}
	defer os.RemoveAll(dir)
	pin := func(name string, keySize uint32, keys ...uint32) string {
		fd, err := mapCreate(1, keySize, 4, 16, 0, "hetu_vtest")
		if err != nil {
			t.Skip("bpf unavailable: ", err)
		}
		defer syscall.Close(fd)
		for _, k := range keys {
			key := make([]byte, keySize)
			putNative32(key, k)
			if err := mapUpdate(fd, key, []byte{1, 0, 0, 0}); err != nil {
				t.Fatal(err)
			}
		}
		path := dir + "/" + name
		if err := objPin(fd, path); err != nil {
			t.Fatal(err)
		}
		return path
	}
	wlan := pin("map_oplus-netd_app_wlan_socket_uid_limit_map", 4, 10100, 10200, 1010300)
	odd := pin("map_oplus-netd_app_odd_socket_uid_limit_map", 8, 10100)
	paths := []string{wlan, odd}
	if got := vendorUID(paths, []uint32{10100, 1010300, 10999}, false); got != "maps=1 present=2 removed=0 failed=1 names=wlan" {
		t.Fatalf("check: %s", got)
	}
	if got := vendorUID(paths, []uint32{10100, 1010300, 10999}, true); got != "maps=1 present=2 removed=2 failed=1 names=wlan" {
		t.Fatalf("unblock: %s", got)
	}
	fd, err := objGet(wlan)
	if err != nil {
		t.Fatal(err)
	}
	defer syscall.Close(fd)
	keys, err := mapKeys(fd, 4)
	if err != nil || len(keys) != 1 || binary.NativeEndian.Uint32(keys[0]) != 10200 {
		t.Fatalf("left %v %v", keys, err)
	}
	ofd, err := objGet(odd)
	if err != nil {
		t.Fatal(err)
	}
	defer syscall.Close(ofd)
	if left, err := mapKeys(ofd, 8); err != nil || len(left) != 1 {
		t.Fatalf("a map with another key layout was touched: %v %v", left, err)
	}
}
