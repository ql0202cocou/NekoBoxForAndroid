package libcore

import (
	"os"
	"strings"
	"testing"
)

// 金丝雀：sing-box 的 REALITY 客户端有两处限制（固定自报版本 1.8.1、去掉
// X25519MLKEM768），服务端设了 minClientVer 时会表现为
// "reality verification failed"。应用里的提示与 NEKO.md 的
// "Accepted upstream behavior" 都建立在这些事实上。升级 sing-box 后若这些
// 内容变了，本测试先红，提醒回头核对。
const realityClientFile = "sing-box/common/tls/reality_client.go"

func readRealityClient(t *testing.T) string {
	t.Helper()
	data, err := os.ReadFile(realityClientFile)
	if err != nil {
		t.Fatalf("读不到 %s: %v", realityClientFile, err)
	}
	return string(data)
}

// 压缩空白，使断言不受缩进与换行影响。
func squashSpaces(s string) string {
	return strings.Join(strings.Fields(s), " ")
}

func realityCanaryHint() string {
	return "sing-box 的 REALITY 客户端实现变了：请核对 " + realityClientFile +
		" 的自报版本、X25519MLKEM768 处理与报错文本，对照新版 Xray 服务端默认 minClientVer，" +
		"并更新 libcore/sing-box/NEKO.md 的「Accepted upstream behavior」条目与应用里的 REALITY 失败提示"
}

func TestRealityClientErrorText(t *testing.T) {
	src := readRealityClient(t)
	if !strings.Contains(src, `E.New("reality verification failed")`) {
		t.Fatalf("握手失败的报错文本变了（应用按这句话附加提示）。%s", realityCanaryHint())
	}
}

func TestRealityClientFixedVersionBytes(t *testing.T) {
	src := squashSpaces(readRealityClient(t))
	for _, want := range []string{
		"hello.SessionId[0] = 1",
		"hello.SessionId[1] = 8",
		"hello.SessionId[2] = 1",
	} {
		if !strings.Contains(src, want) {
			t.Fatalf("固定自报版本 1.8.1 的写法变了，缺少 %q。%s", want, realityCanaryHint())
		}
	}
}

func TestRealityClientDropsX25519MLKEM768(t *testing.T) {
	src := squashSpaces(readRealityClient(t))
	for _, want := range []string{
		"curveID != utls.X25519MLKEM768",
		"share.Group != utls.X25519MLKEM768",
	} {
		if !strings.Contains(src, want) {
			t.Fatalf("去掉 X25519MLKEM768 的处理变了，缺少 %q。%s", want, realityCanaryHint())
		}
	}
}
