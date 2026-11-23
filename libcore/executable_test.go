package libcore

import (
	"debug/elf"
	"testing"
)

func TestExecutableLoadSegmentPageSize(t *testing.T) {
	for _, tc := range []struct {
		name     string
		prog     elf.ProgHeader
		conflict bool
	}{
		{"trojan-go", elf.ProgHeader{Type: elf.PT_LOAD, Off: 0x872590, Vaddr: 0x873590, Filesz: 1, Align: 4096}, true},
		{"naive", elf.ProgHeader{Type: elf.PT_LOAD, Off: 0x3bb470, Vaddr: 0x3bc470, Filesz: 1, Align: 4096}, true},
		{"matching-offset", elf.ProgHeader{Type: elf.PT_LOAD, Off: 0x1470, Vaddr: 0x5470, Filesz: 1, Align: 4096}, false},
		{"zero-fill", elf.ProgHeader{Type: elf.PT_LOAD, Off: 0, Vaddr: 4096}, false},
		{"non-load", elf.ProgHeader{Type: elf.PT_NOTE, Off: 0, Vaddr: 4096, Filesz: 1}, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if incompatibleLoadSegment(tc.prog, 4096) || incompatibleLoadSegment(tc.prog, 16384) != tc.conflict {
				t.Fatal("unexpected page compatibility")
			}
		})
	}
}
