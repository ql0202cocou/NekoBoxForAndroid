package libcore

import (
	"fmt"
	"strings"
	"time"

	"libcore/device"
	"libcore/stun"
)

type StunResult struct {
	Text    string
	Success bool
}

// Time budget of a whole StunTest run (Discover + BehaviorTest combined).
// With the retransmission parameters below a single STUN test blocks at most
// 100+200+400+800+1600 = 3.1s, and every read is additionally capped at this
// overall deadline, so a call always returns within ~25s even when UDP is
// black-holed; with the RFC 3489 library defaults it could block for over a
// minute with no way to cancel.
const stunTestBudget = 25 * time.Second

func StunTest(server string) (ret *StunResult) {
	ret = &StunResult{}
	defer device.DeferPanicToError("StunTest", func(err error) { ret.Text = err.Error() })

	//note: this library doesn't support stun1.l.google.com:19302
	var text string

	// Old NAT Type Test
	client := stun.NewClient()
	client.SetServerAddr(server)
	client.SetDeadline(time.Now().Add(stunTestBudget))
	client.SetRetransmission(5, 100*time.Millisecond, 1600*time.Millisecond)
	nat, host, fakeFullCone, discoverErr := client.Discover()
	if discoverErr != nil {
		text += fmt.Sprintln("Discover Error:", discoverErr.Error())
	} else {
		text += fmt.Sprintln("NAT Type:", nat)
	}

	if fakeFullCone {
		text += fmt.Sprintln("Fake fullcone (no endpoint IP change) detected!!")
	}

	if host != nil {
		text += fmt.Sprintln("External IP Family:", host.Family())
		text += fmt.Sprintln("External IP:", host.IP())
		text += fmt.Sprintln("External Port:", host.Port())
	}

	// New NAT Test

	natBehavior, err := client.BehaviorTest()
	if err != nil {
		text += fmt.Sprintln("BehaviorTest Error:", err.Error())
	}

	if natBehavior != nil {
		text += fmt.Sprintln("Mapping Behavior:", natBehavior.MappingType)
		text += fmt.Sprintln("Filtering Behavior:", natBehavior.FilteringType)
		text += fmt.Sprintln("Normal NAT Type:", natBehavior.NormalType())
	}

	// Discover is the test proper; a BehaviorTest failure (e.g. "Not behind a
	// NAT.") is reported inside the text instead of failing the whole run.
	ret.Success = discoverErr == nil
	ret.Text = strings.TrimRight(text, "\n")
	return ret
}
