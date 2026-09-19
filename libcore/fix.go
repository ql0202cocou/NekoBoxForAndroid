package libcore

// https://github.com/golang/go/issues/46893
// Fixed in cmd/cgo for Go 1.26 (CL 692935 aligns the export argument
// struct). The wrapper stays even on 1.26: *StringBox is part of the
// gomobile-bound API surface consumed by the app, and upstream sing-box
// libbox keeps the same pattern.

type StringBox struct {
	Value string
}

func wrapString(value string) *StringBox {
	return &StringBox{Value: value}
}
