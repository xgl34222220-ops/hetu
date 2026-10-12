// Minimal reader for the eBPF object clang emits for native/ebpf/hetu_match.c: the legacy
// (pre-BTF) layout of a "maps" section of struct bpf_map_def, one program per socket/* section
// and R_BPF_64_64 relocations that turn a map symbol into its file descriptor. Written from
// scratch; debug.elf from the standard library does the ELF parsing, nothing else is imported.
package main

import (
	"debug/elf"
	"encoding/binary"
	"fmt"
	"sort"
	"strings"
)

const mapDefSize = 20 // type, key_size, value_size, max_entries, map_flags

// R_BPF_64_64: a 64-bit immediate load of a symbol's address (here: a map).
const rBPF6464 = 1

type mapDef struct {
	Name       string
	Type       uint32
	KeySize    uint32
	ValueSize  uint32
	MaxEntries uint32
	Flags      uint32
	Offset     uint64
}

type progDef struct {
	Name  string // section name without the "socket/" prefix
	Insns []byte
	Relos []reloc
}

type reloc struct {
	Offset uint64 // byte offset of the instruction inside Insns
	Symbol string
}

type object struct {
	License string
	Maps    []mapDef
	Progs   []progDef
}

func parseObject(path string) (*object, error) {
	f, err := elf.Open(path)
	if err != nil {
		return nil, fmt.Errorf("open %s: %w", path, err)
	}
	defer f.Close()
	if f.Machine != elf.EM_BPF {
		return nil, fmt.Errorf("%s is not an eBPF object (machine %v)", path, f.Machine)
	}
	out := &object{}

	if sec := f.Section("license"); sec != nil {
		data, err := sec.Data()
		if err != nil {
			return nil, err
		}
		out.License = string(trimZero(data))
	}
	if out.License == "" {
		return nil, fmt.Errorf("%s has no license section", path)
	}

	symbols, err := f.Symbols()
	if err != nil {
		return nil, fmt.Errorf("symbols: %w", err)
	}

	mapsIndex := -1
	for i, sec := range f.Sections {
		if sec.Name == "maps" {
			mapsIndex = i
		}
	}
	if mapsIndex >= 0 {
		data, err := f.Sections[mapsIndex].Data()
		if err != nil {
			return nil, err
		}
		if len(data)%mapDefSize != 0 {
			return nil, fmt.Errorf("maps section is %d bytes, not a multiple of %d", len(data), mapDefSize)
		}
		byOffset := map[uint64]string{}
		for _, sym := range symbols {
			if int(sym.Section) == mapsIndex {
				byOffset[sym.Value] = sym.Name
			}
		}
		for off := 0; off < len(data); off += mapDefSize {
			name, ok := byOffset[uint64(off)]
			if !ok {
				return nil, fmt.Errorf("map definition at offset %d has no symbol", off)
			}
			out.Maps = append(out.Maps, mapDef{
				Name:       name,
				Type:       binary.LittleEndian.Uint32(data[off:]),
				KeySize:    binary.LittleEndian.Uint32(data[off+4:]),
				ValueSize:  binary.LittleEndian.Uint32(data[off+8:]),
				MaxEntries: binary.LittleEndian.Uint32(data[off+12:]),
				Flags:      binary.LittleEndian.Uint32(data[off+16:]),
				Offset:     uint64(off),
			})
		}
		sort.Slice(out.Maps, func(i, j int) bool { return out.Maps[i].Offset < out.Maps[j].Offset })
	}

	for i, sec := range f.Sections {
		if !strings.HasPrefix(sec.Name, "socket/") || sec.Type != elf.SHT_PROGBITS {
			continue
		}
		insns, err := sec.Data()
		if err != nil {
			return nil, err
		}
		if len(insns) == 0 || len(insns)%8 != 0 {
			return nil, fmt.Errorf("%s holds %d bytes of instructions", sec.Name, len(insns))
		}
		prog := progDef{Name: strings.TrimPrefix(sec.Name, "socket/"), Insns: insns}
		for _, rel := range f.Sections {
			if rel.Type != elf.SHT_REL || int(rel.Info) != i {
				continue
			}
			data, err := rel.Data()
			if err != nil {
				return nil, err
			}
			for off := 0; off+16 <= len(data); off += 16 {
				where := binary.LittleEndian.Uint64(data[off:])
				info := binary.LittleEndian.Uint64(data[off+8:])
				symIndex := int(info >> 32)
				if info&0xffffffff != rBPF6464 {
					return nil, fmt.Errorf("%s: unsupported relocation type %d", sec.Name, info&0xffffffff)
				}
				if symIndex == 0 || symIndex > len(symbols) {
					return nil, fmt.Errorf("%s: relocation symbol %d out of range", sec.Name, symIndex)
				}
				prog.Relos = append(prog.Relos, reloc{Offset: where, Symbol: symbols[symIndex-1].Name})
			}
		}
		out.Progs = append(out.Progs, prog)
	}
	if len(out.Progs) == 0 {
		return nil, fmt.Errorf("%s holds no socket/* program", path)
	}
	sort.Slice(out.Progs, func(i, j int) bool { return out.Progs[i].Name < out.Progs[j].Name })
	return out, nil
}

// relocate rewrites every "r1 = <map symbol>" load into the pseudo map-fd form the verifier
// expects: src_reg = BPF_PSEUDO_MAP_FD and imm = the map's file descriptor.
func relocate(prog *progDef, fds map[string]int) error {
	for _, rel := range prog.Relos {
		if rel.Offset+16 > uint64(len(prog.Insns)) {
			return fmt.Errorf("%s: relocation at %d past the program", prog.Name, rel.Offset)
		}
		fd, ok := fds[rel.Symbol]
		if !ok {
			return fmt.Errorf("%s: relocation references unknown map %s", prog.Name, rel.Symbol)
		}
		insn := prog.Insns[rel.Offset : rel.Offset+16]
		if insn[0] != 0x18 {
			return fmt.Errorf("%s: relocation at %d is not a 64-bit load", prog.Name, rel.Offset)
		}
		insn[1] = insn[1]&0x0f | byte(pseudoMapFD)<<4
		binary.LittleEndian.PutUint32(insn[4:], uint32(fd))
	}
	return nil
}
