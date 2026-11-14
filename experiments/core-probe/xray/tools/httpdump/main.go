// httpdump：XHTTP 回环实验用的明文 TCP 探针（只用标准库，只监听本机）。
// HTTP 读完头部（最多 4096 字节），二进制流读首块；打印前 1500 字节，HTTP/1.x 另打印 REQLINE= 与 HOST=。
// 读取最多等 2 秒，残缺头部报告 HTTPDUMP-ERROR；不回复，打印后再等 1 秒关闭。用于观察实际请求的 Host 头。
// 用法：httpdump -listen 127.0.0.1:32000
package main

import (
	"bytes"
	"flag"
	"fmt"
	"io"
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
	data, err := readCapture(c)
	fmt.Print(formatCapture(data, err))
	time.Sleep(1 * time.Second)
}

func readCapture(r io.Reader) ([]byte, error) {
	buf := make([]byte, 4096)
	n := 0
	for n < len(buf) {
		count, err := r.Read(buf[n:])
		n += count
		// TLS 等二进制流只用于判断是否拨向 TCP，不按 HTTP 解析。
		if n > 0 && (buf[0] < 0x20 || buf[0] >= 0x7f) {
			return buf[:n], nil
		}
		if end := bytes.Index(buf[:n], []byte("\r\n\r\n")); end >= 0 {
			return buf[:end+4], nil
		}
		if err != nil {
			return buf[:n], err
		}
	}
	return buf[:n], fmt.Errorf("HTTP header exceeds %d bytes", len(buf))
}

func formatCapture(data []byte, err error) string {
	var output strings.Builder
	if err != nil {
		fmt.Fprintf(&output, "HTTPDUMP-ERROR=%v\n", err)
	}
	if len(data) == 0 {
		fmt.Fprintln(&output, "CONN no-data")
		return output.String()
	}
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
			fmt.Fprintln(&output, "RAW| "+line)
		}
	}
	if err != nil {
		return output.String()
	}
	head := string(data)
	if i := strings.Index(head, "\r\n"); i > 0 && strings.Contains(head[:i], "HTTP/1.") {
		fmt.Fprintln(&output, "REQLINE="+head[:i])
		for _, l := range strings.Split(head, "\r\n")[1:] {
			if strings.HasPrefix(strings.ToLower(l), "host:") {
				fmt.Fprintln(&output, "HOST="+strings.TrimSpace(l[5:]))
			}
		}
	} else if strings.HasPrefix(head, "PRI * HTTP/2.0") {
		fmt.Fprintln(&output, "H2C-PREFACE")
	}
	return output.String()
}
