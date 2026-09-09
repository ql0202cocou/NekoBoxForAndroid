package libcore

import (
	"fmt"
	"strings"

	"libcore/device"
	"libcore/stun"
)

type StunResult struct {
	Text    string
	Success bool
}

func StunTest(server string) (ret *StunResult) {
	ret = &StunResult{}
	defer device.DeferPanicToError("StunTest", func(err error) { ret.Text = err.Error() })

	//note: this library doesn't support stun1.l.google.com:19302
	var text string

	// Old NAT Type Test
	client := stun.NewClient()
	client.SetServerAddr(server)
	nat, host, discoverErr, fakeFullCone := client.Discover()
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
