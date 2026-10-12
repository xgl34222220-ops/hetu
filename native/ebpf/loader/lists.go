// Pure list handling (no syscalls), unit-tested on the host.
package main

import (
	"bufio"
	"encoding/binary"
	"fmt"
	"io"
	"net/netip"
	"sort"
	"strconv"
	"strings"
)

const (
	uidFlagDirect = 1
	uidFlagList   = 2
	maxUIDs       = 4096
	noOwnerUID    = 65534
	firstAppUID   = 10000
)

// parseUIDs reads "10123,10200-10210" exactly as hetu-root.sh's split_safe_uids accepts it:
// application UIDs only (>= 10000), ascending ranges.
func parseUIDs(list string) ([]uint32, error) {
	var out []uint32
	list = strings.TrimSpace(list)
	if list == "" {
		return nil, nil
	}
	for _, item := range strings.Split(list, ",") {
		lo, hi, ranged := strings.Cut(item, "-")
		if !ranged {
			hi = lo
		}
		a, err := strconv.ParseUint(lo, 10, 32)
		if err != nil {
			return nil, fmt.Errorf("bad uid %q", item)
		}
		b, err := strconv.ParseUint(hi, 10, 32)
		if err != nil {
			return nil, fmt.Errorf("bad uid %q", item)
		}
		if a < firstAppUID || b < a {
			return nil, fmt.Errorf("uid %q is not an application range", item)
		}
		if b-a >= maxUIDs {
			return nil, fmt.Errorf("uid range %q is too large", item)
		}
		for u := a; u <= b; u++ {
			if u == noOwnerUID {
				return nil, fmt.Errorf("uid %d is the kernel overflow uid", u)
			}
			out = append(out, uint32(u))
			if len(out) > maxUIDs {
				return nil, fmt.Errorf("more than %d uids", maxUIDs)
			}
		}
	}
	return out, nil
}

// uidFlags merges the two lists into the map contents (uid -> flag bits).
func uidFlags(direct, list string) (map[uint32]uint8, error) {
	d, err := parseUIDs(direct)
	if err != nil {
		return nil, err
	}
	l, err := parseUIDs(list)
	if err != nil {
		return nil, err
	}
	out := map[uint32]uint8{}
	for _, u := range d {
		out[u] |= uidFlagDirect
	}
	for _, u := range l {
		out[u] |= uidFlagList
	}
	if len(out) > maxUIDs {
		return nil, fmt.Errorf("more than %d uids", maxUIDs)
	}
	return out, nil
}

// lpmKey encodes a prefix as the LPM trie key: u32 prefix length (host order) + address bytes.
func lpmKey(p netip.Prefix) []byte {
	addr := p.Addr().AsSlice()
	key := make([]byte, 4+len(addr))
	binary.NativeEndian.PutUint32(key, uint32(p.Bits()))
	copy(key[4:], addr)
	return key
}

func parsePrefix(raw string) (netip.Prefix, bool) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return netip.Prefix{}, false
	}
	if !strings.Contains(raw, "/") {
		a, err := netip.ParseAddr(raw)
		if err != nil {
			return netip.Prefix{}, false
		}
		return netip.PrefixFrom(a, a.BitLen()), true
	}
	p, err := netip.ParsePrefix(raw)
	if err != nil {
		return netip.Prefix{}, false
	}
	if p.Addr().Is4In6() || p.Addr().Zone() != "" {
		return netip.Prefix{}, false
	}
	return p.Masked(), true
}

// splitCIDRs parses a comma list strictly: one bad entry rejects the whole list (the caller
// falls back to iptables instead of silently dropping a bypass network).
func splitCIDRs(list string) (v4, v6 []netip.Prefix, err error) {
	list = strings.TrimSpace(list)
	if list == "" {
		return nil, nil, nil
	}
	for _, item := range strings.Split(list, ",") {
		p, ok := parsePrefix(item)
		if !ok {
			return nil, nil, fmt.Errorf("bad network %q", item)
		}
		if p.Addr().Is4() {
			v4 = append(v4, p)
		} else {
			v6 = append(v6, p)
		}
	}
	return dedupe(v4), dedupe(v6), nil
}

// readCIDRFile parses a CN provider file (one network per line, "#" comments) the way
// hetu-root.sh's ipset import does: invalid lines are skipped, the count is bounded.
func readCIDRFile(r io.Reader, family int, minCount, maxCount int) ([]netip.Prefix, error) {
	var out []netip.Prefix
	scan := bufio.NewScanner(r)
	scan.Buffer(make([]byte, 64*1024), 1024*1024)
	for scan.Scan() {
		line := scan.Text()
		if i := strings.IndexByte(line, '#'); i >= 0 {
			line = line[:i]
		}
		p, ok := parsePrefix(line)
		if !ok {
			continue
		}
		if family == 4 && (!p.Addr().Is4() || p.Bits() < 8) {
			continue
		}
		if family == 6 && (p.Addr().Is4() || p.Bits() < 16) {
			continue
		}
		out = append(out, p)
	}
	if err := scan.Err(); err != nil {
		return nil, err
	}
	out = dedupe(out)
	if len(out) < minCount || len(out) > maxCount {
		return nil, fmt.Errorf("v%d list holds %d networks, expected %d..%d", family, len(out), minCount, maxCount)
	}
	return out, nil
}

func dedupe(in []netip.Prefix) []netip.Prefix {
	seen := map[netip.Prefix]bool{}
	var out []netip.Prefix
	for _, p := range in {
		if !seen[p] {
			seen[p] = true
			out = append(out, p)
		}
	}
	sort.Slice(out, func(i, j int) bool {
		if c := out[i].Addr().Compare(out[j].Addr()); c != 0 {
			return c < 0
		}
		return out[i].Bits() < out[j].Bits()
	})
	return out
}

func putNative32(b []byte, v uint32) { binary.NativeEndian.PutUint32(b, v) }
