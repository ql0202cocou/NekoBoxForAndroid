# 为 Xray 生成探测配置（目标版本见 XRAY_VER）（run -test / convert pb -debug 组与回环组）。
# 全部材料虚构：域名只用 example.com / .net / .org 及其子域，IPv4 只用 192.0.2.0/24、198.51.100.0/24、203.0.113.0/24（与 127.0.0.1），
# IPv6 只用 2001:db8::/32（与 ::1）；证书复用「Example Test CA」（fixtures/pki），REALITY 密钥复用 fixtures/ 中的 x25519.txt / mldsa.txt。
# 用法：python3 gen.py  （在本目录生成 test/ 与 loopback/，覆盖旧文件）
import base64, copy, os

from probe_common import (ALLOW_LOOP, CA, FULL, LEAF, LEAF_KEY, LEAF_PIN, LOOPBACK_COLUMNS, P, PRIV, PUB, SID, TEST_COLUMNS, TPW, U, UDP_ARGS, UUID,
                          der_sha256, entry_inbound, load_kv, pki, reality_target, reset_dir, tls_server, trojan, vless, vmess, write_suite)

HERE = os.path.dirname(os.path.abspath(__file__))
TEST = os.path.join(HERE, "test")
LB = os.path.join(HERE, "loopback")
for d in (TEST, LB):
    reset_dir(d)

# ---------------- 虚构材料（共用部分见 probe_common.py）
PBK_FAKE = "4D3ynC7iq5De6gng9hCVIfGAYuVTmjB52__M2pgyCnc"
MLDSA_FAKE = base64.urlsafe_b64encode(bytes(range(256)) * 7 + bytes(160)).rstrip(b"=").decode()
assert len(MLDSA_FAKE) == 2603
PIN_RAND = "b8463a194404b23d4b81ca841786d18431dc6d5447cd9a122c2fe7b0f8a47ebb"
CA_PIN = der_sha256("ca.pem")
PIN_COLON = ":".join(LEAF_PIN[i:i + 2] for i in range(0, 64, 2)).upper()
PIN_B64 = base64.b64encode(bytes.fromhex(LEAF_PIN)).decode()
ECH_B64 = base64.b64encode(b"\x00\x45\xfe\x0d\x00\x41" + bytes(63)).decode()
FAKE_PEM = ["-----BEGIN CERTIFICATE-----", "MIIBszCCAVmgAwIBAgIUZm9vYmFyZXhhbXBsZTAKBggqhkjOPQQDAjAWMRQwEgYD", "-----END CERTIFICATE-----"]
VLESS_ENC = "mlkem768x25519plus.native.0rtt.pd2cwozLOm-0CUxwd8y5pqyyNyy-vkdLFEd7K_YqyFI"  # 用 xray vlessenc 生成
BFULL, BLEAF_KEY = pki("bfull.pem"), pki("bleaf.pk8")
mk = load_kv("mldsa.txt")
SEED, VERIFY = mk["Seed"], mk["Verify"]

# Xray v26.9.30 transport/internet/tls/tls.go 的三张表（PresetFingerprints / ModernFingerprints / OtherFingerprints）
FP_ALL = [
    "chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq", "random", "randomized", "randomizednoalpn", "unsafe",
    "hellofirefox_120", "hellofirefox_148", "hellochrome_120", "hellochrome_131", "hellochrome_133", "helloios_13", "helloios_14",
    "helloedge_106", "hellosafari_26_3", "hello360_11_0", "helloqq_11_1",
    "hellogolang", "hellorandomized", "hellorandomizedalpn", "hellorandomizednoalpn", "hellofirefox_auto", "hellofirefox_55",
    "hellofirefox_56", "hellofirefox_63", "hellofirefox_65", "hellofirefox_99", "hellofirefox_102", "hellofirefox_105",
    "hellochrome_auto", "hellochrome_58", "hellochrome_62", "hellochrome_70", "hellochrome_72", "hellochrome_83", "hellochrome_87",
    "hellochrome_96", "hellochrome_100", "hellochrome_102", "hellochrome_106_shuffle", "helloios_auto", "helloios_11_1",
    "helloios_12_1", "helloandroid_11_okhttp", "helloedge_85", "helloedge_auto", "hellosafari_16_0", "hellosafari_auto",
    "hello360_auto", "hello360_7_5", "helloqq_auto", "hellochrome_100_psk", "hellochrome_112_psk_shuf",
    "hellochrome_114_padding_psk_shuf", "hellochrome_115_pq", "hellochrome_115_pq_psk", "hellochrome_120_pq",
]
assert len(FP_ALL) == 63 and len(set(FP_ALL)) == 63

# ---------------- 生成器同形的构件（buildXrayConfig / buildXrayOutbound）


def inbound(i):
    return {"tag": f"in-{i}", "listen": "127.0.0.1", "port": 21000 + i, "protocol": "socks",
            "settings": {"auth": "password", "accounts": [{"user": U, "pass": P}], "udp": True}}


def merged(*outs, log=None):
    inb = [inbound(i) for i in range(len(outs))]
    obs = [{"tag": "block", "protocol": "blackhole"}]
    rules = []
    for i, o in enumerate(outs):
        oo = {"tag": f"out-{i}"}
        oo.update(o)
        obs.append(oo)
        rules.append({"type": "field", "inboundTag": [f"in-{i}"], "outboundTag": f"out-{i}"})
    return {"log": log or {"loglevel": "warning", "access": "none"}, "inbounds": inb, "outbounds": obs,
            "routing": {"domainStrategy": "AsIs", "rules": rules}}


NETS = {
    "tcp": {"network": "tcp"},
    "ws": {"network": "ws", "wsSettings": {"path": "/ws", "headers": {"Host": "cdn.example.net"}}},
    "grpc": {"network": "grpc", "grpcSettings": {"serviceName": "svc"}},
    "httpupgrade": {"network": "httpupgrade", "httpupgradeSettings": {"host": "cdn.example.net", "path": "/up"}},
    "tcphttp": {"network": "tcp", "tcpSettings": {"header": {"type": "http", "request": {
        "path": ["/"], "headers": {"Host": ["a.example.com", "b.example.com"]}}}}},
    "xhttp": {"network": "xhttp", "xhttpSettings": {"path": "/xh", "host": "cdn.example.net"}},
}


def st(net="tcp", sec=None, **kw):
    s = copy.deepcopy(NETS[net])
    if sec == "tls":
        t = {"serverName": "node.example.com"}
        t.update(kw)
        s["security"] = "tls"
        s["tlsSettings"] = t
    elif sec == "reality":
        r = {"serverName": "www.example.org", "publicKey": PBK_FAKE, "shortId": SID, "fingerprint": "chrome"}
        r.update(kw)
        s["security"] = "reality"
        s["realitySettings"] = {k: v for k, v in r.items() if v is not None}
    elif sec == "none":
        s["security"] = "none"
    return s


# ---------------- run -test / convert pb -debug 用例
T = []  # (slug, config, cmd, expect, item, src, note)


def t(slug, cfg, expect, item, src, note="", cmd="test"):
    T.append((slug, cfg, cmd, expect, item, src, note))


E_VLESS = "vless without TLS or other encryption is prohibited unless the server address is a private IP or domain"
E_TROJAN = "trojan without TLS is prohibited unless the server address is a private IP or domain"

# --- Q1 未加密出站（d7fa2076 / 65458e91 / a1bf968b）
t("q1-vless-none-loopback", merged(vless("127.0.0.1")), "OK", "Q1", "infra/conf/xray.go,common/geodata/consts.go", "生成器形状：经映射拨 127.0.0.1")
t("q1-vless-none-docnet-v4", merged(vless("203.0.113.10")), "ERR:" + E_VLESS, "Q1", "common/geodata/consts.go", "v26.10.31 起 IP 字面量只看 IsPrivate / IsLoopback / IsLinkLocalUnicast，文档地址段算公网（v26.10.21 及以前是 OK）")
t("q1-vless-none-docnet-v6", merged(vless("2001:db8::10")), "ERR:" + E_VLESS, "Q1", "infra/conf/xray.go,common/geodata/consts.go", "2001:db8::/32 不在名单里，算公网")
t("q1-vless-none-domain", merged(vless("node.example.com")), "ERR:" + E_VLESS, "Q1", "infra/conf/xray.go,common/geodata/consts.go", "普通域名算公网")
t("q1-vless-none-localhost", merged(vless("localhost")), "OK", "Q1", "common/geodata/consts.go", "localhost 在私有域名名单")
t("q1-vless-none-dotless", merged(vless("router")), "OK", "Q1", "common/geodata/consts.go", "无点域名算私有")
t("q1-vless-none-dot-internal", merged(vless("nas.internal")), "OK", "Q1", "common/geodata/consts.go", ".internal 算私有")
t("q1-vless-none-v6-loopback", merged(vless("::1")), "OK", "Q1", "common/geodata/consts.go", "::1 是 IsLoopback")
t("q1-vless-tls-domain", merged(vless("node.example.com", stream=st("tcp", "tls"))), "OK", "Q1", "infra/conf/xray.go", "有 TLS 不查地址")
t("q1-vless-reality-domain", merged(vless("node.example.com", stream=st("tcp", "reality"))), "OK", "Q1", "infra/conf/xray.go", "REALITY 也算有安全层")
t("q1-vless-explicit-none-domain", merged(vless("node.example.com", stream=st("tcp", "none"))), "ERR:" + E_VLESS, "Q1", "infra/conf/xray.go", "security 写 none 与不写相同")
t("q1-vless-ws-none-domain", merged(vless("node.example.com", stream=st("ws"))), "ERR:" + E_VLESS, "Q1", "infra/conf/xray.go", "与传输无关")
t("q1-vless-grpc-none-domain", merged(vless("node.example.com", stream=st("grpc"))), "ERR:" + E_VLESS, "Q1", "infra/conf/xray.go", "与传输无关")
t("q1-vless-hu-none-domain", merged(vless("node.example.com", stream=st("httpupgrade"))), "ERR:" + E_VLESS, "Q1", "infra/conf/xray.go", "与传输无关")
t("q1-vless-tcphttp-none-domain", merged(vless("node.example.com", stream=st("tcphttp"))), "ERR:" + E_VLESS, "Q1", "infra/conf/xray.go", "http 头伪装不算安全层")
t("q1-vless-flat-none-domain", merged(vless("node.example.com", flat=True)), "ERR:" + E_VLESS, "Q1", "infra/conf/xray.go,infra/conf/vless.go", "扁平写法")
t("q1-vless-flat-none-loopback", merged(vless("127.0.0.1", flat=True)), "OK", "Q1", "infra/conf/xray.go", "扁平写法，本机")
t("q1-vless-vlessenc-domain", merged(vless("node.example.com", enc=VLESS_ENC)), "OK", "Q1", "infra/conf/xray.go,infra/conf/vless.go", "VLESS Encryption 可绕过（唯一的绕过方式）；本应用不写")
t("q1-trojan-none-loopback", merged(trojan("127.0.0.1")), "OK", "Q1", "infra/conf/xray.go", "")
t("q1-trojan-none-docnet-v4", merged(trojan("198.51.100.7")), "ERR:" + E_TROJAN, "Q1", "common/geodata/consts.go", "v26.10.31 起文档地址段算公网（v26.10.21 及以前是 OK）")
t("q1-trojan-none-domain", merged(trojan("node.example.com")), "ERR:" + E_TROJAN, "Q1", "infra/conf/xray.go", "")
t("q1-trojan-none-docnet-v6", merged(trojan("2001:db8::20")), "ERR:" + E_TROJAN, "Q1", "infra/conf/xray.go,common/geodata/consts.go", "")
t("q1-trojan-flat-none-domain", merged(trojan("node.example.com", flat=True)), "ERR:" + E_TROJAN, "Q1", "infra/conf/xray.go", "扁平写法")
t("q1-trojan-tls-domain", merged(trojan("node.example.com", stream=st("tcp", "tls"))), "OK", "Q1", "infra/conf/xray.go", "")
t("q1-vmess-none-domain", merged(vmess("node.example.com")), "OK", "Q1", "infra/conf/xray.go", "VMess 不在检查范围")
t("q1-merged-tag-mapping", merged(vless("127.0.0.1"), trojan("node.example.com")), "ERR:with tag out-1 > infra/conf: " + E_TROJAN, "Q1/Q7", "infra/conf/xray.go,main/run.go", "合并配置里报错能对回 out-1；这条不带「failed to build outbound handler」层")

# --- Q1 / VMess 加密方式（d7fa2076）：run -test 都通过，convert pb -debug 看实际类型
for sec, typ in [("none", "AUTO"), ("zero", "AUTO"), ("auto", "AUTO"), ("aes-128-gcm", "AES128_GCM"), ("chacha20-poly1305", "CHACHA20_POLY1305"), ("AES-128-GCM", "AES128_GCM"), ("aes-256-cfb", "AUTO")]:
    slug = sec.lower() + ("-upper" if sec != sec.lower() else "")
    t(f"vmess-sec-{slug}", merged(vmess(security=sec)), "OK", "Q1", "infra/conf/vmess.go", "run -test 不报错")
    t(f"vmess-sec-{slug}-pb", merged(vmess(security=sec)), f'PB:+"type": "{typ}"', "Q1", "infra/conf/vmess.go", f"{sec} → {typ}（静默）", cmd="pb")

# --- 清单 1：Trojan 写法
t("trojan-servers-tls", merged(trojan(stream=st("tcp", "tls"))), "OK", "1", "infra/conf/trojan.go")
t("trojan-flat-tls", merged(trojan(stream=st("tcp", "tls"), flat=True)), "OK", "1", "infra/conf/trojan.go")
two = trojan(stream=st("tcp", "tls"))
two["settings"]["servers"].append({"address": "127.0.0.1", "port": 20004, "password": TPW})
t("trojan-two-servers", merged(two), 'ERR:Trojan settings: "servers" should have one and only one member', "1", "infra/conf/trojan.go")
t("trojan-no-password", merged(trojan(stream=st("tcp", "tls"), pw="")), "ERR:Trojan password is not specified.", "1", "infra/conf/trojan.go")
t("trojan-flow", merged(trojan(stream=st("tcp", "tls"), extra={"flow": "xtls-rprx-vision"})), "ERR:The feature Flow for Trojan has been removed.", "1", "infra/conf/trojan.go")
t("trojan-v6-domain-tls", merged(trojan("2001:db8::30", stream=st("tcp", "tls")), trojan("node.example.org", stream=st("ws", "tls"))), "OK", "1", "infra/conf/trojan.go")

# --- 清单 2 / Q4：传输名映射、REALITY 允许的传输
for n, exp in [("h2", "ERR:The feature HTTP transport (without header padding, etc.) has been removed and migrated to XHTTP stream-one H2 & H3."),
               ("http", "ERR:The feature HTTP transport (without header padding, etc.) has been removed"),
               ("h3", "ERR:The feature HTTP transport (without header padding, etc.) has been removed"),
               ("quic", "ERR:The feature QUIC transport (without web service, etc.) has been removed and migrated to XHTTP stream-one H3."),
               ("foo", "ERR:Config: unknown transport protocol: foo")]:
    t(f"net-{n}", merged(vless(stream={"network": n, "security": "tls", "tlsSettings": {"serverName": "node.example.com"}})), exp, "2/Q4", "infra/conf/transport_internet.go")
names = [{"network": x} for x in ("raw", "tcp", "RAW", "xhttp", "splithttp", "grpc", "ws", "websocket", "httpupgrade")]
t("net-names-ok", merged(*[vless(stream=dict(n, security="tls", tlsSettings={"serverName": "node.example.com"})) for n in names]), "OK", "Q4", "infra/conf/transport_internet.go", "传输名映射（含大小写）")
t("net-method-alias", merged(vless(stream={"method": "ws", "wsSettings": {"path": "/m"}, "security": "tls", "tlsSettings": {"serverName": "node.example.com"}})), "OK", "Q4", "infra/conf/transport_internet.go", "fb548f54 新增的 method 别名")
t("net-method-alias-pb", merged(vless(stream={"method": "ws", "wsSettings": {"path": "/m"}, "security": "tls", "tlsSettings": {"serverName": "node.example.com"}})), 'PB:+"protocolName": "websocket"', "Q4", "infra/conf/transport_internet.go", "method 生效", cmd="pb")
for n, exp in [("tcp", "OK"), ("tcphttp", "OK"), ("grpc", "OK"), ("xhttp", "OK"),
               ("ws", "ERR:REALITY only supports RAW, XHTTP and gRPC for now."),
               ("httpupgrade", "ERR:REALITY only supports RAW, XHTTP and gRPC for now.")]:
    t(f"reality-net-{n}", merged(vless(stream=st(n, "reality"))), exp, "2/Q2", "infra/conf/transport_internet.go")
t("reality-protocols", merged(vmess(stream=st("tcp", "reality")), trojan(stream=st("tcp", "reality")), vless(stream=st("tcp", "reality"), flow="xtls-rprx-vision")), "OK", "2/Q2", "infra/conf/transport_internet.go")
t("reality-empty-settings", merged(vless(stream={"network": "tcp", "security": "reality"})), 'ERR:REALITY: Empty "realitySettings".', "Q2", "infra/conf/transport_internet.go")

# --- 清单 3 / Q2：realitySettings 客户端字段
t("reality-min-pbk-only", merged(vless(stream={"network": "tcp", "security": "reality", "realitySettings": {"publicKey": PBK_FAKE}})), "OK", "3", "infra/conf/transport_security.go")
t("reality-password-alias", merged(vless(stream={"network": "tcp", "security": "reality", "realitySettings": {"serverName": "www.example.org", "password": PBK_FAKE}})), "OK", "3", "infra/conf/transport_security.go")
t("reality-no-pbk", merged(vless(stream=st("tcp", "reality", publicKey=None))), 'ERR:empty "password"', "3", "infra/conf/transport_security.go")
t("reality-bad-pbk", merged(vless(stream=st("tcp", "reality", publicKey="not-a-valid-key"))), 'ERR:invalid "password": not-a-valid-key', "3", "infra/conf/transport_security.go")
t("reality-sid-odd", merged(vless(stream=st("tcp", "reality", shortId="abc"))), 'ERR:invalid "shortId": abc', "3", "infra/conf/transport_security.go")
t("reality-sid-17", merged(vless(stream=st("tcp", "reality", shortId="0123456789abcdef0"))), 'ERR:too long "shortId": 0123456789abcdef0', "3", "infra/conf/transport_security.go")
t("reality-sid-nonhex", merged(vless(stream=st("tcp", "reality", shortId="zz"))), 'ERR:invalid "shortId": zz', "3", "infra/conf/transport_security.go")
t("reality-sids-array", merged(vless(stream=st("tcp", "reality", shortIds=["0123abcd"]))), 'ERR:non-empty "shortIds", please use "shortId" instead', "3", "infra/conf/transport_security.go")
t("reality-servernames-array", merged(vless(stream=st("tcp", "reality", serverNames=["www.example.org"]))), 'ERR:non-empty "serverNames", please use "serverName" instead', "3", "infra/conf/transport_security.go")
t("reality-fp-unsafe", merged(vless(stream=st("tcp", "reality", fingerprint="unsafe"))), 'ERR:invalid "fingerprint": unsafe', "3", "infra/conf/transport_security.go")
t("reality-fp-hellogolang", merged(vless(stream=st("tcp", "reality", fingerprint="HelloGolang"))), 'ERR:invalid "fingerprint": hellogolang', "3", "infra/conf/transport_security.go", "先转小写再比")
t("reality-fp-netscape", merged(vless(stream=st("tcp", "reality", fingerprint="netscape"))), 'ERR:unknown "fingerprint": netscape', "3", "infra/conf/transport_security.go")
t("reality-fp-empty", merged(vless(stream=st("tcp", "reality", fingerprint=None))), "OK", "3", "infra/conf/transport_security.go,transport/internet/tls/tls.go", "空＝chrome")
t("reality-fp-all-allowed", merged(*[vless(stream=st("tcp", "reality", fingerprint=f)) for f in FP_ALL if f not in ("unsafe", "hellogolang")]), "OK", "3/7", "infra/conf/transport_security.go,transport/internet/tls/tls.go", "61 个名字 run -test 全接受（运行时能否连新服务端另见 L2-R*）")
t("reality-mldsa-ok", merged(vless(stream=st("tcp", "reality", mldsa65Verify=MLDSA_FAKE))), "OK", "3", "infra/conf/transport_security.go")
t("reality-mldsa-short", merged(vless(stream=st("tcp", "reality", mldsa65Verify=MLDSA_FAKE[:-10]))), 'ERR:invalid "mldsa65Verify": ', "3", "infra/conf/transport_security.go")
t("reality-spiderx-ok", merged(vless(stream=st("tcp", "reality", spiderX="/path?p=1-3&c=2"))), "OK", "3", "infra/conf/transport_security.go")
t("reality-spiderx-bad", merged(vless(stream=st("tcp", "reality", spiderX="path"))), 'ERR:invalid "spiderX": path', "3", "infra/conf/transport_security.go")

# --- 清单 4–7 / Q3：TLS
t("tls-allowinsecure-true", merged(vless(stream=st("tcp", "tls", allowInsecure=True))), 'ERR:The feature "allowInsecure" has been removed and migrated to "pinnedPeerCertSha256"(pcs) and "verifyPeerCertByName"(vcn).', "4/Q3", "infra/conf/transport_security.go", "不再按设备时钟")
t("tls-allowinsecure-false", merged(vless(stream=st("tcp", "tls", allowInsecure=False))), "OK", "4", "infra/conf/transport_security.go")
t("tls-pin-plus-allowinsecure", merged(vless(stream=st("tcp", "tls", allowInsecure=True, pinnedPeerCertSha256=LEAF_PIN))), 'ERR:The feature "allowInsecure" has been removed', "4", "infra/conf/transport_security.go")
t("tls-pin-formats-ok", merged(vless(stream=st("tcp", "tls", pinnedPeerCertSha256=LEAF_PIN)), vless(stream=st("tcp", "tls", pinnedPeerCertSha256=PIN_COLON)),
                                vless(stream=st("tcp", "tls", pinnedPeerCertSha256=LEAF_PIN + " , " + CA_PIN))), "OK", "5/Q3", "infra/conf/transport_security.go")
t("tls-pin-base64", merged(vless(stream=st("tcp", "tls", pinnedPeerCertSha256=PIN_B64))), "ERR:encoding/hex: invalid byte", "5", "infra/conf/transport_security.go")
t("tls-pin-short", merged(vless(stream=st("tcp", "tls", pinnedPeerCertSha256=LEAF_PIN[:62]))), "ERR:incorrect pinnedPeerCertSha256 length: ", "5", "infra/conf/transport_security.go")
t("tls-pin-array", merged(vless(stream=st("tcp", "tls", pinnedPeerCertSha256=[LEAF_PIN]))), "ERR:cannot unmarshal array", "5", "infra/conf/transport_security.go")
t("tls-pin-old-fields", merged(vless(stream=st("tcp", "tls", pinnedPeerCertificateChainSha256=[PIN_B64], pinnedPeerCertificatePublicKeySha256=[PIN_B64]))), "OK", "5", "transport/internet/tls/config.proto", "旧字段仍静默忽略")
t("tls-verifypeercertinnames", merged(vless(stream=st("tcp", "tls", verifyPeerCertInNames=["node.example.com"]))), "OK", "5/Q6", "transport/internet/tls/config.proto", "变：v26.6.22 起字段删掉，从报错变成静默忽略")
t("tls-verifypeercertbyname", merged(vless(stream=st("tcp", "tls", verifyPeerCertByName="node.example.com,alt.example.com"))), "OK", "5", "infra/conf/transport_security.go")
t("tls-ca-badpem", merged(vless(stream=st("tcp", "tls", certificates=[{"usage": "verify", "certificate": FAKE_PEM}]))), "OK", "6", "transport/internet/tls/config_other.go", "run -test 不解析 PEM")
t("tls-ca-usage-unknown", merged(vless(stream=st("tcp", "tls", certificates=[{"usage": "nope", "certificate": CA}]))), "OK", "6", "infra/conf/transport_security.go")
t("tls-ca-disablesystemroot", merged(vless(stream=st("tcp", "tls", certificates=[{"usage": "verify", "certificate": CA}], disableSystemRoot=True))), "OK", "6", "transport/internet/tls/config_other.go")
t("tls-fp-all", merged(*[vless(stream=st("tcp", "tls", fingerprint=f)) for f in FP_ALL] + [vless(stream=st("tcp", "tls", fingerprint="Chrome")), vless(stream=st("tcp", "tls"))]), "OK", "7/Q3", "infra/conf/transport_security.go,transport/internet/tls/tls.go", "63 个名字 + 大写 + 不写")
t("tls-fp-new3", merged(*[vless(stream=st("tcp", "tls", fingerprint=f)) for f in ("hellochrome_133", "hellofirefox_148", "hellosafari_26_3")]), "OK", "7", "transport/internet/tls/tls.go", "比 v26.3.27 新增的 3 个")
t("tls-fp-netscape", merged(vless(stream=st("tcp", "tls", fingerprint="netscape"))), 'ERR:unknown "fingerprint": netscape', "7", "infra/conf/transport_security.go")
t("tls-alpn-forms", merged(vless(stream=st("tcp", "tls", alpn=["h2", "http/1.1"])), vless(stream=st("tcp", "tls", alpn="http/1.1")), vless(stream=st("tcp", "tls", alpn=["h3", "foo"]))), "OK", "7", "infra/conf/transport_security.go")
for slug, val in [("b64", ECH_B64), ("garbage", "!!!not-base64!!!"), ("doh", "https://dns.example.net/dns-query"),
                  ("domain-doh", "node.example.com+https://dns.example.net/dns-query"), ("udp", "udp://192.0.2.53"), ("h2c", "h2c://dns.example.net/dns-query")]:
    t(f"tls-ech-{slug}", merged(vless(stream=st("tcp", "tls", echConfigList=val))), "OK", "8/Q3", "infra/conf/transport_security.go,transport/internet/tls/ech.go", "run -test 不检查内容")
t("tls-ech-forcequery-bogus", merged(vless(stream=st("tcp", "tls", echConfigList=ECH_B64, echForceQuery="always"))), "OK", "8/Q3", "transport/internet/tls/config.proto", "变：v26.6.22 起 echForceQuery 字段删掉，旧版这里报 invalid \"echForceQuery\"")
t("tls-ech-sockopt", merged(vless(stream=st("tcp", "tls", echConfigList="https://dns.example.net/dns-query", echSockopt={"mark": 0}))), "OK", "8", "infra/conf/transport_security.go")
t("tls-ech-pb", merged(vless(stream=st("tcp", "tls", echConfigList=ECH_B64, echForceQuery="none"))), "PB:-echForceQuery && PB:+echConfigList", "8", "transport/internet/tls/config.proto", "pb 里只剩 echConfigList", cmd="pb")

# --- Q4：ws / httpupgrade / tcp 头 / grpc 的键
t("ws-early-data-path", merged(vless(stream={"network": "ws", "wsSettings": {"path": "/ws?ed=2048", "host": "cdn.example.net"}, "security": "tls", "tlsSettings": {"serverName": "node.example.com"}})), "OK", "Q4", "infra/conf/transport_method.go")
t("ws-early-data-pb", merged(vless(stream={"network": "ws", "wsSettings": {"path": "/ws?ed=2048"}, "security": "tls", "tlsSettings": {"serverName": "node.example.com"}})), 'PB:+"ed": 2048 && PB:+"path": "/ws"', "Q4", "infra/conf/transport_method.go", "?ed= 从路径里取出", cmd="pb")
t("ws-ignored-ed-keys", merged(vless(stream={"network": "ws", "wsSettings": {"path": "/ws", "maxEarlyData": 2048, "earlyDataHeaderName": "Sec-WebSocket-Protocol"}})), "OK", "Q4/Q6", "infra/conf/transport_method.go", "sing-box 风格的键被静默忽略")
t("ws-headers-host-pb", merged(vless(stream={"network": "ws", "wsSettings": {"path": "/ws", "headers": {"Host": "cdn.example.net"}}})), 'PB:+"host": "cdn.example.net"', "Q4/Q6", "infra/conf/transport_method.go", "headers.Host 迁到 host（伴随弃用警告）", cmd="pb")
t("hu-headers-host", merged(vless(stream={"network": "httpupgrade", "httpupgradeSettings": {"path": "/up", "headers": {"Host": "cdn.example.net"}}})), 'ERR:"headers" can\'t contain "host"', "Q4", "transport/internet/httpupgrade/dialer.go")
t("hu-camelcase-key", merged(vless(stream={"network": "httpupgrade", "httpUpgradeSettings": {"path": "/up", "headers": {"Host": "cdn.example.net"}}})), 'ERR:"headers" can\'t contain "host"', "11/Q6", "infra/conf/serial/loader.go", "推断：Go encoding/json 键名大小写不敏感，驼峰键也会被解析；若 OK 则说明被忽略")
t("hu-camelcase-pb", merged(vless(stream={"network": "httpupgrade", "httpUpgradeSettings": {"path": "/camel", "host": "cdn.example.net"}})), 'PB:+"path": "/camel"', "11/Q6", "infra/conf/serial/loader.go", "同上，用 pb 看 path 是否进了配置", cmd="pb")
t("tcp-http-header", merged(vless(stream=st("tcphttp"))), "OK", "Q4", "infra/conf/transport_method.go")
t("tcp-http-header-pb", merged(vless(stream=st("tcphttp"))), 'PB:+"a.example.com" && PB:+"Host"', "Q4", "infra/conf/transport_method.go", cmd="pb")
t("tcp-header-bogus", merged(vless(stream={"network": "tcp", "tcpSettings": {"header": {"type": "bogus"}}})), "ERR:invalid TCP header config", "Q4", "infra/conf/transport_method.go")
t("grpc-servicename", merged(vless(stream=st("grpc", "tls"))), "OK", "Q4", "infra/conf/transport_internet.go")

# --- 清单 9 / Q5：mux
mux8 = {"enabled": True, "concurrency": 8}
t("mux-basic", merged(vless(mux=mux8), vmess(mux=mux8), trojan(stream=st("tcp", "tls"), mux=mux8)), "OK", "9/Q5", "infra/conf/xray.go")
for v in ("reject", "allow", "skip"):
    t(f"mux-udp443-{v}", merged(vless(mux={"enabled": True, "concurrency": -1, "xudpConcurrency": 16, "xudpProxyUDP443": v})), "OK", "9/Q5", "infra/conf/xray.go")
t("mux-udp443-unset-pb", merged(vless(mux=mux8)), 'PB:+"xudpProxyUDP443": "reject"', "9/Q5", "infra/conf/xray.go", "不写＝reject", cmd="pb")
t("mux-udp443-bogus", merged(vless(mux={"enabled": True, "xudpProxyUDP443": "allowed"})), 'ERR:unknown "xudpProxyUDP443": allowed', "9", "infra/conf/xray.go")
t("mux-conc-big", merged(vless(mux={"enabled": True, "concurrency": 40000})), "ERR:cannot unmarshal number 40000", "9", "infra/conf/xray.go")
t("mux-odd-values", merged(vless(mux={"enabled": True, "concurrency": -2}), vless(mux={"enabled": True, "xudpConcurrency": 1025}), vless(mux={"enabled": True, "protocol": "smux", "padding": True})), "OK", "9/Q6", "infra/conf/xray.go", "没有范围检查；protocol / padding 被忽略")
t("mux-vision", merged(vless(stream=st("tcp", "tls"), flow="xtls-rprx-vision", mux=mux8), vless(stream=st("tcp", "tls"), flow="xtls-rprx-vision-udp443", mux={"enabled": True, "concurrency": -1, "xudpConcurrency": 16})), "OK", "10/Q5", "proxy/vless/outbound/outbound.go", "vision＋mux 配置期不拒绝")
t("vless-flow-bogus", merged(vless(stream=st("tcp", "tls"), flow="xtls-rprx-direct")), 'ERR:VLESS users: "flow" doesn\'t support "xtls-rprx-direct" in this version', "Q5", "infra/conf/vless.go")

# --- Q6：生成器会写的每个键（与 XrayConfig.kt 同形）＋ 未知键
full = merged(
    vmess(stream={"network": "ws", "wsSettings": {"path": "/ws?ed=2048", "headers": {"Host": "cdn.example.net"}}, "security": "tls",
                  "tlsSettings": {"serverName": "node.example.com", "alpn": ["h2", "http/1.1"], "pinnedPeerCertSha256": LEAF_PIN, "fingerprint": "chrome",
                                  "certificates": [{"usage": "verify", "certificate": CA}], "echConfigList": ECH_B64}},
          mux={"enabled": True, "concurrency": 8, "xudpConcurrency": 16, "xudpProxyUDP443": "allow"}),
    vless(stream={"network": "tcp", "security": "reality", "realitySettings": {"serverName": "www.example.org", "publicKey": PBK_FAKE, "shortId": SID,
                                                                                  "mldsa65Verify": MLDSA_FAKE, "fingerprint": "chrome"}}, flow="xtls-rprx-vision"),
    trojan(stream={"network": "grpc", "grpcSettings": {"serviceName": "svc"}, "security": "tls", "tlsSettings": {"serverName": "node.example.com", "fingerprint": "firefox"}},
           mux={"enabled": True, "concurrency": 8}),
    vless(stream={"network": "httpupgrade", "httpupgradeSettings": {"host": "cdn.example.net", "path": "/up"}}),
    vmess(stream=st("tcphttp"), security="aes-128-gcm", mux={"enabled": True, "concurrency": -1, "xudpConcurrency": 16, "xudpProxyUDP443": "allow"}),
)
t("gen-all-keys", full, "OK", "11/Q6", "infra/conf/transport_security.go,infra/conf/xray.go,infra/conf/socks.go,infra/conf/log.go", "生成器写的全部键")
t("gen-all-keys-pb", full, 'PB:+"pinnedPeerCertSha256" && PB:+"echConfigList" && PB:+"mldsa65Verify" && PB:+"xudpProxyUDP443": "allow" && PB:+"serviceName": "svc" && PB:+"authType": "PASSWORD" && PB:+"udpEnabled": true', "11/Q6", "infra/conf/serial/loader.go", "确认各键都进了 pb（不是被忽略）", cmd="pb")
unk = merged(vless(stream=st("ws", "tls")))
unk["fooTop"] = {"x": 1}
unk["inbounds"][0]["fooIn"] = 1
unk["inbounds"][0]["settings"]["fooSettings"] = True
unk["outbounds"][1]["fooOut"] = [1, 2]
unk["outbounds"][1]["settings"]["vnext"][0]["users"][0]["fooUser"] = "x"
unk["outbounds"][1]["streamSettings"]["fooStream"] = "x"
unk["outbounds"][1]["streamSettings"]["tlsSettings"]["fooTls"] = 1
unk["outbounds"][1]["streamSettings"]["wsSettings"]["fooWs"] = 1
unk["routing"]["rules"][0]["fooRule"] = 1
t("unknown-keys-everywhere", unk, "OK", "11/Q6", "infra/conf/serial/loader.go", "未知键仍静默忽略")
t("unknown-keys-strict-env", unk, "OK", "11/Q6", "infra/conf/serial/loader.go", "XRAY_JSON_STRICT=true 也不拒绝未知键", cmd="test+env:XRAY_JSON_STRICT=true")
t("vless-flat-mixed-pb", merged(vless(flat=True)), 'PB:+"address": "127.0.0.1"', "Q6", "infra/conf/vless.go", "扁平写法仍可用", cmd="pb")

# --- 清单 12 / Q7：报错格式与退出码
dup = merged(vless(), vless())
dup["outbounds"][2]["tag"] = "out-0"
t("dup-outbound-tag", dup, "ERR:app/proxyman/outbound: existing tag found: out-0", "12/Q7", "main/run.go")
dupi = merged(vless(), vless())
dupi["inbounds"][1]["tag"] = "in-0"
t("dup-inbound-tag", dupi, "ERR:app/proxyman/inbound: existing tag found: in-0", "12/Q7", "main/run.go")
t("nested-error-layers", merged(vless(), vless(stream=st("tcp", "reality", publicKey="not-a-valid-key"))), 'ERR:failed to build outbound config with tag out-1 > infra/conf: failed to build stream settings for outbound detour > infra/conf: Failed to build REALITY config. > infra/conf: invalid "password": not-a-valid-key', "12/Q7", "main/run.go")
t("anytls-outbound", merged({"protocol": "anytls", "settings": {}}), "ERR:unknown config id: anytls", "12", "main/run.go")
t("warnings-only", merged(vmess(stream=st("grpc", "tls")), trojan(stream=st("ws", "tls"))), "OK", "12/Q7", "main/run.go", "只有弃用警告，退出码 0")

# --- Q8：socks 入站
def socks_only(settings):
    c = merged(vless())
    c["inbounds"][0]["settings"] = settings
    return c


t("socks-accounts", socks_only({"auth": "password", "accounts": [{"user": U, "pass": P}], "udp": True}), "OK", "Q8", "infra/conf/socks.go")
t("socks-accounts-pb", socks_only({"auth": "password", "accounts": [{"user": U, "pass": P}], "udp": True}), f'PB:+"authType": "PASSWORD" && PB:+"{U}": "{P}" && PB:+"udpEnabled": true', "Q8", "infra/conf/socks.go", "accounts 仍被接受（并入 users）", cmd="pb")
t("socks-users-pb", socks_only({"auth": "password", "users": [{"user": U, "pass": P}], "udp": True}), f'PB:+"authType": "PASSWORD" && PB:+"{U}": "{P}"', "Q8", "infra/conf/socks.go", "新的正式键名 users", cmd="pb")
t("socks-auth-typo", socks_only({"auth": "Password", "accounts": [{"user": U, "pass": P}], "udp": True}), "OK", "Q8", "infra/conf/socks.go", "拼错仍 run -test 通过")
t("socks-auth-typo-pb", socks_only({"auth": "Password", "accounts": [{"user": U, "pass": P}], "udp": True}), 'PB:-"authType"', "Q8", "infra/conf/socks.go", "拼错 → NO_AUTH（零值不输出）", cmd="pb")
t("log-access-none-pb", merged(vless()), 'PB:-"accessLogType" && PB:+"errorLogLevel": "Warning"', "Q8", "infra/conf/log.go", "access: none → None（零值不输出）", cmd="pb")
t("log-access-default-pb", merged(vless(), log={"loglevel": "warning"}), 'PB:+"accessLogType": "Console"', "Q8", "infra/conf/log.go", "对照：不写 access 时是 Console", cmd="pb")

# --- v26.10.31 的明文判定（N13）：追加在末尾，前面的编号不变，便于与旧版结果逐条对比
t("q1-vless-none-rfc1918", merged(vless("192.168.1.10")), "OK", "Q1", "N13", "IsPrivate")
t("q1-vless-none-linklocal", merged(vless("169.254.1.10")), "OK", "Q1", "N13", "IsLinkLocalUnicast")
t("q1-vless-none-cgnat", merged(vless("100.64.0.10")), "ERR:" + E_VLESS, "Q1", "N13", "100.64.0.0/10 不是 IsPrivate，算公网（v26.10.21 在旧名单里）")
t("q1-vless-none-v6-ula", merged(vless("fd00::10")), "OK", "Q1", "N13", "fc00::/7 是 IsPrivate")

# ---------------- 回环组
L = []  # (slug, config, kind, args, expect, item, src, note)


SRV_NETS = {
    "tcp": {"network": "tcp"},
    "grpc": {"network": "grpc", "grpcSettings": {"serviceName": "svc"}},
}
# 被测 Xray 的版本：REALITY 客户端自报的版本、minClientVer / maxClientVer 的相对阈值都由它推出。改这里后仍要逐条复核预期。
XRAY_VER = (26, 10, 31)
CV_LOG = "[%d %d %d" % XRAY_VER                                     # 服务端 show 日志里 hs.c.ClientVer 的开头
CV_STR = "%d.%d.%d" % XRAY_VER
CV_ABOVE = "%d.%d.%d" % (XRAY_VER[0], XRAY_VER[1], XRAY_VER[2] + 1)  # 比客户端新的最小版本
CV_BELOW = "%d.%d.%d" % (XRAY_VER[0], XRAY_VER[1], XRAY_VER[2] - 1)  # 比客户端旧的最大版本


def lb(proto="trojan", net="tcp", sec="tls", chain=LEAF, client_tls=None, client_reality=None, server_reality=None,
       mux=None, vmess_sec="auto", loglevel="info", target_chain=FULL, target_key=LEAF_KEY, udp_client=False,
       server_freedom_redirect=None):
    """服务端入站（in-s :32000）+ 客户端出站（out-c）同进程；客户端入口是 dokodemo（TCP :31000 → 127.0.0.1:40000）或 socks（:31080，带凭据、开 UDP）。"""
    inb = [entry_inbound(udp_client)]
    if proto == "trojan":
        srv_settings = {"users": [{"password": TPW}]}
        cli = trojan("127.0.0.1", 32000)
    elif proto == "vmess":
        srv_settings = {"users": [{"id": UUID}]}
        cli = vmess("127.0.0.1", 32000, security=vmess_sec)
    else:
        srv_settings = {"users": [{"id": UUID}], "decryption": "none"}
        cli = vless("127.0.0.1", 32000)
    srv_stream = copy.deepcopy(SRV_NETS[net])
    cli_stream = copy.deepcopy(SRV_NETS[net])
    if sec == "tls":
        srv_stream.update(tls_server(chain))
        cli_stream["security"] = "tls"
        cli_stream["tlsSettings"] = client_tls or {"serverName": "node.example.com", "pinnedPeerCertSha256": LEAF_PIN}
    elif sec == "reality":
        rs = {"target": "127.0.0.1:32100", "serverNames": ["node.example.com"], "privateKey": PRIV, "shortIds": [SID], "show": True}
        rs.update(server_reality or {})
        srv_stream.update({"security": "reality", "realitySettings": rs})
        rc = {"serverName": "node.example.com", "publicKey": PUB, "shortId": SID}
        rc.update(client_reality or {})
        cli_stream.update({"security": "reality", "realitySettings": {k: v for k, v in rc.items() if v is not None}})
        inb.append(reality_target(target_chain, target_key))
    inb.append({"tag": "in-s", "listen": "127.0.0.1", "port": 32000, "protocol": proto, "settings": srv_settings, "streamSettings": srv_stream})
    cli["tag"] = "out-c"
    cli["streamSettings"] = cli_stream
    if mux:
        cli["mux"] = mux
    fr = dict(ALLOW_LOOP)
    if server_freedom_redirect:
        fr["redirect"] = server_freedom_redirect
    return {"log": {"loglevel": loglevel, "access": "none"}, "inbounds": inb,
            "outbounds": [{"tag": "block", "protocol": "blackhole"}, cli, {"tag": "direct", "protocol": "freedom", "settings": fr}],
            "routing": {"rules": [{"type": "field", "inboundTag": ["in-c"], "outboundTag": "out-c"},
                                  {"type": "field", "inboundTag": ["in-s", "in-target"], "outboundTag": "direct"}]}}


def l(slug, cfg, kind, expect, item, src, note="", args=""):
    L.append((slug, cfg, kind, args, expect, item, src, note))


# 期望子句的写法见 run-lb.sh 文件头
# --- REALITY：新客户端 × 新服务端（缺省 minClientVer、新 reality 库）
l("reality-chrome", lb(sec="reality"), "tcp", "PASS && log:+hs.c.ClientVer: " + CV_LOG, "3/Q2", "transport/internet/reality/reality.go,infra/conf/transport_security.go,xtls/reality/tls.go", "新客户端自报 " + CV_STR + "；服务端缺省不设 minClientVer")
for fp, ok in [("firefox", True), ("safari", True), ("hellochrome_131", True), ("hellochrome_133", True), ("hellofirefox_148", True), ("hellosafari_26_3", True),
               ("ios", False), ("edge", False), ("qq", False), ("hellochrome_120", False), ("hellofirefox_120", False), ("helloios_14", False), ("hello360_11_0", False)]:
    exp = "PASS && log:+hs.c.ClientVer: " + CV_LOG if ok else "FAIL && log:-hs.c.ClientVer && log:+forwarded SNI && log:+processed invalid connection"
    l(f"reality-fp-{fp}", lb(sec="reality", client_reality={"fingerprint": fp}), "tcp", exp, "7/Q2", "xtls/reality/tls.go,utls/u_parrots.go",
      "uTLS 模板带 X25519MLKEM768 且排在 X25519 前才放行" if ok else "模板没有 X25519MLKEM768（或排在后面）：新服务端当陌生人转发给 target，客户端报 x509 错误")
for fp in ("android", "360"):
    l(f"reality-fp-{fp}", lb(sec="reality", client_reality={"fingerprint": fp}), "tcp", "FAIL && log:+does not support TLS 1.3", "7/Q2", "transport/internet/reality/reality.go,utls/u_parrots.go", "模板只有 TLS 1.2，客户端自己就建不起 REALITY")
for fp in ("random", "randomized"):
    l(f"reality-fp-{fp}", lb(sec="reality", client_reality={"fingerprint": fp}), "tcp", "ANY", "7/Q2", "transport/internet/tls/tls.go,utls/u_parrots.go", "每次进程启动随机取模板：结果不确定，跑 5 次记成功次数")
l("reality-minver-above", lb(sec="reality", server_reality={"minClientVer": CV_ABOVE}), "tcp", "FAIL && log:+hs.c.ClientVer: " + CV_LOG + " && log:+hs.c.conn == conn: false", "3/Q2", "infra/conf/transport_security.go", "服务端 minClientVer 高于客户端")
l("reality-minver-equal", lb(sec="reality", server_reality={"minClientVer": CV_STR}), "tcp", "PASS", "3/Q2", "infra/conf/transport_security.go", ">= 比较")
l("reality-maxver-below", lb(sec="reality", server_reality={"maxClientVer": CV_BELOW}), "tcp", "FAIL && log:+hs.c.conn == conn: false", "3/Q2", "infra/conf/transport_security.go", "")
l("reality-oldmin-26327", lb(sec="reality", server_reality={"minClientVer": "26.3.27"}), "tcp", "PASS", "3/Q2", "infra/conf/transport_security.go", "v26.7.11–v26.7.28 服务端的缺省值")
l("reality-mldsa-bigtarget", lb(sec="reality", client_reality={"mldsa65Verify": VERIFY}, server_reality={"mldsa65Seed": SEED}, target_chain=BFULL, target_key=BLEAF_KEY),
  "tcp", "PASS", "3/Q2", "transport/internet/reality/reality.go", "REALITY 双方启用 ML-DSA")
l("reality-mldsa-client-only", lb(sec="reality", client_reality={"mldsa65Verify": VERIFY}, target_chain=BFULL, target_key=BLEAF_KEY), "tcp", "FAIL", "3", "transport/internet/reality/reality.go", "只有 REALITY 客户端要求 ML-DSA 校验")
l("reality-vmess", lb(proto="vmess", sec="reality"), "tcp", "PASS", "2", "infra/conf/transport_internet.go", "K1 L-X16 复测")
l("reality-grpc", lb(net="grpc", sec="reality"), "tcp", "PASS", "2", "infra/conf/transport_internet.go", "K1 L-X18 复测")
# --- 证书固定与自定义 CA
l("pin-leaf", lb(), "tcp", "PASS", "5", "transport/internet/tls/config.go", "K1 L-X01")
l("pin-leaf-wrongsni", lb(client_tls={"serverName": "wrong.example.net", "pinnedPeerCertSha256": LEAF_PIN}), "tcp", "PASS", "5", "transport/internet/tls/config.go", "命中叶子不查名字（K1 L-X02）")
l("pin-ca-fullchain", lb(chain=FULL, client_tls={"serverName": "node.example.com", "pinnedPeerCertSha256": CA_PIN}), "tcp", "PASS", "5", "transport/internet/tls/config.go", "K1 L-X03")
l("pin-ca-wrongsni", lb(chain=FULL, client_tls={"serverName": "wrong.example.net", "pinnedPeerCertSha256": CA_PIN}), "tcp", "FAIL && log:+peer cert is invalid (against pinned CA and serverName)", "5", "transport/internet/tls/config.go", "K1 L-X04")
l("pin-ca-nosni", lb(chain=FULL, client_tls={"pinnedPeerCertSha256": CA_PIN}), "tcp", "FAIL && log:+against pinned CA and serverName", "5", "transport/internet/tls/config.go", "不写 serverName：WithDestination 填成 127.0.0.1，名字对不上；新加的 Pinning CA needs a valid ServerName 走不到")
l("pin-ca-leafonly", lb(chain=LEAF, client_tls={"serverName": "node.example.com", "pinnedPeerCertSha256": CA_PIN}), "tcp", "FAIL && log:+peer cert is unrecognized (against pinnedPeerCertSha256)", "5", "transport/internet/tls/config.go", "K1 L-X05")
l("pin-random", lb(client_tls={"serverName": "node.example.com", "pinnedPeerCertSha256": PIN_RAND}), "tcp", "FAIL && log:+peer cert is unrecognized (against pinnedPeerCertSha256)", "5", "transport/internet/tls/config.go", "K1 L-X06")
l("ca-custom-ok", lb(client_tls={"serverName": "node.example.com", "certificates": [{"usage": "verify", "certificate": CA}]}), "tcp", "PASS", "6", "transport/internet/tls/config_other.go", "K1 L-X07")
l("ca-custom-wrongsni", lb(client_tls={"serverName": "wrong.example.net", "certificates": [{"usage": "verify", "certificate": CA}]}), "tcp", "FAIL && log:+x509: certificate is valid for node.example.com", "6", "transport/internet/tls/config_other.go", "K1 L-X08")
l("ca-none", lb(client_tls={"serverName": "node.example.com"}), "tcp", "FAIL && log:+x509: ", "6", "transport/internet/tls/config_other.go", "K1 L-X09；Android 上原文应是 x509: certificate signed by unknown authority（macOS 走系统校验器，原文不同）")
l("ca-custom-disablesystemroot", lb(client_tls={"serverName": "node.example.com", "certificates": [{"usage": "verify", "certificate": CA}], "disableSystemRoot": True}), "tcp", "PASS", "6", "transport/internet/tls/config_other.go", "只信自带证书时也能通（追加与替换的区分见 CoreCapabilities.kt 第 6 条）")
l("fp-unsafe-customca", lb(client_tls={"serverName": "node.example.com", "fingerprint": "unsafe", "certificates": [{"usage": "verify", "certificate": CA}]}), "tcp", "PASS", "7", "transport/internet/tcp/dialer.go", "K1 L-X20")
l("ech-garbage-pinleaf", lb(client_tls={"serverName": "node.example.com", "pinnedPeerCertSha256": LEAF_PIN, "echConfigList": "!!!not-base64!!!"}), "tcp", "FAIL && log:+Failed to unmarshal ECHConfigList", "8", "transport/internet/tls/ech.go", "失败即拒绝（K1 L-X10）")
l("ech-garbage-forcequery-none", lb(client_tls={"serverName": "node.example.com", "pinnedPeerCertSha256": LEAF_PIN, "echConfigList": "!!!not-base64!!!", "echForceQuery": "none"}), "tcp", "FAIL", "8", "transport/internet/tls/ech.go,transport/internet/tls/config.proto", "变：echForceQuery 已删，none 不再让它回落成不用 ECH")
# --- 协议与 mux
l("trojan-muxcool", lb(mux={"enabled": True, "concurrency": 8}), "tcp", "PASS && log:+v1.mux.cool", "9/Q5", "app/proxyman/outbound/handler.go", "K1 L-X11")
l("vmess-sec-none", lb(proto="vmess", sec="tls", vmess_sec="none"), "tcp", "PASS", "Q1", "infra/conf/vmess.go", "none 被当 auto，新服务端（已删 NONE）照样能通")
l("vmess-sec-zero", lb(proto="vmess", sec="tls", vmess_sec="zero"), "tcp", "PASS", "Q1", "infra/conf/vmess.go", "")
l("vmess-sec-auto", lb(proto="vmess", sec="tls", vmess_sec="auto"), "tcp", "PASS", "Q1", "infra/conf/vmess.go", "")
l("vless-plain-loopback", lb(proto="vless", sec="none"), "tcp", "PASS", "Q1", "infra/conf/xray.go", "不加密 VLESS 到 127.0.0.1：配置期放行、真能通")
l("trojan-plain-loopback", lb(proto="trojan", sec="none"), "tcp", "PASS", "Q1", "infra/conf/xray.go", "")
no_final_rules = lb(proto="vless", sec="none")
no_final_rules["outbounds"][2]["settings"] = {}
l("freedom-default-blocks-private", no_final_rules, "tcp", "FAIL", "—", "proxy/freedom/freedom.go",
  "只为说明本回环里为什么要给 freedom 加 finalRules：去掉后服务端 freedom 缺省拦 127.0.0.1（有 30–90 秒 blockDelay，run-lb.sh 只等 5 秒）")
# --- UDP：mux 的 UDP/443、cone XUDP（客户端入口改成带凭据的 socks :31080，服务端 freedom 把目标改写到本机回显 :40053）
for v in ("reject", "allow", "skip", None):
    m = {"enabled": True, "concurrency": 8}
    if v:
        m["xudpProxyUDP443"] = v
    exp = {"reject": "UDP:TIMEOUT && log:+XUDP rejected UDP/443 traffic", "allow": "UDP:REPLY && log:+common/mux", "skip": "UDP:REPLY && log:-XUDP rejected", None: "UDP:TIMEOUT && log:+XUDP rejected UDP/443 traffic"}[v]
    l(f"mux-udp443-{v or 'unset'}", lb(proto="vless", sec="tls", mux=m, udp_client=True, loglevel="debug", server_freedom_redirect="127.0.0.1:40053"),
      "udp", exp, "9/Q5", "infra/conf/xray.go,app/proxyman/outbound/handler.go", "只开 mux：UDP/443 先按 xudpProxyUDP443 处理", args="-addr 127.0.0.1:31080 -target 127.0.0.1:443")
l("cone-xudp-5353", lb(proto="vless", sec="tls", udp_client=True, loglevel="debug", server_freedom_redirect="127.0.0.1:40053"), "udp", "UDP:REPLY && log:+v1.mux.cool", "10", "proxy/vless/outbound/outbound.go,proxy/vmess/outbound/outbound.go",
  "不开 mux：53/443 以外的 UDP 走 XUDP（cone）", args=UDP_ARGS)
l("cone-native-53", lb(proto="vless", sec="tls", udp_client=True, loglevel="debug", server_freedom_redirect="127.0.0.1:40053"), "udp", "UDP:REPLY && log:-v1.mux.cool", "10", "proxy/vless/outbound/outbound.go,proxy/vmess/outbound/outbound.go",
  "53 端口不走 XUDP", args="-addr 127.0.0.1:31080 -target 127.0.0.1:53")
l("cone-vmess-5353", lb(proto="vmess", sec="tls", udp_client=True, loglevel="debug", server_freedom_redirect="127.0.0.1:40053"), "udp", "UDP:REPLY && log:+v1.mux.cool", "10", "proxy/vmess/outbound/outbound.go",
  "VMess 同样默认 XUDP", args=UDP_ARGS)

# --- 客户端 ClientHello 实际内容（清单 7 的 alpn、新 REALITY 服务端要的 X25519MLKEM768）：客户端直连 socksprobe tlshello（:32000），只看它打印的 ClientHello


def hello_cfg(stream):
    return {"log": {"loglevel": "info", "access": "none"},
            "inbounds": [entry_inbound()],
            "outbounds": [{"tag": "block", "protocol": "blackhole"}, dict(vless("127.0.0.1", 32000, stream=stream), tag="out-c")],
            "routing": {"rules": [{"type": "field", "inboundTag": ["in-c"], "outboundTag": "out-c"}]}}


def hs_tls(net="tcp", **kw):
    s = {"network": net}
    if net == "ws":
        s["wsSettings"] = {"path": "/ws"}
    t_ = {"serverName": "node.example.com"}
    t_.update(kw)
    s.update({"security": "tls", "tlsSettings": t_})
    return s


for slug, stream, exp, note in [
    ("chrome-noalpn", hs_tls(), "hello:+alpn=[h2 http/1.1] && hello:+X25519MLKEM768", "不写 fingerprint＝chrome（Chrome 133 模板）"),
    ("chrome-alpn-h2", hs_tls(alpn=["h2"]), "hello:+alpn=[h2 http/1.1]", "uTLS 模板的 ALPN 盖过用户写的 alpn"),
    ("chrome-alpn-http11", hs_tls(alpn=["http/1.1"]), "hello:+alpn=[http/1.1]", "只写 http/1.1 时 RAW 走 WebsocketHandshakeContext，按用户的来"),
    ("unsafe-alpn-h2", hs_tls(fingerprint="unsafe", alpn=["h2"]), "hello:+alpn=[h2]", "Go 标准 TLS 按用户 alpn"),
    ("unsafe-noalpn", hs_tls(fingerprint="unsafe"), "hello:+alpn=[h2 http/1.1]", "缺省 h2, http/1.1"),
    ("randomized-alpn-h2", hs_tls(fingerprint="randomized", alpn=["h2"]), "hello:+alpn=[h2]", "变：copyConfig 现在总拷 NextProtos，randomized 系列改用用户 alpn（推断，v26.3.27 是 h2, http/1.1）"),
    ("ws-chrome-noalpn", hs_tls("ws"), "hello:+alpn=[http/1.1]", "ws 外层 ALPN 强制 http/1.1"),
    ("ws-chrome-alpn-h2h1", hs_tls("ws", alpn=["h2", "http/1.1"]), "hello:+alpn=[h2 http/1.1]", "ws 写 h2,http/1.1 时允许作伪装（v26.5.3 起）"),
    ("firefox-curves", hs_tls(fingerprint="firefox"), "hello:+X25519MLKEM768", "Firefox 148 模板"),
    ("ios-curves", hs_tls(fingerprint="ios"), "hello:-X25519MLKEM768", "iOS 14 模板没有 X25519MLKEM768"),
    ("reality-chrome", {"network": "tcp", "security": "reality", "realitySettings": {"serverName": "node.example.com", "publicKey": PUB, "shortId": SID}}, "hello:+X25519MLKEM768", "REALITY 客户端的 ClientHello"),
    ("reality-edge", {"network": "tcp", "security": "reality", "realitySettings": {"serverName": "node.example.com", "publicKey": PUB, "shortId": SID, "fingerprint": "edge"}}, "hello:-X25519MLKEM768", "Edge 85 模板：新 REALITY 服务端会拒"),
]:
    l(f"hello-{slug}", hello_cfg(stream), "hello", exp, "7/Q2/Q3", "transport/internet/tls/tls.go,utls/u_parrots.go", note)

# --- socks 入站本身（Q8）：入站 → freedom → 本机 UDP 回显 / TCP 目标
socks_cfg = {"log": {"loglevel": "debug", "access": "none"},
             "inbounds": [{"tag": "in-0", "listen": "127.0.0.1", "port": 21000, "protocol": "socks",
                           "settings": {"auth": "password", "accounts": [{"user": U, "pass": P}], "udp": True}},
                          {"tag": "in-1", "listen": "127.0.0.1", "port": 21001, "protocol": "socks",
                           "settings": {"auth": "Password", "accounts": [{"user": U, "pass": P}], "udp": True}}],
             "outbounds": [{"tag": "block", "protocol": "blackhole"}, {"tag": "out-0", "protocol": "freedom"}],
             "routing": {"domainStrategy": "AsIs", "rules": [{"type": "field", "inboundTag": ["in-0", "in-1"], "outboundTag": "out-0"}]}}
A = "-addr 127.0.0.1:21000"
l("socks-hs-nocred", socks_cfg, "hs", "HS=05ff", "Q8", "proxy/socks/protocol.go", "K0b：只报无认证 → 05 FF", args=A + " -mode nocred")
l("socks-hs-wrong", socks_cfg, "hs", "HS=050201ff", "Q8", "proxy/socks/protocol.go", "K0b：错密码 → 05 02 + 01 FF", args=A + " -mode wrong")
l("socks-hs-ok", socks_cfg, "hs", "HS=05020100", "Q8", "proxy/socks/protocol.go", "K0b：对的凭据 → 05 02 + 01 00", args=A + " -mode ok")
l("socks-hs-authtypo", socks_cfg, "hs", "HS=0500", "Q8", "infra/conf/socks.go", "auth 拼成 Password：静默不认证", args="-addr 127.0.0.1:21001 -mode nocred")
l("socks-udp-assoc-any", socks_cfg, "udp", "UDP:REPLY", "Q8", "proxy/socks/protocol.go", "ASSOCIATE 报 0.0.0.0:0（sing-box 对公网 IPv4 目标的写法）", args=A + " -assoc 0.0.0.0:0")
l("socks-udp-assoc-v6any", socks_cfg, "udp", "UDP:REPLY", "Q8", "proxy/socks/protocol.go,sing/protocol/socks/client.go", "报 [::]:0（sing-box 对域名、回环目标的写法）", args=A + " -assoc [::]:0")
l("socks-udp-assoc-v6loop", socks_cfg, "udp", "UDP:TIMEOUT", "Q8", "proxy/socks/protocol.go,sing/protocol/socks/client.go",
  "报 [::1]:0（sing-box 对私有 IPv4 目标的写法）：期望的来源 IP 成了 ::1，127.0.0.1 发来的包被丢 → 推断 sing-box 经 Xray 跳到私有 IPv4 的 UDP 不通", args=A + " -assoc [::1]:0")
l("socks-udp-assoc-v4loop", socks_cfg, "udp", "UDP:REPLY", "Q8", "proxy/socks/protocol.go,sing/protocol/socks/client.go", "报 127.0.0.1:0（sing-box 对私有 IPv6 目标的写法）", args=A + " -assoc 127.0.0.1:0")
l("socks-udp-assoc-fixedport", socks_cfg, "udp", "UDP:REPLY", "Q8", "proxy/socks/protocol.go", "报出自己的源端口", args=A + " -assoc 127.0.0.1:41001 -from 41001")
l("socks-udp-assoc-wrongport", socks_cfg, "udp", "UDP:TIMEOUT", "Q8", "proxy/socks/protocol.go", "报的端口与实际源端口不同", args=A + " -assoc 127.0.0.1:41002 -from 41003")
l("socks-udp-assoc-domain", socks_cfg, "udp", "UDP:REPLY", "Q8", "proxy/socks/protocol.go", "报域名（ATYP 3）→ 用 TCP 对端 IP", args=A + " -assoc localhost:0")
l("socks-udp-second-sender", socks_cfg, "udp", "UDP:REPLY && R2:TIMEOUT && R3:REPLY", "Q8", "proxy/socks/protocol.go", "首包锁定 IP:端口，第二个本机端口被丢", args=A + " -assoc 0.0.0.0:0 -second")
l("socks-udp-after-tcp-close", socks_cfg, "udp", "UDP:TIMEOUT", "Q8", "proxy/socks/protocol.go", "TCP 控制连接关掉后临时 UDP 端口跟着关", args=A + " -assoc 0.0.0.0:0 -closetcp")
l("socks-udp-to-inbound-port", socks_cfg, "udp", "UDP:TIMEOUT", "Q8", "proxy/socks/protocol.go", "变：入站端口本身不再收 UDP（v26.3.27 收）", args=A + " -assoc 0.0.0.0:0 -raw 21000")

# ---------------- 写文件
write_suite(TEST, "T2", TEST_COLUMNS, T)
write_suite(LB, "L2", LOOPBACK_COLUMNS, L)
print(len(T), "test configs,", len(L), "loopback configs")
