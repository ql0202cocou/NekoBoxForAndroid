// Package procfs resolves the UID owning a socket via /proc/net/{tcp,tcp6,udp,udp6}.
//
// Vendored from sing-box's experimental/libbox/internal/procfs, with local
// divergences — do NOT blind-sync with upstream:
//  1. Upstream's two init() functions (endianness detection and header parse)
//     are merged into one here.
//  2. init() failure paths log instead of failing silently, because upstream
//     leaves the -1 indexes indistinguishable from a real "not found".
//  3. ResolveSocketByProcSearch continues on strconv.Atoi failure where
//     upstream returns -1.
package procfs

import (
	"bufio"
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"log"
	"net"
	"net/netip"
	"os"
	"strconv"
	"strings"
	"unsafe"

	N "github.com/sagernet/sing/common/network"
)

var (
	netIndexOfLocal = -1
	netIndexOfUid   = -1
	nativeEndian    binary.ByteOrder
)

func init() {
	var x uint32 = 0x01020304
	if *(*byte)(unsafe.Pointer(&x)) == 0x01 {
		nativeEndian = binary.BigEndian
	} else {
		nativeEndian = binary.LittleEndian
	}

	// Column indexes are learned once from the tcp header and then applied to
	// tcp6/udp/udp6 in ResolveSocketByProcSearch, assuming all four files share
	// the same column layout (holds on Linux: they are emitted by the same
	// kernel seq_file code).
	file, err := os.Open("/proc/net/tcp")
	if err != nil {
		// Without the header parse below, ResolveSocketByProcSearch always
		// returns -1, indistinguishable from a real "not found" at the caller.
		log.Println("procfs: open /proc/net/tcp failed:", err)
		return
	}

	defer file.Close()

	reader := bufio.NewReader(file)

	header, _, err := reader.ReadLine()
	if err != nil {
		log.Println("procfs: read /proc/net/tcp header failed:", err)
		return
	}

	columns := strings.Fields(string(header))

	var txQueue, rxQueue, tr, tmWhen bool

	for idx, col := range columns {
		offset := 0

		if txQueue && rxQueue {
			offset--
		}

		if tr && tmWhen {
			offset--
		}

		switch col {
		case "tx_queue":
			txQueue = true
		case "rx_queue":
			rxQueue = true
		case "tr":
			tr = true
		case "tm->when":
			tmWhen = true
		case "local_address":
			netIndexOfLocal = idx + offset
		case "uid":
			netIndexOfUid = idx + offset
		}
	}
}

func ResolveSocketByProcSearch(network string, source, _ netip.AddrPort) int32 {
	if netIndexOfLocal < 0 || netIndexOfUid < 0 {
		return -1
	}

	path := "/proc/net/"

	if network == N.NetworkTCP {
		path += "tcp"
	} else {
		path += "udp"
	}

	if source.Addr().Is6() {
		path += "6"
	}

	sIP := source.Addr().AsSlice()
	if len(sIP) == 0 {
		return -1
	}

	var bytes [2]byte
	binary.BigEndian.PutUint16(bytes[:], source.Port())
	local := fmt.Sprintf("%s:%s", hex.EncodeToString(nativeEndianIP(sIP)), hex.EncodeToString(bytes[:]))

	file, err := os.Open(path)
	if err != nil {
		return -1
	}

	defer file.Close()

	reader := bufio.NewReader(file)

	for {
		row, _, err := reader.ReadLine()
		if err != nil {
			return -1
		}

		fields := strings.Fields(string(row))

		if len(fields) <= netIndexOfLocal || len(fields) <= netIndexOfUid {
			continue
		}

		if strings.EqualFold(local, fields[netIndexOfLocal]) {
			uid, err := strconv.Atoi(fields[netIndexOfUid])
			if err != nil {
				continue
			}

			return int32(uid)
		}
	}
}

func nativeEndianIP(ip net.IP) []byte {
	result := make([]byte, len(ip))

	for i := 0; i < len(ip); i += 4 {
		value := binary.BigEndian.Uint32(ip[i:])

		nativeEndian.PutUint32(result[i:], value)
	}

	return result
}
