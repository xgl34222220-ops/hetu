// Raw bpf(2) access. Written from scratch for 河图: no libbpf, no cgo, no third-party module,
// so the binary is a static pure-Go executable that runs from /data/adb/hetu on any Android.
package main

import (
	"encoding/binary"
	"errors"
	"fmt"
	"runtime"
	"syscall"
	"unsafe"
)

const (
	cmdMapCreate     = 0
	cmdMapLookupElem = 1
	cmdMapUpdateElem = 2
	cmdMapDeleteElem = 3
	cmdMapGetNextKey = 4
	cmdProgLoad      = 5
	cmdObjPin        = 6
	cmdObjGet        = 7
	cmdObjGetInfo    = 15
)

const (
	progTypeSocketFilter = 1
	pseudoMapFD          = 1
)

func bpf(cmd int, attr []byte) (int, error) {
	r, _, errno := syscall.Syscall(sysBPF, uintptr(cmd), uintptr(unsafe.Pointer(&attr[0])), uintptr(len(attr)))
	runtime.KeepAlive(attr)
	if errno != 0 {
		return int(r), errno
	}
	return int(r), nil
}

// Kernels before 4.15 know no object names and reject a non-zero byte past their own attr
// layout. Retry once with the name cleared and the attr cut to the old size.
func oldAttr(err error) bool {
	return errors.Is(err, syscall.EINVAL) || errors.Is(err, syscall.E2BIG)
}

func attrBuf(n int) []byte { return make([]byte, n) }

// mapCreate returns the new map's file descriptor.
func mapCreate(mapType, keySize, valueSize, maxEntries, flags uint32, name string) (int, error) {
	attr := attrBuf(72)
	binary.NativeEndian.PutUint32(attr[0:], mapType)
	binary.NativeEndian.PutUint32(attr[4:], keySize)
	binary.NativeEndian.PutUint32(attr[8:], valueSize)
	binary.NativeEndian.PutUint32(attr[12:], maxEntries)
	binary.NativeEndian.PutUint32(attr[16:], flags)
	if len(name) > 15 {
		name = name[:15]
	}
	copy(attr[28:44], name)
	fd, err := bpf(cmdMapCreate, attr)
	if err != nil && oldAttr(err) {
		short := attrBuf(20)
		copy(short, attr[:20])
		fd, err = bpf(cmdMapCreate, short)
	}
	if err != nil {
		return -1, fmt.Errorf("map create %s: %w", name, err)
	}
	return fd, nil
}

func elemAttr(fd int, key, value []byte, flags uint64) []byte {
	attr := attrBuf(32)
	binary.NativeEndian.PutUint32(attr[0:], uint32(fd))
	if len(key) > 0 {
		binary.NativeEndian.PutUint64(attr[8:], uint64(uintptr(unsafe.Pointer(&key[0]))))
	}
	if len(value) > 0 {
		binary.NativeEndian.PutUint64(attr[16:], uint64(uintptr(unsafe.Pointer(&value[0]))))
	}
	binary.NativeEndian.PutUint64(attr[24:], flags)
	return attr
}

func mapUpdate(fd int, key, value []byte) error {
	attr := elemAttr(fd, key, value, 0)
	_, err := bpf(cmdMapUpdateElem, attr)
	keepAlive(key, value)
	return err
}

func mapDelete(fd int, key []byte) error {
	attr := elemAttr(fd, key, nil, 0)
	_, err := bpf(cmdMapDeleteElem, attr)
	keepAlive(key)
	return err
}

func mapLookup(fd int, key, value []byte) error {
	attr := elemAttr(fd, key, value, 0)
	_, err := bpf(cmdMapLookupElem, attr)
	keepAlive(key, value)
	return err
}

// mapKeys walks every key currently in the map.
func mapKeys(fd int, keySize int) ([][]byte, error) {
	var keys [][]byte
	cur := make([]byte, keySize)
	next := make([]byte, keySize)
	first := true
	for {
		attr := attrBuf(32)
		binary.NativeEndian.PutUint32(attr[0:], uint32(fd))
		if !first {
			binary.NativeEndian.PutUint64(attr[8:], uint64(uintptr(unsafe.Pointer(&cur[0]))))
		}
		binary.NativeEndian.PutUint64(attr[16:], uint64(uintptr(unsafe.Pointer(&next[0]))))
		_, err := bpf(cmdMapGetNextKey, attr)
		keepAlive(cur, next)
		if err == syscall.ENOENT {
			return keys, nil
		}
		if err != nil {
			return keys, err
		}
		got := make([]byte, keySize)
		copy(got, next)
		keys = append(keys, got)
		copy(cur, next)
		first = false
		if len(keys) > 1<<20 {
			return keys, fmt.Errorf("map walk did not terminate")
		}
	}
}

func progLoad(insns []byte, license string, name string) (int, string, error) {
	logBuf := make([]byte, 1<<16)
	lic := append([]byte(license), 0)
	attr := attrBuf(80)
	binary.NativeEndian.PutUint32(attr[0:], progTypeSocketFilter)
	binary.NativeEndian.PutUint32(attr[4:], uint32(len(insns)/8))
	binary.NativeEndian.PutUint64(attr[8:], uint64(uintptr(unsafe.Pointer(&insns[0]))))
	binary.NativeEndian.PutUint64(attr[16:], uint64(uintptr(unsafe.Pointer(&lic[0]))))
	binary.NativeEndian.PutUint32(attr[24:], 1)
	binary.NativeEndian.PutUint32(attr[28:], uint32(len(logBuf)))
	binary.NativeEndian.PutUint64(attr[32:], uint64(uintptr(unsafe.Pointer(&logBuf[0]))))
	if len(name) > 15 {
		name = name[:15]
	}
	copy(attr[48:64], name)
	fd, err := bpf(cmdProgLoad, attr)
	if err != nil && oldAttr(err) {
		short := attrBuf(48)
		copy(short, attr[:48])
		fd, err = bpf(cmdProgLoad, short)
	}
	keepAlive(insns, lic, logBuf)
	verifier := string(trimZero(logBuf))
	if err != nil {
		return -1, verifier, fmt.Errorf("prog load %s: %w", name, err)
	}
	return fd, verifier, nil
}

func objPin(fd int, path string) error {
	p := append([]byte(path), 0)
	attr := attrBuf(24)
	binary.NativeEndian.PutUint64(attr[0:], uint64(uintptr(unsafe.Pointer(&p[0]))))
	binary.NativeEndian.PutUint32(attr[8:], uint32(fd))
	_, err := bpf(cmdObjPin, attr)
	keepAlive(p)
	if err != nil {
		return fmt.Errorf("pin %s: %w", path, err)
	}
	return nil
}

func objGet(path string) (int, error) {
	p := append([]byte(path), 0)
	attr := attrBuf(24)
	binary.NativeEndian.PutUint64(attr[0:], uint64(uintptr(unsafe.Pointer(&p[0]))))
	fd, err := bpf(cmdObjGet, attr)
	keepAlive(p)
	if err != nil {
		return -1, fmt.Errorf("open pinned %s: %w", path, err)
	}
	return fd, nil
}

// mapKeySize reads key_size from struct bpf_map_info (type, id, key_size, ...) of an open map.
func mapKeySize(fd int) (uint32, error) {
	info := make([]byte, 40)
	attr := attrBuf(16)
	binary.NativeEndian.PutUint32(attr[0:], uint32(fd))
	binary.NativeEndian.PutUint32(attr[4:], uint32(len(info)))
	binary.NativeEndian.PutUint64(attr[8:], uint64(uintptr(unsafe.Pointer(&info[0]))))
	_, err := bpf(cmdObjGetInfo, attr)
	keepAlive(info)
	if err != nil {
		return 0, fmt.Errorf("map info: %w", err)
	}
	return binary.NativeEndian.Uint32(info[8:]), nil
}

func trimZero(b []byte) []byte {
	for i, c := range b {
		if c == 0 {
			return b[:i]
		}
	}
	return b
}

// keepAlive stops the garbage collector from moving or freeing a buffer whose address the
// kernel is reading through the attr union.
func keepAlive(buffers ...[]byte) {
	for _, b := range buffers {
		if len(b) > 0 {
			runtime.KeepAlive(&b[0])
		}
	}
}
