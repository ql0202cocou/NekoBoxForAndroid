// socksprobe：核心回环实验用的 SOCKS5 探针（只用标准库，只连 127.0.0.1 / ::1）。
//
// 子命令：
//
//	echo   -listen 127.0.0.1:40053                  UDP 回显服务（回 "ECHO:" + 原文），前台运行
//	tcpserve -listen 127.0.0.1:40000                TCP 目标：每个连接读到一行后回 TARGET-REPLY-OK 并关闭，前台运行
//	tcp    -addr 127.0.0.1:31000 [-wait 5s]         连上后发一行，等回复：TCP-REPLY=… 或 TCP-NOREPLY；
//	                                                对端接受后复位连接时先多打一行 TCP-RESET=dial|write|read（撞上的阶段）
//	tlshello -listen 127.0.0.1:32000               TLS 服务端（临时自签证书），只打印每个 ClientHello 的 SNI / ALPN / 曲线 / 版本，前台运行
//	hs     -addr A -user U -pass P -mode M          SOCKS5 握手，打印服务端回的字节（十六进制）
//	                                                M = nocred（只报 00，也用来看是否静默不认证）| wrong（错密码）| ok（正确凭据）
//	udp    -addr A -user U -pass P -assoc H:P -target H:P [-from P] [-second] [-closetcp] [-raw P]
//	                                                UDP ASSOCIATE（请求里的 DST.ADDR:DST.PORT 由 -assoc 指定），
//	                                                从 127.0.0.1:<from> 发一个 SOCKS5 UDP 数据报到返回的 BND 端口，等回包。
//	                                                -second：首包回来后换一个本地端口再发一次（应被丢）
//	                                                -closetcp：先关掉 TCP 控制连接再发（应收不到）
//	                                                -raw P：不用 BND 端口，直接把数据报发到 127.0.0.1:P（例如入站自己的端口号）
//
// 输出一行一事实，供 run-lb.sh 判读：BND=…、R1 REPLY=… / R1 TIMEOUT（-second 时另有 R2、R3）、TCP-REPLY=…、TCP-RESET=…、HS=…、ERR=…
package main

import (
	"bufio"
	"bytes"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"flag"
	"fmt"
	"io"
	"math/big"
	"net"
	"net/netip"
	"os"
	"strconv"
	"syscall"
	"time"
)

func main() {
	if len(os.Args) < 2 {
		fmt.Println("usage: socksprobe echo|tcpserve|tcp|tlshello|hs|udp ...")
		os.Exit(2)
	}
	var err error
	switch os.Args[1] {
	case "echo":
		err = runEcho(os.Args[2:])
	case "tcpserve":
		err = runTCPServe(os.Args[2:])
	case "tcp":
		err = runTCP(os.Args[2:])
	case "tlshello":
		err = runTLSHello(os.Args[2:])
	case "hs":
		err = runHandshake(os.Args[2:])
	case "udp":
		err = runUDP(os.Args[2:])
	default:
		err = fmt.Errorf("unknown subcommand %q", os.Args[1])
	}
	if err != nil {
		fmt.Println("ERR=" + err.Error())
		os.Exit(1)
	}
}

func runEcho(args []string) error {
	fs := flag.NewFlagSet("echo", flag.ExitOnError)
	listen := fs.String("listen", "127.0.0.1:40053", "")
	fs.Parse(args)
	pc, err := net.ListenPacket("udp", *listen)
	if err != nil {
		return err
	}
	fmt.Println("ECHO-LISTEN=" + pc.LocalAddr().String())
	buf := make([]byte, 65535)
	for {
		n, from, err := pc.ReadFrom(buf)
		if err != nil {
			return err
		}
		fmt.Printf("ECHO-GOT from=%s len=%d\n", from, n)
		pc.WriteTo(append([]byte("ECHO:"), buf[:n]...), from)
	}
}

func runTLSHello(args []string) error {
	fs := flag.NewFlagSet("tlshello", flag.ExitOnError)
	listen := fs.String("listen", "127.0.0.1:32000", "")
	fs.Parse(args)
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return err
	}
	tpl := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "hello.example.com"},
		DNSNames: []string{"hello.example.com"}, NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour)}
	der, err := x509.CreateCertificate(rand.Reader, tpl, tpl, &key.PublicKey, key)
	if err != nil {
		return err
	}
	cert := tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key}
	cfg := &tls.Config{Certificates: []tls.Certificate{cert}, GetConfigForClient: func(h *tls.ClientHelloInfo) (*tls.Config, error) {
		fmt.Printf("HELLO sni=%s alpn=%v curves=%v versions=%x\n", h.ServerName, h.SupportedProtos, h.SupportedCurves, h.SupportedVersions)
		return nil, nil
	}}
	ln, err := tls.Listen("tcp", *listen, cfg)
	if err != nil {
		return err
	}
	fmt.Println("TLS-LISTEN=" + ln.Addr().String())
	for {
		c, err := ln.Accept()
		if err != nil {
			return err
		}
		go func(c net.Conn) {
			defer c.Close()
			c.SetDeadline(time.Now().Add(5 * time.Second))
			c.(*tls.Conn).Handshake()
		}(c)
	}
}

func runTCPServe(args []string) error {
	fs := flag.NewFlagSet("tcpserve", flag.ExitOnError)
	listen := fs.String("listen", "127.0.0.1:40000", "")
	fs.Parse(args)
	ln, err := net.Listen("tcp", *listen)
	if err != nil {
		return err
	}
	fmt.Println("TCP-LISTEN=" + ln.Addr().String())
	for {
		c, err := ln.Accept()
		if err != nil {
			return err
		}
		go func(c net.Conn) {
			defer c.Close()
			c.SetDeadline(time.Now().Add(20 * time.Second))
			b := make([]byte, 4096)
			n, _ := c.Read(b)
			fmt.Printf("TCP-GOT from=%s len=%d\n", c.RemoteAddr(), n)
			c.Write([]byte("TARGET-REPLY-OK\n"))
		}(c)
	}
}

func runTCP(args []string) error {
	fs := flag.NewFlagSet("tcp", flag.ExitOnError)
	addr := fs.String("addr", "127.0.0.1:31000", "")
	wait := fs.Duration("wait", 5*time.Second, "")
	fs.Parse(args)
	return dialAndProbeTCP(func() (net.Conn, error) {
		return net.DialTimeout("tcp", *addr, 3*time.Second)
	}, *wait, os.Stdout)
}

func dialAndProbeTCP(dial func() (net.Conn, error), wait time.Duration, output io.Writer) error {
	c, err := dial()
	if err != nil {
		if resetByPeer(err, "dial", output) {
			return nil
		}
		return err
	}
	defer c.Close()
	return probeTCP(c, wait, output)
}

// 对端接受连接后主动复位（如 sing-box 出站初始化失败时对入口连接 SetLinger(0) 再关）是一次有效观测：没有回复。
// 复位可能抢在连接完成、发送或读取任一阶段之前到达，三处都按同一结果输出；没有监听（connection refused）等其它错误不算。
func resetByPeer(err error, stage string, output io.Writer) bool {
	if !errors.Is(err, syscall.ECONNRESET) {
		return false
	}
	fmt.Fprintln(output, "TCP-RESET="+stage)
	fmt.Fprintln(output, "TCP-NOREPLY")
	return true
}

func probeTCP(c net.Conn, wait time.Duration, output io.Writer) error {
	c.SetDeadline(time.Now().Add(wait))
	if _, err := c.Write([]byte("ping-from-client\n")); err != nil {
		if resetByPeer(err, "write", output) {
			return nil
		}
		return err
	}
	// 一行最多 4096 字节；短读继续等待，残缺回复不能变成预期的连接失败。
	b, err := bufio.NewReaderSize(c, 4096).ReadSlice('\n')
	if err != nil {
		if len(b) > 0 {
			return fmt.Errorf("incomplete TCP reply: %w", err)
		}
		if !resetByPeer(err, "read", output) {
			fmt.Fprintln(output, "TCP-NOREPLY")
		}
		return nil
	}
	fmt.Fprintln(output, "TCP-REPLY="+string(bytes.TrimSpace(b)))
	return nil
}

func readN(c net.Conn, n int) ([]byte, error) {
	b := make([]byte, n)
	c.SetReadDeadline(time.Now().Add(3 * time.Second))
	_, err := io.ReadFull(c, b)
	return b, err
}

func greet(c net.Conn, user, pass, mode string) ([]byte, error) {
	var out []byte
	if mode == "nocred" {
		c.Write([]byte{5, 1, 0})
	} else {
		c.Write([]byte{5, 1, 2})
	}
	r, err := readN(c, 2)
	out = append(out, r...)
	if err != nil {
		return out, err
	}
	if r[1] != 2 {
		return out, nil
	}
	p := pass
	if mode == "wrong" {
		p = pass + "x"
	}
	req := []byte{1, byte(len(user))}
	req = append(req, user...)
	req = append(req, byte(len(p)))
	req = append(req, p...)
	c.Write(req)
	r2, err := readN(c, 2)
	out = append(out, r2...)
	return out, err
}

func runHandshake(args []string) error {
	fs := flag.NewFlagSet("hs", flag.ExitOnError)
	addr := fs.String("addr", "", "")
	user := fs.String("user", "", "")
	pass := fs.String("pass", "", "")
	mode := fs.String("mode", "ok", "")
	fs.Parse(args)
	c, err := net.DialTimeout("tcp", *addr, 3*time.Second)
	if err != nil {
		return err
	}
	defer c.Close()
	r, err := greet(c, *user, *pass, *mode)
	fmt.Println("HS=" + hex.EncodeToString(r))
	if err != nil && !errors.Is(err, io.EOF) {
		fmt.Println("HS-READ-ERR=" + err.Error())
	}
	return nil
}

func socksAddr(hp string) ([]byte, error) {
	host, ps, err := net.SplitHostPort(hp)
	if err != nil {
		return nil, err
	}
	port, err := strconv.Atoi(ps)
	if err != nil {
		return nil, err
	}
	var b []byte
	if ip, err := netip.ParseAddr(host); err == nil {
		if ip.Is4() {
			b = append([]byte{1}, ip.AsSlice()...)
		} else {
			b = append([]byte{4}, ip.AsSlice()...)
		}
	} else {
		b = append([]byte{3, byte(len(host))}, host...)
	}
	return binary.BigEndian.AppendUint16(b, uint16(port)), nil
}

func readSocksAddr(c net.Conn) (string, error) {
	h, err := readN(c, 1)
	if err != nil {
		return "", err
	}
	var host string
	switch h[0] {
	case 1:
		b, err := readN(c, 4)
		if err != nil {
			return "", err
		}
		host = netip.AddrFrom4([4]byte(b)).String()
	case 4:
		b, err := readN(c, 16)
		if err != nil {
			return "", err
		}
		host = netip.AddrFrom16([16]byte(b)).String()
	case 3:
		l, err := readN(c, 1)
		if err != nil {
			return "", err
		}
		b, err := readN(c, int(l[0]))
		if err != nil {
			return "", err
		}
		host = string(b)
	default:
		return "", fmt.Errorf("bad atyp %d", h[0])
	}
	p, err := readN(c, 2)
	if err != nil {
		return "", err
	}
	return net.JoinHostPort(host, strconv.Itoa(int(binary.BigEndian.Uint16(p)))), nil
}

func sendAndWait(uc *net.UDPConn, to *net.UDPAddr, target []byte, payload string) string {
	pkt := append([]byte{0, 0, 0}, target...)
	pkt = append(pkt, payload...)
	if _, err := uc.WriteToUDP(pkt, to); err != nil {
		return "SEND-ERR=" + err.Error()
	}
	uc.SetReadDeadline(time.Now().Add(3 * time.Second))
	buf := make([]byte, 65535)
	n, _, err := uc.ReadFromUDP(buf)
	if err != nil {
		return "TIMEOUT"
	}
	// 回包是 SOCKS5 UDP 头 + 数据；只取 "ECHO:" 之后的内容
	if i := bytes.Index(buf[:n], []byte("ECHO:")); i >= 0 {
		return "REPLY=" + string(buf[i:n])
	}
	return "REPLY-RAW=" + hex.EncodeToString(buf[:n])
}

func runUDP(args []string) error {
	fs := flag.NewFlagSet("udp", flag.ExitOnError)
	addr := fs.String("addr", "", "")
	user := fs.String("user", "", "")
	pass := fs.String("pass", "", "")
	assoc := fs.String("assoc", "0.0.0.0:0", "")
	target := fs.String("target", "127.0.0.1:40053", "")
	from := fs.Int("from", 0, "")
	second := fs.Bool("second", false, "")
	closeTCP := fs.Bool("closetcp", false, "")
	raw := fs.Int("raw", 0, "")
	fs.Parse(args)
	const payload = "udp-probe"

	c, err := net.DialTimeout("tcp", *addr, 3*time.Second)
	if err != nil {
		return err
	}
	defer c.Close()
	mode := "ok"
	if *user == "" {
		mode = "nocred"
	}
	r, err := greet(c, *user, *pass, mode)
	fmt.Println("HS=" + hex.EncodeToString(r))
	if err != nil {
		return err
	}
	a, err := socksAddr(*assoc)
	if err != nil {
		return err
	}
	c.Write(append([]byte{5, 3, 0}, a...))
	h, err := readN(c, 3)
	if err != nil {
		return fmt.Errorf("associate reply: %w", err)
	}
	if h[1] != 0 {
		fmt.Printf("ASSOC-REJECTED=%d\n", h[1])
		return nil
	}
	bnd, err := readSocksAddr(c)
	if err != nil {
		return err
	}
	fmt.Println("BND=" + bnd)
	bndAddr, err := net.ResolveUDPAddr("udp", bnd)
	if err != nil {
		return err
	}
	if *raw != 0 {
		bndAddr = &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: *raw}
		fmt.Println("RAW-TO=" + bndAddr.String())
	}
	t, err := socksAddr(*target)
	if err != nil {
		return err
	}
	uc, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: *from})
	if err != nil {
		return err
	}
	defer uc.Close()
	fmt.Println("FROM=" + uc.LocalAddr().String())
	if *closeTCP {
		c.Close()
		time.Sleep(500 * time.Millisecond)
	}
	fmt.Println("R1 " + sendAndWait(uc, bndAddr, t, payload))
	if *second {
		uc2, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
		if err != nil {
			return err
		}
		defer uc2.Close()
		fmt.Println("FROM2=" + uc2.LocalAddr().String())
		fmt.Println("R2 " + sendAndWait(uc2, bndAddr, t, payload+"-2"))
		fmt.Println("R3 " + sendAndWait(uc, bndAddr, t, payload+"-3"))
	}
	return nil
}
