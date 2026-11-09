#!/bin/sh
# 重新生成核心探测用的虚构测试 PKI，覆盖本目录同名输入。
# 两套链：EC P-256 根 -> 叶子，以及 RSA-4096 根 -> 中间 -> 叶子。
# fullchain.pem = EC 叶子 + 根；bfull.pem = RSA 叶子 + 中间 + 根；*.pk8 为叶子私钥。
# 用法：sh gen-pki.sh（任意目录均可），OPENSSL 可指定其它 openssl 实现。
# 更换材料后按 ../../README.md 重新导出 Kotlin 样本并运行四个配置生成器；旧结果不再适用。
set -eu

OPENSSL=${OPENSSL:-/usr/bin/openssl}
DAYS=3650
HERE=$(cd "$(dirname "$0")" && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
cd "$TMP"

# ---- 扩展配置（每段列出该证书的全部扩展，没写的扩展证书里就没有）
# 根证书：basicConstraints 关键、不限路径长度；keyUsage 关键；带 SubjectKeyIdentifier（req -x509 自动加）。
# 主题要写在配置的 [dn] 段里（prompt = no 时 req 不认 -subj），所以每个根各生成一份
mkroot_cnf() { # $1 输出文件，其余参数是 [dn] 段的行（顺序即证书里的 RDN 顺序）
    out=$1; shift
    {
        printf '[req]\ndistinguished_name = dn\nx509_extensions = v3_root\nprompt = no\n[dn]\n'
        for l in "$@"; do printf '%s\n' "$l"; done
        printf '[v3_root]\nbasicConstraints = critical,CA:TRUE\nkeyUsage = critical,keyCertSign,cRLSign\nsubjectKeyIdentifier = hash\n'
    } > "$out"
}
mkroot_cnf ext-ca.cnf "CN = Example Test CA"
mkroot_cnf ext-bca.cnf "CN = Example Big Root CA" "O = Example Org" "OU = Testing Unit For Large Chains"
# 中间证书：只有 basicConstraints(关键, 无 pathlen) 与 keyUsage(关键, 仅 keyCertSign)，没有 SKI / AKI
cat > ext-int.cnf <<'CNF'
basicConstraints = critical,CA:TRUE
keyUsage = critical,keyCertSign
CNF
# 叶子证书（EC）：SAN 只有 node.example.com；basicConstraints 非关键；EKU 仅 serverAuth；没有 keyUsage
cat > ext-leaf.cnf <<'CNF'
subjectAltName = DNS:node.example.com
basicConstraints = CA:FALSE
extendedKeyUsage = serverAuth
CNF
# 叶子证书（RSA）：同上，SAN 多三个名字
cat > ext-bleaf.cnf <<'CNF'
subjectAltName = DNS:node.example.com,DNS:www.example.com,DNS:a.example.com,DNS:b.example.com
basicConstraints = CA:FALSE
extendedKeyUsage = serverAuth
CNF

# ---- 链一：EC P-256
$OPENSSL ecparam -name prime256v1 -genkey -noout -out ca.key
$OPENSSL req -x509 -new -key ca.key -sha256 -days $DAYS -config ext-ca.cnf -out ca.pem

$OPENSSL ecparam -name prime256v1 -genkey -noout -out leaf.key
$OPENSSL req -new -key leaf.key -subj "/CN=node.example.com" -out leaf.csr
# -CAcreateserial 在 CA 证书旁生成 ca.srl（随机序列号）
$OPENSSL x509 -req -in leaf.csr -CA ca.pem -CAkey ca.key -CAcreateserial -sha256 -days $DAYS -extfile ext-leaf.cnf -out leaf.pem
cat leaf.pem ca.pem > fullchain.pem
$OPENSSL pkcs8 -topk8 -nocrypt -in leaf.key -out leaf.pk8

# ---- 链二：RSA-4096，根 -> 中间 -> 叶子
$OPENSSL genrsa -out bca.key 4096
$OPENSSL req -x509 -new -key bca.key -sha256 -days $DAYS -config ext-bca.cnf -out bca.pem

$OPENSSL genrsa -out bint.key 4096
$OPENSSL req -new -key bint.key -subj "/CN=Example Big Intermediate CA/O=Example Org" -out bint.csr
$OPENSSL x509 -req -in bint.csr -CA bca.pem -CAkey bca.key -CAcreateserial -sha256 -days $DAYS -extfile ext-int.cnf -out bint.pem

$OPENSSL genrsa -out bleaf.key 4096
$OPENSSL req -new -key bleaf.key -subj "/CN=node.example.com" -out bleaf.csr
$OPENSSL x509 -req -in bleaf.csr -CA bint.pem -CAkey bint.key -CAcreateserial -sha256 -days $DAYS -extfile ext-bleaf.cnf -out bleaf.pem
cat bleaf.pem bint.pem bca.pem > bfull.pem
$OPENSSL pkcs8 -topk8 -nocrypt -in bleaf.key -out bleaf.pk8

# ---- 自检：链必须能验过
$OPENSSL verify -CAfile ca.pem leaf.pem
$OPENSSL verify -CAfile bca.pem -untrusted bint.pem bleaf.pem

# ---- 只保留探测器实际读取的输入，根私钥与签发中间产物随临时目录清理
for f in ca.pem leaf.pem leaf.pk8 fullchain.pem bleaf.pk8 bfull.pem; do
    cp "$f" "$HERE/$f"
done
