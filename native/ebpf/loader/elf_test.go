package main

import (
	"encoding/binary"
	"os"
	"testing"
)

// HETU_BPF_OBJECT points at the object built from ../hetu_match.c (CI builds it first).
func TestShippedObjectLayout(t *testing.T) {
	path := os.Getenv("HETU_BPF_OBJECT")
	if path == "" {
		t.Skip("HETU_BPF_OBJECT not set")
	}
	obj, err := parseObject(path)
	if err != nil {
		t.Fatal(err)
	}
	if obj.License != "GPL" {
		t.Fatalf("license %q", obj.License)
	}
	wantMaps := map[string][3]uint32{
		"hetu_uid": {1, 4, 1}, "hetu_cidr4": {11, 8, 1}, "hetu_cidr6": {11, 20, 1}, "hetu_cn4": {11, 8, 1}, "hetu_cn6": {11, 20, 1},
	}
	if len(obj.Maps) != len(wantMaps) {
		t.Fatalf("maps %+v", obj.Maps)
	}
	for _, m := range obj.Maps {
		w, ok := wantMaps[m.Name]
		if !ok || m.Type != w[0] || m.KeySize != w[1] || m.ValueSize != w[2] {
			t.Fatalf("map %+v", m)
		}
		if m.Type == 11 && m.Flags != 1 {
			t.Fatalf("LPM map %s without BPF_F_NO_PREALLOC", m.Name)
		}
	}
	wantProgs := map[string]string{
		"hetu_uid_direct": "hetu_uid", "hetu_uid_list": "hetu_uid", "hetu_bypass4": "hetu_cidr4",
		"hetu_bypass6": "hetu_cidr6", "hetu_cn4": "hetu_cn4", "hetu_cn6": "hetu_cn6",
	}
	if len(obj.Progs) != len(wantProgs) {
		t.Fatalf("progs %d", len(obj.Progs))
	}
	fds := map[string]int{}
	for i, m := range obj.Maps {
		fds[m.Name] = 100 + i
	}
	for i := range obj.Progs {
		p := &obj.Progs[i]
		if len(p.Relos) != 1 || p.Relos[0].Symbol != wantProgs[p.Name] {
			t.Fatalf("%s relocations %+v", p.Name, p.Relos)
		}
		if err := relocate(p, fds); err != nil {
			t.Fatal(err)
		}
		insn := p.Insns[p.Relos[0].Offset:]
		if insn[1]>>4 != pseudoMapFD || int(binary.LittleEndian.Uint32(insn[4:])) != fds[wantProgs[p.Name]] {
			t.Fatalf("%s relocation not applied: % x", p.Name, insn[:8])
		}
	}
}
