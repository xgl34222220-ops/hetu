package main

import (
	"net/netip"
	"os"
	"strings"
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
