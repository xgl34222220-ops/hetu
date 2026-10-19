// hetu-bpf: loads native/ebpf/hetu_match.o, pins its programs and maps under one directory
// on bpffs, and rewrites the maps when a list changes. hetu-root.sh is the only caller; every
// failure prints one short reason token on stdout (the script records it as the fallback
// reason) and exits non-zero, so the iptables owner/CIDR path takes over.
//
// Usage:
//
//	hetu-bpf probe     <pindir>
//	hetu-bpf load      <object> <pindir>
//	hetu-bpf sync-uid  <pindir> <direct-uids> <list-uids>
//	hetu-bpf sync-cidr <pindir> <networks>
//	hetu-bpf sync-cn   <pindir> 4|6 <file>|-
//	hetu-bpf count     <pindir>
//	hetu-bpf unload    <pindir>
//	hetu-bpf vendor-uid check|unblock <uids>
package main

import (
	"encoding/binary"
	"errors"
	"fmt"
	"net/netip"
	"os"
	"path/filepath"
	"strings"
	"syscall"
)

const bpfFSMagic = 0xcafe4a11

const (
	mapUID   = "hetu_uid"
	mapCIDR4 = "hetu_cidr4"
	mapCIDR6 = "hetu_cidr6"
	mapCN4   = "hetu_cn4"
	mapCN6   = "hetu_cn6"
)

type failure struct {
	reason string
	err    error
}

func (f failure) Error() string { return f.reason + ": " + f.err.Error() }

func fail(reason string, err error) error { return failure{reason, err} }

func main() {
	if err := run(os.Args[1:]); err != nil {
		var f failure
		reason := "error"
		if errors.As(err, &f) {
			reason = f.reason
		}
		fmt.Fprintln(os.Stderr, "hetu-bpf:", err)
		fmt.Println(reason)
		os.Exit(1)
	}
}

func run(args []string) error {
	if len(args) < 2 {
		return fail("usage", errors.New("missing command"))
	}
	switch args[0] {
	case "probe":
		return probe(args[1])
	case "load":
		if len(args) != 3 {
			return fail("usage", errors.New("load <object> <pindir>"))
		}
		return load(args[1], args[2])
	case "sync-uid":
		if len(args) != 4 {
			return fail("usage", errors.New("sync-uid <pindir> <direct> <list>"))
		}
		return syncUID(args[1], args[2], args[3])
	case "sync-cidr":
		if len(args) != 3 {
			return fail("usage", errors.New("sync-cidr <pindir> <networks>"))
		}
		return syncCIDR(args[1], args[2])
	case "sync-cn":
		if len(args) != 4 {
			return fail("usage", errors.New("sync-cn <pindir> 4|6 <file>"))
		}
		return syncCN(args[1], args[2], args[3])
	case "count":
		return count(args[1])
	case "unload":
		return unload(args[1])
	case "vendor-uid":
		if len(args) != 3 || (args[1] != "check" && args[1] != "unblock") {
			return fail("usage", errors.New("vendor-uid check|unblock <uids>"))
		}
		uids, err := parseUIDs(args[2])
		if err != nil {
			return fail("uid-list-invalid", err)
		}
		paths, _ := filepath.Glob(vendorUIDMaps)
		fmt.Println(vendorUID(paths, uids, args[1] == "unblock"))
		return nil
	}
	return fail("usage", fmt.Errorf("unknown command %q", args[0]))
}

func selinuxEnforcing() bool {
	b, err := os.ReadFile("/sys/fs/selinux/enforce")
	return err == nil && strings.TrimSpace(string(b)) == "1"
}

// classify turns a syscall error into the fallback reason the App shows.
func classify(prefix string, err error) error {
	switch {
	case errors.Is(err, syscall.ENOSYS):
		return fail("bpf-syscall-unavailable", err)
	case errors.Is(err, syscall.EACCES) && selinuxEnforcing():
		return fail("selinux-denied", err)
	case errors.Is(err, syscall.EPERM), errors.Is(err, syscall.EACCES):
		return fail(prefix+"-permission", err)
	}
	return fail(prefix+"-failed", err)
}

func onBPFFS(path string) bool {
	var st syscall.Statfs_t
	if syscall.Statfs(path, &st) != nil {
		return false
	}
	return uint32(st.Type) == bpfFSMagic
}

// Probe instructions: r0 = bpf_get_socket_uid(ctx); r0 = 0; exit. Loading it proves socket
// filters may be loaded and that this kernel offers the uid helper the matcher needs.
var probeInsns = []byte{
	0x85, 0x00, 0x00, 0x00, 0x2f, 0x00, 0x00, 0x00,
	0xb7, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
	0x95, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
}

func probe(pindir string) error {
	parent := filepath.Dir(pindir)
	if !onBPFFS(parent) {
		return fail("bpffs-missing", fmt.Errorf("%s is not a mounted bpf filesystem", parent))
	}
	fd, err := mapCreate(1, 4, 1, 1, 0, "hetu_probe")
	if err != nil {
		return classify("bpf-syscall", err)
	}
	defer syscall.Close(fd)
	lpm, err := mapCreate(11, 8, 1, 1, 1, "hetu_probe_lpm")
	if err != nil {
		return fail("lpm-unsupported", err)
	}
	syscall.Close(lpm)
	prog, _, err := progLoad(probeInsns, "GPL", "hetu_probe")
	if err != nil {
		if errors.Is(err, syscall.EINVAL) {
			return fail("socket-uid-helper-unavailable", err)
		}
		return classify("prog-load", err)
	}
	syscall.Close(prog)
	if err := os.MkdirAll(pindir, 0o700); err != nil {
		return classify("pin", err)
	}
	pin := filepath.Join(pindir, "probe_map")
	_ = os.Remove(pin)
	if err := objPin(fd, pin); err != nil {
		return classify("pin", err)
	}
	got, err := objGet(pin)
	_ = os.Remove(pin)
	if err != nil {
		return classify("pin", err)
	}
	syscall.Close(got)
	fmt.Println("ok")
	return nil
}

func load(objectPath, pindir string) error {
	obj, err := parseObject(objectPath)
	if err != nil {
		return fail("object-invalid", err)
	}
	if !onBPFFS(filepath.Dir(pindir)) {
		return fail("bpffs-missing", fmt.Errorf("%s is not on bpffs", pindir))
	}
	if err := unload(pindir); err != nil {
		return err
	}
	if err := os.MkdirAll(pindir, 0o700); err != nil {
		return classify("pin", err)
	}
	ok := false
	defer func() {
		if !ok {
			_ = unload(pindir)
		}
	}()
	fds := map[string]int{}
	defer func() {
		for _, fd := range fds {
			syscall.Close(fd)
		}
	}()
	for _, m := range obj.Maps {
		fd, err := mapCreate(m.Type, m.KeySize, m.ValueSize, m.MaxEntries, m.Flags, m.Name)
		if err != nil {
			return classify("map-create", err)
		}
		fds[m.Name] = fd
		if err := objPin(fd, filepath.Join(pindir, "map_"+m.Name)); err != nil {
			return classify("pin", err)
		}
	}
	for i := range obj.Progs {
		prog := &obj.Progs[i]
		if err := relocate(prog, fds); err != nil {
			return fail("object-invalid", err)
		}
		fd, verifier, err := progLoad(prog.Insns, obj.License, prog.Name)
		if err != nil {
			if verifier != "" {
				fmt.Fprintln(os.Stderr, lastLines(verifier, 12))
			}
			return classify("prog-load", err)
		}
		err = objPin(fd, filepath.Join(pindir, "prog_"+prog.Name))
		syscall.Close(fd)
		if err != nil {
			return classify("pin", err)
		}
	}
	ok = true
	fmt.Printf("loaded maps=%d progs=%d\n", len(obj.Maps), len(obj.Progs))
	return nil
}

func lastLines(s string, n int) string {
	lines := strings.Split(strings.TrimSpace(s), "\n")
	if len(lines) > n {
		lines = lines[len(lines)-n:]
	}
	return strings.Join(lines, "\n")
}

func openMap(pindir, name string) (int, error) {
	fd, err := objGet(filepath.Join(pindir, "map_"+name))
	if err != nil {
		return -1, fail("pins-missing", err)
	}
	return fd, nil
}

// syncMap makes the map hold exactly want: new and changed entries are written first, then
// stale keys go, so a key present before and after never disappears in between.
func syncMap(fd int, keySize int, want map[string][]byte) (int, error) {
	for k, v := range want {
		if err := mapUpdate(fd, []byte(k), v); err != nil {
			return 0, classify("map-update", err)
		}
	}
	keys, err := mapKeys(fd, keySize)
	if err != nil {
		return 0, classify("map-update", err)
	}
	for _, k := range keys {
		if _, keep := want[string(k)]; keep {
			continue
		}
		if err := mapDelete(fd, k); err != nil && !errors.Is(err, syscall.ENOENT) {
			return 0, classify("map-update", err)
		}
	}
	return len(want), nil
}

func syncUID(pindir, direct, list string) error {
	flags, err := uidFlags(direct, list)
	if err != nil {
		return fail("uid-list-invalid", err)
	}
	fd, err := openMap(pindir, mapUID)
	if err != nil {
		return err
	}
	defer syscall.Close(fd)
	want := map[string][]byte{}
	for uid, f := range flags {
		key := make([]byte, 4)
		putNative32(key, uid)
		want[string(key)] = []byte{f}
	}
	n, err := syncMap(fd, 4, want)
	if err != nil {
		return err
	}
	fmt.Printf("uid=%d\n", n)
	return nil
}

func prefixSet(prefixes []netip.Prefix) map[string][]byte {
	want := map[string][]byte{}
	for _, p := range prefixes {
		want[string(lpmKey(p))] = []byte{1}
	}
	return want
}

func syncCIDR(pindir, list string) error {
	v4, v6, err := splitCIDRs(list)
	if err != nil {
		return fail("cidr-list-invalid", err)
	}
	fd4, err := openMap(pindir, mapCIDR4)
	if err != nil {
		return err
	}
	defer syscall.Close(fd4)
	fd6, err := openMap(pindir, mapCIDR6)
	if err != nil {
		return err
	}
	defer syscall.Close(fd6)
	n4, err := syncMap(fd4, 8, prefixSet(v4))
	if err != nil {
		return err
	}
	n6, err := syncMap(fd6, 20, prefixSet(v6))
	if err != nil {
		return err
	}
	fmt.Printf("cidr4=%d cidr6=%d\n", n4, n6)
	return nil
}

func syncCN(pindir, family, file string) error {
	var prefixes []netip.Prefix
	name, keySize := mapCN4, 8
	if family == "6" {
		name, keySize = mapCN6, 20
	} else if family != "4" {
		return fail("usage", fmt.Errorf("family %q", family))
	}
	if file != "-" {
		f, err := os.Open(file)
		if err != nil {
			return fail("cn-data-missing", err)
		}
		defer f.Close()
		minCount, maxCount := 1000, 20000
		if family == "6" {
			minCount, maxCount = 500, 30000
		}
		prefixes, err = readCIDRFile(f, map[string]int{"4": 4, "6": 6}[family], minCount, maxCount)
		if err != nil {
			return fail("cn-data-invalid", err)
		}
	}
	fd, err := openMap(pindir, name)
	if err != nil {
		return err
	}
	defer syscall.Close(fd)
	n, err := syncMap(fd, keySize, prefixSet(prefixes))
	if err != nil {
		return err
	}
	fmt.Printf("cn%s=%d\n", family, n)
	return nil
}

func count(pindir string) error {
	var parts []string
	for _, m := range []struct {
		name string
		key  int
	}{{mapUID, 4}, {mapCIDR4, 8}, {mapCIDR6, 20}, {mapCN4, 8}, {mapCN6, 20}} {
		fd, err := openMap(pindir, m.name)
		if err != nil {
			return err
		}
		keys, err := mapKeys(fd, m.key)
		syscall.Close(fd)
		if err != nil {
			return classify("map-read", err)
		}
		parts = append(parts, fmt.Sprintf("%s=%d", strings.TrimPrefix(m.name, "hetu_"), len(keys)))
	}
	fmt.Println(strings.Join(parts, " "))
	return nil
}

// unload removes this matcher's pins and its directory. Only names this tool creates are
// touched, and only on bpffs, so a wrong argument can never delete ordinary files.
func unload(pindir string) error {
	entries, err := os.ReadDir(pindir)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return fail("unpin-failed", err)
	}
	if !onBPFFS(pindir) {
		return fail("unpin-failed", fmt.Errorf("%s is not on bpffs", pindir))
	}
	for _, e := range entries {
		name := e.Name()
		if !strings.HasPrefix(name, "prog_") && !strings.HasPrefix(name, "map_") && !strings.HasPrefix(name, "probe_") {
			return fail("unpin-failed", fmt.Errorf("unexpected entry %s", name))
		}
		if err := os.Remove(filepath.Join(pindir, name)); err != nil && !errors.Is(err, os.ErrNotExist) {
			return fail("unpin-failed", err)
		}
	}
	if err := os.Remove(pindir); err != nil && !errors.Is(err, os.ErrNotExist) {
		return fail("unpin-failed", err)
	}
	return nil
}

// ColorOS / Oplus keeps a per-UID network block list in netd's cgroup eBPF maps (one per
// network type: wlan, qcom cellular, ...). While a transparent proxy runs instead of a
// VpnService it lists Google Play services, the Play Store, GSF and ConfigUpdater there, which
// iptables cannot see, and it writes them again at boot and on every VPN interface change.
// The same maps hold the user's own per-app "no WLAN / no mobile data" switches, so only the
// given UIDs are ever touched, never the whole map. Keys are the 4-byte UID.
const vendorUIDMaps = "/sys/fs/bpf/map_oplus-netd_app_*_socket_uid_limit_map"

// vendorUID reports how many of uids the maps at paths list, deleting them when unblock is set.
// A map that cannot be opened or has another key layout counts as failed and is left alone.
func vendorUID(paths []string, uids []uint32, unblock bool) string {
	want := map[uint32]bool{}
	for _, u := range uids {
		want[u] = true
	}
	maps, present, removed, failed := 0, 0, 0, 0
	var names []string
	for _, path := range paths {
		fd, err := objGet(path)
		if err != nil {
			failed++
			continue
		}
		keys, err := vendorKeys(fd)
		if err != nil {
			syscall.Close(fd)
			failed++
			continue
		}
		maps++
		names = append(names, vendorMapLabel(path))
		for _, k := range keys {
			if !want[binary.NativeEndian.Uint32(k)] {
				continue
			}
			present++
			if !unblock {
				continue
			}
			if err := mapDelete(fd, k); err == nil || errors.Is(err, syscall.ENOENT) {
				removed++
			} else {
				failed++
			}
		}
		syscall.Close(fd)
	}
	if len(names) == 0 {
		names = []string{"-"}
	}
	return fmt.Sprintf("maps=%d present=%d removed=%d failed=%d names=%s", maps, present, removed, failed, strings.Join(names, ","))
}

func vendorKeys(fd int) ([][]byte, error) {
	size, err := mapKeySize(fd)
	if err != nil {
		return nil, err
	}
	if size != 4 {
		return nil, fmt.Errorf("key size %d", size)
	}
	return mapKeys(fd, 4)
}

// vendorMapLabel: map_oplus-netd_app_wlan_socket_uid_limit_map -> wlan.
func vendorMapLabel(path string) string {
	name := filepath.Base(path)
	name = strings.TrimPrefix(name, "map_oplus-netd_app_")
	name = strings.TrimSuffix(name, "_socket_uid_limit_map")
	if name == "" || strings.ContainsAny(name, " ,=") {
		return "map"
	}
	return name
}
