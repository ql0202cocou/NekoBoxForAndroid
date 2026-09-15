package libcore

import (
	"os"
	"path/filepath"
	"slices"
	"testing"

	"github.com/sagernet/sing-box/constant"
)

// resourcePaths aliases sing-box's private constant.resourcePaths through a
// linkname (see nb4a.go). This verifies an appended directory is actually
// honored by constant.FindPath, so a broken linkname fails here instead of
// silently making the external assets directory unreachable at runtime.
func TestResourcePathsLinkname(t *testing.T) {
	dir := t.TempDir()
	const name = "nb4a-resource-paths-test.db"
	if err := os.WriteFile(filepath.Join(dir, name), []byte{0}, 0o600); err != nil {
		t.Fatal(err)
	}
	if !slices.Contains(resourcePaths, dir) {
		resourcePaths = append(resourcePaths, dir)
		t.Cleanup(func() {
			resourcePaths = slices.DeleteFunc(resourcePaths, func(p string) bool { return p == dir })
		})
	}
	path, found := constant.FindPath(name)
	if !found || filepath.Dir(path) != dir {
		t.Fatalf("FindPath did not resolve %q from the appended dir: %q, %v", name, path, found)
	}
}
