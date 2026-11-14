# Xray 升级 v26.10.21 时补的 Vision 回环：VLESS + xtls-rprx-vision 在 RAW 上配 TLS / REALITY 的数据通路。
# v26.10.21 的官方二进制改用 Go 1.27.3 编译，Vision 读 TLS 内部缓冲的方式（#7176，proxy/vless/vless.go AccessTLSBuffers）这次才真正用上。
# 内层流量分两种：明文（只走 padding）、TLS 1.3（经同进程的 trojan TLS 入站，Vision 识别到 TLS 1.3 后切直拷，才会读那两个缓冲）。
# 材料与构件见上一级 probe_common.py。用法：python3 gen-vision.py（生成 loopback/）
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
from probe_common import (LOOPBACK_COLUMNS, TPW, UDP_ARGS, UUID, direct_out, entry_inbound, pinned_tls_client, reality_client,
                          reality_server, reality_target, reset_dir, tls_server, trojan, vless, write_suite)

LB = os.path.join(HERE, "loopback")
reset_dir(LB)


def cfg(sec="tls", inner_tls=False, udp=False, fp=None):
    """服务端 VLESS vision 入站 in-s :32000 + 客户端 VLESS vision 出站 out-c，同进程。
    inner_tls：客户端入口 :31000 → trojan TLS 出站（dialerProxy=out-c）→ … → 服务端 freedom → trojan TLS 入站 :33000 → freedom → :40000，
    这样 Vision 看到的内层是 TLS 1.3。udp：客户端入口是带凭据的 socks :31080（UDP 经 XUDP）。"""
    inb = [entry_inbound(udp)]
    srv = {"network": "tcp"}
    cli = {"network": "tcp"}
    if sec == "tls":
        srv.update(tls_server())
        cli.update({"security": "tls", "tlsSettings": pinned_tls_client(fp)})
    else:
        srv.update(reality_server())
        cli.update({"security": "reality", "realitySettings": reality_client(fp or "chrome")})
        inb.append(reality_target())
    inb.append({"tag": "in-s", "listen": "127.0.0.1", "port": 32000, "protocol": "vless",
                "settings": {"users": [{"id": UUID, "flow": "xtls-rprx-vision"}], "decryption": "none"}, "streamSettings": srv})
    outs = [{"tag": "block", "protocol": "blackhole"},
            {"tag": "out-c", **vless("127.0.0.1", 32000, stream=cli, flow="xtls-rprx-vision")}]
    first = "out-c"
    if inner_tls:
        inb.append({"tag": "in-inner", "listen": "127.0.0.1", "port": 33000, "protocol": "trojan",
                    "settings": {"users": [{"password": TPW}]}, "streamSettings": {"network": "tcp", **tls_server()}})
        outs.append({"tag": "inner", **trojan("127.0.0.1", 33000, stream={
            "network": "tcp", "security": "tls",
            "tlsSettings": pinned_tls_client(),
            "sockopt": {"dialerProxy": "out-c"}})})
        first = "inner"
    outs.append(direct_out("127.0.0.1:40053" if udp else None))
    return {"log": {"loglevel": "debug", "access": "none"}, "inbounds": inb, "outbounds": outs,
            "routing": {"rules": [{"type": "field", "inboundTag": ["in-c"], "outboundTag": first},
                                  {"type": "field", "inboundTag": ["in-s", "in-target", "in-inner"], "outboundTag": "direct"}]}}


# 判读子句同上一级 run-lb.sh；v26.11.9 的 CommandPaddingDirect=2，核对发送与接收的 Vision 命令。
# CopyRawConn 是 freedom 等路径共用的拷贝日志；不能据此断言 Vision 已切换或仍在 padding。
# 源码：https://github.com/XTLS/Xray-core/blob/v26.11.9/proxy/proxy.go
PADDED = "PASS && vision:PADDED"
DIRECT = "PASS && vision:DIRECT"
L = [
    ("tls-inner-plain", cfg("tls"), "tcp", "-", PADDED, "TLS（缺省指纹 chrome）+ vision，内层明文：只走 padding，不切直拷"),
    ("tls-inner-tls13", cfg("tls", inner_tls=True), "tcp", "-", DIRECT, "TLS + vision，内层 TLS 1.3：切直拷，读外层 TLS 缓冲（客户端 uTLS、服务端标准库 tls.Conn）"),
    ("reality-inner-plain", cfg("reality"), "tcp", "-", PADDED, "REALITY + vision，内层明文"),
    ("reality-inner-tls13", cfg("reality", inner_tls=True), "tcp", "-", DIRECT, "REALITY + vision，内层 TLS 1.3：切直拷（服务端 reality.Conn）"),
    ("tls-gotls-inner-tls13", cfg("tls", inner_tls=True, fp="unsafe"), "tcp", "-", DIRECT,
     "客户端 fingerprint=unsafe（标准库 TLS，不是 uTLS）：客户端一侧也走 Go 1.27.3 rawInput 是指针的分支；应用不生成这个值，只为覆盖代码路径"),
    ("tls-udp-xudp", cfg("tls", udp=True), "udp", UDP_ARGS, "UDP:REPLY", "TLS + vision，UDP 经 XUDP（生成器的缺省形状）"),
]
write_suite(LB, "XV", LOOPBACK_COLUMNS, [(slug, c, kind, args, expect, "10", "#7176", note) for slug, c, kind, args, expect, note in L])
print(len(L), "vision loopback configs")
