package libcore

// https://github.com/golang/go/issues/46893
// TODO: remove after `bulkBarrierPreWrite: unaligned arguments` fixed
// Fixed in cmd/cgo for Go 1.26 (CL 692935 aligns the export argument
// struct); keep this while the toolchain pin is on Go 1.25.

type StringBox struct {
	Value string
}

func wrapString(value string) *StringBox {
	return &StringBox{Value: value}
}
