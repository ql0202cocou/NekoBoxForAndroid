# 四个配置生成器共用的虚构材料、配置构件与写文件逻辑（只用标准库，import 时不写任何文件）。
# 证书是 fixtures/pki 的「Example Test CA」，REALITY 密钥来自 fixtures/x25519.txt。
import hashlib, json, os, ssl

FIXTURES = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "fixtures"))

U, P = "uc90f4cf742b44cca", "p7b7c6006f6cfa3368ea9f75160f0df4b"  # 本机 socks 凭据（生成器形状：u+16 位、p+32 位十六进制）；runner-lib.sh 里有同一份
UUID = "64d988ac-52d8-4e06-8fff-a5120f441a72"
TPW = "tp-3a38db6d347563d9"
SID = "0123abcd"
UDP_ARGS = "-addr 127.0.0.1:31080 -target 127.0.0.1:5353"
ALLOW_LOOP = {"finalRules": [{"action": "allow", "ip": ["127.0.0.0/8", "::1/128"]}]}  # v26.9.30 freedom 对代理入站缺省拦私有地址（proxy/freedom/freedom.go）
DEFAULT_ALPN = ["h2", "http/1.1"]

TEST_COLUMNS = ("cmd", "expect", "item", "src", "note")
LOOPBACK_COLUMNS = ("kind", "args", "expect", "item", "src", "note")


def pki(name):
    return open(os.path.join(FIXTURES, "pki", name)).read().strip().splitlines()


def der_sha256(name):
    # 证书指纹 = 证书 DER 的 SHA-256（pinnedPeerCertSha256 / mihomo 的 fingerprint），直接从 pki/ 里的证书算，换证书后不用手改
    return hashlib.sha256(ssl.PEM_cert_to_DER_cert(open(os.path.join(FIXTURES, "pki", name)).read())).hexdigest()


def load_kv(name):
    return dict(l.split(": ", 1) for l in open(os.path.join(FIXTURES, name)).read().strip().splitlines())


LEAF, LEAF_KEY, CA, FULL = pki("leaf.pem"), pki("leaf.pk8"), pki("ca.pem"), pki("fullchain.pem")
LEAF_PIN = der_sha256("leaf.pem")
_x25519 = load_kv("x25519.txt")
PRIV, PUB = _x25519["PrivateKey"], _x25519["Password (PublicKey)"]


# ---------------- 配置构件
def tls_server(chain=LEAF, key=LEAF_KEY, alpn=DEFAULT_ALPN):
    t = {"certificates": [{"certificate": chain, "key": key}]}
    if alpn:
        t["alpn"] = alpn
    return {"security": "tls", "tlsSettings": t}


def reality_target(chain=FULL, key=LEAF_KEY, alpn=DEFAULT_ALPN):
    # REALITY 的 target：一个 TLS 服务端（陌生人会被转发到这里）
    return {"tag": "in-target", "listen": "127.0.0.1", "port": 32100, "protocol": "trojan",
            "settings": {"users": [{"password": "example-target-pass"}]},
            "streamSettings": {"network": "tcp", **tls_server(chain, key, alpn)}}


def entry_inbound(udp=False):
    # 客户端入口：带凭据的 socks :31080（开 UDP），或 dokodemo :31000 → 127.0.0.1:40000
    if udp:
        return {"tag": "in-c", "listen": "127.0.0.1", "port": 31080, "protocol": "socks",
                "settings": {"auth": "password", "accounts": [{"user": U, "pass": P}], "udp": True}}
    return {"tag": "in-c", "listen": "127.0.0.1", "port": 31000, "protocol": "dokodemo-door",
            "settings": {"address": "127.0.0.1", "port": 40000, "network": "tcp"}}


# 生成器同形的协议出站（buildXrayOutbound）
def vless(addr="127.0.0.1", port=20001, stream=None, flow=None, enc="none", flat=False, mux=None):
    if flat:
        s = {"address": addr, "port": port, "id": UUID, "encryption": enc}
        if flow:
            s["flow"] = flow
    else:
        u = {"id": UUID, "encryption": enc}
        if flow:
            u["flow"] = flow
        s = {"vnext": [{"address": addr, "port": port, "users": [u]}]}
    o = {"protocol": "vless", "settings": s, "streamSettings": stream or {"network": "tcp"}}
    if mux:
        o["mux"] = mux
    return o


def vmess(addr="127.0.0.1", port=20002, stream=None, security="auto", mux=None):
    o = {"protocol": "vmess", "settings": {"vnext": [{"address": addr, "port": port, "users": [
        {"id": UUID, "alterId": 0, "security": security}]}]}, "streamSettings": stream or {"network": "tcp"}}
    if mux:
        o["mux"] = mux
    return o


def trojan(addr="127.0.0.1", port=20003, stream=None, flat=False, pw=TPW, mux=None, extra=None):
    if flat:
        s = {"address": addr, "port": port, "password": pw}
    else:
        srv = {"address": addr, "port": port, "password": pw}
        srv.update(extra or {})
        s = {"servers": [srv]}
    o = {"protocol": "trojan", "settings": s, "streamSettings": stream or {"network": "tcp"}}
    if mux:
        o["mux"] = mux
    return o


# ---------------- 写文件
def reset_dir(d):
    # 清掉上次生成的配置、清单和日志
    os.makedirs(d, exist_ok=True)
    for f in os.listdir(d):
        if f.endswith((".json", ".tsv", ".log")):
            os.remove(os.path.join(d, f))


def write_json(path, cfg):
    with open(path, "w") as f:
        json.dump(cfg, f, indent=1, ensure_ascii=False)


def write_manifest(out_dir, columns, rows):
    # 空字段写成 -：sh 的 read 会把连续的制表符当成一个分隔符
    with open(os.path.join(out_dir, "manifest.tsv"), "w") as m:
        m.write("\t".join(columns) + "\n")
        for row in rows:
            m.write("\t".join(x or "-" for x in row) + "\n")


def write_suite(out_dir, prefix, columns, cases):
    # cases 的每一项是 (slug, 配置, 其余列...)；编号按顺序，新用例要追加在末尾，前面的编号才不变
    rows = []
    for i, (slug, cfg, *cols) in enumerate(cases, 1):
        cid = f"{prefix}-{i:02d}"
        fn = f"{cid}-{slug}.json"
        write_json(os.path.join(out_dir, fn), cfg)
        rows.append((cid, fn, *cols))
    write_manifest(out_dir, ("id", "file", *columns), rows)
