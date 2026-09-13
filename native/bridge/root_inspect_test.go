package main

import "testing"

func TestRootInspectKeepsCustomListeners(t *testing.T) {
	s := sample + "listeners:\n  - name: root-tproxy\n    type: tproxy\n    port: 9898\nrouting-mark: 255\ninterface-name: wlan0\n"
	data, err := inspectRoot(request{YAML: s})
	if err != nil {
		t.Fatal(err)
	}
	if ok, _ := data["compatible"].(bool); !ok {
		t.Fatal("root config not marked compatible")
	}
	if _, err := prepare(request{YAML: s}); err == nil {
		t.Fatal("Android TUN validator must still reject custom listeners")
	}
}
