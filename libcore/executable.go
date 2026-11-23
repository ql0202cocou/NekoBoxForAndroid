package libcore

import (
	"debug/elf"
	"os"
)

// IncompatibleExecutablePageSize 仅检查内核直接执行 ELF 时必须满足的加载段偏移条件。
// 可正常映射的旧二进制即使声明 4 KiB 对齐也要放行；其它格式仍交给原启动路径。
func IncompatibleExecutablePageSize(path string) int32 {
	pageSize := uint64(os.Getpagesize())
	if pageSize <= 4096 {
		return 0
	}
	f, err := elf.Open(path)
	if err != nil {
		return 0
	}
	defer f.Close()
	if f.Type == elf.ET_EXEC || f.Type == elf.ET_DYN {
		for _, prog := range f.Progs {
			if incompatibleLoadSegment(prog.ProgHeader, pageSize) {
				return int32(pageSize)
			}
		}
	}
	return 0
}

func incompatibleLoadSegment(p elf.ProgHeader, pageSize uint64) bool {
	return p.Type == elf.PT_LOAD && p.Filesz != 0 && p.Off%pageSize != p.Vaddr%pageSize
}
