// httpdump：XHTTP 回环实验用的明文 TCP 探针（只用标准库，只监听本机）。
// 接受连接，读到第一块数据后打印：前 1500 字节（可见字符原样，其余转 \xNN）；若是 HTTP/1.x 请求则另打印 REQLINE= 与 HOST=。
// 最多等 2 秒读第一块数据；不回复，打印后再等 1 秒关闭。用于看 Xray 客户端在「无 TLS」时真正发出的请求（Host 头）。
// 用法：httpdump -listen 127.0.0.1:32000
package main

import (
	"flag"
	"fmt"
	"net"
	"os"
	"strings"
	"time"
)

func main() {
	listen := flag.String("listen", "127.0.0.1:32000", "")
	flag.Parse()
	ln, err := net.Listen("tcp", *listen)
	if err != nil {
		fmt.Println("ERR=" + err.Error())
		os.Exit(1)
	}
	fmt.Println("HTTPDUMP-LISTEN=" + ln.Addr().String())
	for {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		go handle(c)
	}
}

func handle(c net.Conn) {
	defer c.Close()
	c.SetReadDeadline(time.Now().Add(2 * time.Second))
	buf := make([]byte, 4096)
	n, _ := c.Read(buf)
	if n == 0 {
		fmt.Println("CONN no-data")
		return
	}
	data := buf[:n]
	show := data
	if len(show) > 1500 {
		show = show[:1500]
	}
	var sb strings.Builder
	for _, b := range show {
		if b >= 0x20 && b < 0x7f || b == '\r' || b == '\n' {
			sb.WriteByte(b)
		} else {
			fmt.Fprintf(&sb, "\\x%02x", b)
		}
	}
	text := strings.ReplaceAll(sb.String(), "\r\n", "\n")
	for _, line := range strings.Split(text, "\n") {
		if line != "" {
			fmt.Println("RAW| " + line)
		}
	}
	head := string(data)
	if i := strings.Index(head, "\r\n"); i > 0 && strings.Contains(head[:i], "HTTP/1.") {
		fmt.Println("REQLINE=" + head[:i])
		for _, l := range strings.Split(head, "\r\n")[1:] {
			if strings.HasPrefix(strings.ToLower(l), "host:") {
				fmt.Println("HOST=" + strings.TrimSpace(l[5:]))
			}
		}
	} else if strings.HasPrefix(head, "PRI * HTTP/2.0") {
		fmt.Println("H2C-PREFACE")
	}
	time.Sleep(1 * time.Second)
}
