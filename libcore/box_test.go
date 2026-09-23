package libcore

import (
	"strings"
	"sync"
	"testing"
)

// Minimal config that starts on desktop: no inbounds (nothing to bind), a
// single direct outbound, and panic log level to keep test output quiet.
const lifecycleBoxConfig = `{
	"log": {"level": "panic"},
	"outbounds": [{"type": "direct", "tag": "direct"}]
}`

func newTestBox(t *testing.T) *BoxInstance {
	t.Helper()
	instance, err := NewSingBoxInstance(lifecycleBoxConfig, nil)
	if err != nil {
		t.Fatal(err)
	}
	return instance
}

func newStartedTestBox(t *testing.T) *BoxInstance {
	t.Helper()
	instance := newTestBox(t)
	if err := instance.Start(); err != nil {
		t.Fatal(err)
	}
	return instance
}

func TestBoxInstanceLifecycle(t *testing.T) {
	instance := newTestBox(t)

	// before SetV2rayStats every query reads as zero
	if got := instance.QueryStats("direct", "uplink"); got != 0 {
		t.Fatalf("QueryStats before SetV2rayStats = %d", got)
	}
	// no "proxy" selector outbound in this config
	if instance.SelectOutbound("direct") {
		t.Fatal("SelectOutbound without selector = true")
	}

	if err := instance.Start(); err != nil {
		t.Fatal(err)
	}
	// the state machine moves to boxStarted on the first start and never back
	if err := instance.Start(); err == nil || !strings.Contains(err.Error(), "already started") {
		t.Fatalf("second Start = %v", err)
	}

	// pause manager is installed by the box; both calls must be no-ops here
	instance.Sleep()
	instance.Wake()

	if err := instance.Close(); err != nil {
		t.Fatal(err)
	}
	// double close is a no-op
	if err := instance.Close(); err != nil {
		t.Fatalf("second Close = %v", err)
	}
	// boxClosed is not boxNew either, so Start keeps reporting "already started"
	if err := instance.Start(); err == nil || !strings.Contains(err.Error(), "already started") {
		t.Fatalf("Start after Close = %v", err)
	}
}

func TestBoxInstanceCloseBeforeStart(t *testing.T) {
	instance := newTestBox(t)
	if err := instance.Close(); err != nil {
		t.Fatalf("Close before Start = %v", err)
	}
	if err := instance.Close(); err != nil {
		t.Fatalf("second Close = %v", err)
	}
}

func TestBoxInstanceSetAsMainClearsOnClose(t *testing.T) {
	instance := newTestBox(t)
	instance.SetAsMain()
	if getMainInstance() != instance {
		t.Fatal("SetAsMain did not install the instance")
	}
	if err := instance.Close(); err != nil {
		t.Fatal(err)
	}
	if getMainInstance() != nil {
		t.Fatal("Close did not clear the main instance")
	}
}

// SetV2rayStats installs the tracker under b.access and QueryStats reads it
// under the same lock; the race detector validates that pairing.
func TestBoxInstanceSetV2rayStatsRace(t *testing.T) {
	instance := newStartedTestBox(t)

	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			for j := 0; j < 5; j++ {
				if i%2 == 0 {
					// only the first call installs; the rest log a duplicate warning
					instance.SetV2rayStats("direct")
				} else {
					for k := 0; k < 10; k++ {
						instance.QueryStats("direct", "uplink")
					}
				}
			}
		}(i)
	}
	wg.Wait()

	// the winner installed exactly one tracker serving both directions
	if got := instance.QueryStats("direct", "uplink"); got != 0 {
		t.Fatalf("QueryStats = %d, want 0 (no traffic)", got)
	}
	if err := instance.Close(); err != nil {
		t.Fatal(err)
	}
}

// SetV2rayStats/QueryStats after Close must be no-ops: AppendTracker on a
// closed box would panic, and a late UI call must not crash the process.
func TestBoxInstanceStatsAfterClose(t *testing.T) {
	instance := newStartedTestBox(t)
	if err := instance.Close(); err != nil {
		t.Fatal(err)
	}

	instance.SetV2rayStats("direct")
	if instance.v2api != nil {
		t.Fatal("SetV2rayStats installed a tracker on a closed instance")
	}
	if got := instance.QueryStats("direct", "uplink"); got != 0 {
		t.Fatalf("QueryStats after Close = %d", got)
	}
}

// SetAsMain on a closed instance must not install it: UrlTest and
// ResetAllConnections would otherwise run against a torn-down box.
func TestBoxInstanceSetAsMainAfterClose(t *testing.T) {
	instance := newTestBox(t)
	if err := instance.Close(); err != nil {
		t.Fatal(err)
	}

	instance.SetAsMain()
	if getMainInstance() == instance {
		t.Fatal("SetAsMain installed a closed instance as main")
	}
}

// ResetAllConnections must skip a closed main instance: ResetNetwork on a
// torn-down box calls InterfaceUpdated on closed endpoints with no
// guaranteed behavior.
func TestBoxInstanceResetAllConnectionsAfterClose(t *testing.T) {
	instance := newStartedTestBox(t)
	if err := instance.Close(); err != nil {
		t.Fatal(err)
	}
	// Close normally clears the reference; reinstall the closed instance so
	// that the state check inside ResetAllConnections is the thing under test
	mainInstanceAccess.Lock()
	mainInstance = instance
	mainInstanceAccess.Unlock()
	defer func() {
		mainInstanceAccess.Lock()
		mainInstance = nil
		mainInstanceAccess.Unlock()
	}()

	ResetAllConnections()
}
