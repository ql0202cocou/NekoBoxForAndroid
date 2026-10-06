package io.nekohasekai.sagernet.fmt.v2ray;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import java.nio.ByteBuffer;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.http.HttpBean;
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean;
import io.nekohasekai.sagernet.ktx.KryosKt;
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean;
import moe.matsuri.nb4a.utils.JavaUtil;

public abstract class StandardV2RayBean extends AbstractBean {

    public String uuid;
    public String encryption; // or VLESS flow

    //////// End of VMess & VLESS ////////

    // "V2Ray Transport" tcp/http/ws/quic/grpc/httpupgrade
    public String type;

    public String host;

    public String path;

    // --------------------------------------- tls?

    public String security;

    public String sni;

    public String alpn;

    public String utlsFingerprint;

    public Boolean allowInsecure;

    // --------------------------------------- reality


    public String realityPubKey;

    public String realityShortId;

    // Xray-only: sing-box 1.13 has no ML-DSA-65 REALITY support
    public String realityMldsa65Verify;

    // SHA-256 hash of a served certificate (mihomo "fingerprint" / Xray
    // pinnedPeerCertSha256). sing-box cannot express it: its
    // certificate_public_key_sha256 is an SPKI hash, not interchangeable, so
    // there the value is stored without effect (a build-time warning is logged).
    public String certificateFingerprint;

    // --------------------------------------- //

    public Integer wsMaxEarlyData;
    public String earlyDataHeaderName;

    public String certificates;

    // --------------------------------------- ech

    public Boolean enableECH;

    public String echConfig;

    // --------------------------------------- Mux

    public Boolean enableMux;
    public Boolean muxPadding;
    public Integer muxType;
    public Integer muxConcurrency;


    // --------------------------------------- //

    public Integer packetEncoding; // 1:packet 2:xudp

    // K1 之前的实现写下的数据（这一层版本号 < 7）：mux 协议族、ws early data 的携带方式等还没按 K1 的选核标注
    // （LegacyProfileUpgrade.kt）。只由 deserialize 置位，不进字节；备份恢复、通用链接导入标注完成后由调用方清掉。
    // 数据库里的存量节点由 8 → 9 的迁移统一标注，之后读库仍可能读到 v6 字节（迁移只改写有变化的行），
    // 读库得到的 bean 不看这个标记
    public transient boolean legacyUnlabeled;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();

        if (JavaUtil.isNullOrBlank(uuid)) uuid = "";

        if (JavaUtil.isNullOrBlank(type)) type = "tcp";
        else if ("h2".equals(type)) type = "http";

        type = type.toLowerCase(java.util.Locale.ROOT);

        if (JavaUtil.isNullOrBlank(host)) host = "";
        if (JavaUtil.isNullOrBlank(path)) path = "";

        if (JavaUtil.isNullOrBlank(security)) {
            if (this instanceof TrojanBean) {
                security = "tls";
            } else {
                security = "none";
            }
        }
        if (JavaUtil.isNullOrBlank(sni)) sni = "";
        if (JavaUtil.isNullOrBlank(alpn)) alpn = "";

        if (JavaUtil.isNullOrBlank(certificates)) certificates = "";
        if (JavaUtil.isNullOrBlank(earlyDataHeaderName)) earlyDataHeaderName = "";
        if (JavaUtil.isNullOrBlank(utlsFingerprint)) utlsFingerprint = "";

        if (wsMaxEarlyData == null) wsMaxEarlyData = 0;
        if (allowInsecure == null) allowInsecure = false;
        if (packetEncoding == null) packetEncoding = 0;

        if (realityPubKey == null) realityPubKey = "";
        if (realityShortId == null) realityShortId = "";
        if (realityMldsa65Verify == null) realityMldsa65Verify = "";
        if (certificateFingerprint == null) certificateFingerprint = "";

        if (enableECH == null) enableECH = false;
        if (JavaUtil.isNullOrBlank(echConfig)) echConfig = "";

        if (enableMux == null) enableMux = false;
        if (muxPadding == null) muxPadding = false;
        if (muxType == null) muxType = 0;
        if (muxConcurrency == null) muxConcurrency = 1;
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        // 7：字节布局与 6 相同，只用版本号区分 K1 之前写下、还没标注的数据（见 legacyUnlabeled）
        output.writeInt(7);
        super.serialize(output);
        output.writeString(uuid);
        output.writeString(encryption);
        if (this instanceof VMessBean) {
            output.writeInt(((VMessBean) this).alterId);
        }

        output.writeString(type);
        switch (type) {
            case "tcp":
            case "quic": {
                break;
            }
            case "ws": {
                output.writeString(host);
                output.writeString(path);
                output.writeInt(wsMaxEarlyData);
                output.writeString(earlyDataHeaderName);
                break;
            }
            case "http":
            case "httpupgrade": {
                output.writeString(host);
                output.writeString(path);
                break;
            }
            case "grpc": {
                output.writeString(path);
                break;
            }
        }

        output.writeString(security);
        if ("tls".equals(security)) {
            output.writeString(sni);
            output.writeString(alpn);
            output.writeString(certificates);
            output.writeBoolean(allowInsecure);
            output.writeString(utlsFingerprint);
            output.writeString(realityPubKey);
            output.writeString(realityShortId);
        }

        output.writeBoolean(enableECH);
        output.writeString(echConfig);

        output.writeInt(packetEncoding);

        output.writeBoolean(enableMux);
        output.writeBoolean(muxPadding);
        output.writeInt(muxType);
        output.writeInt(muxConcurrency);

        output.writeString(realityMldsa65Verify);
        output.writeString(certificateFingerprint);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        legacyUnlabeled = version < 7;
        super.deserialize(input);
        uuid = input.readString();
        encryption = input.readString();
        if (this instanceof VMessBean) {
            ((VMessBean) this).alterId = input.readInt();
        }

        type = input.readString();
        switch (type) {
            case "tcp":
            case "quic": {
                break;
            }
            case "ws": {
                host = input.readString();
                path = input.readString();
                wsMaxEarlyData = input.readInt();
                earlyDataHeaderName = input.readString();
                break;
            }
            case "http":
            case "httpupgrade": {
                host = input.readString();
                path = input.readString();
                break;
            }
            case "grpc": {
                int pathStart = input.position();
                path = input.readString();
                if (version < 4) {
                    // 老版本在 path 之后多写了 host、path；版本号 0、3 下还有只写 path 的数据，预读判断（见 grpcPathRepeated）
                    boolean layoutsCoexist = version == 0 || version == 3;
                    if (!layoutsCoexist || grpcPathRepeated(input, version, pathStart)) {
                        input.readString();
                        input.readString();
                    }
                }
                break;
            }
        }

        security = input.readString();
        if ("tls".equals(security)) {
            sni = input.readString();
            alpn = input.readString();
            certificates = input.readString();
            allowInsecure = input.readBoolean();
            utlsFingerprint = input.readString();
            realityPubKey = input.readString();
            realityShortId = input.readString();
        }

        if (version >= 1) {
            enableECH = input.readBoolean();
            if (version >= 3) {
                echConfig = input.readString();
            } else {
                if (enableECH) {
                    input.readBoolean();
                    input.readBoolean();
                    echConfig = input.readString();
                }
            }
        } else if (version == 0) {
            // fb55430（1.3.0）开始序列化 ECH 字段但 version 仍写 0，b34c012 才把
            // version 改成 1，因此 version == 0 同时存在「无 ECH 块」「有 ECH 块」
            // 两种布局。Kryo 的 int 是小端 4 字节、boolean 是单字节 0/1；字符串首字节
            // 恒非 0（null 写 0x80、空串 0x81，2–32 个 ASCII 字符直接写字符本身、末字节
            // 或上 0x80，其余先写带 0x80 标志位的 varint 长度）。从当前位置预读 1 字节加 1 个 int
            // 交叉校验（packetEncoding 仅取 0/1/2，小端高 3 字节恒为 0）：
            //   有 ECH 块且 enableECH=false：[0][packetEncoding]
            //     → 预读 int 即 packetEncoding（0/1/2）
            //   有 ECH 块且 enableECH=true ：[1][pq][drs][echConfig 长度…]
            //     → 预读 int 低 24 位含 echConfig 的首字节，恒非 0
            //   无 ECH 块：[packetEncoding][extraVersion=1 / Trojan 的 password]
            //     → 预读 int 低 24 位恒为 0（高字节是子类的下一个字段：VMess 为
            //       extraVersion 小端首字节 0x01，Trojan / Http 为 password / username
            //       的首字节，ShadowTLS 为 version 小端首字节）
            int position = input.getByteBuffer().position(); // 当前位置

            int head = input.readByte() & 0xFF;
            int probe = input.readInt();

            input.setPosition(position); // 读后归位

            boolean hasEchBlock;
            if (head <= 1 && probe >= 0 && probe <= 2) {
                hasEchBlock = true; // enableECH=false 的 ECH 块
            } else if ((probe & 0x00FFFFFF) == 0 && probe != 0) {
                hasEchBlock = false; // 无 ECH 块，预读 int 落在 packetEncoding 尾部
            } else {
                // enableECH=true 的 ECH 块；实在无法识别的损坏数据按无 ECH 块
                // 兜底，后续读取错位抛 KryoException，走 lenient 重置
                hasEchBlock = head == 1;
            }

            if (hasEchBlock) {
                enableECH = input.readBoolean();
                if (enableECH) {
                    input.readBoolean();
                    input.readBoolean();
                    echConfig = input.readString();
                }
            } // 否则下一位就是 packetEncoding
        }

        packetEncoding = input.readInt();

        if (version >= 2) {
            enableMux = input.readBoolean();
            muxPadding = input.readBoolean();
            muxType = input.readInt();
            muxConcurrency = input.readInt();
        }

        if (version >= 5) {
            realityMldsa65Verify = input.readString();
        }
        if (version >= 6) {
            certificateFingerprint = input.readString();
        }
    }

    // 旧版本 grpc 节点的两种布局。e805fe7（1.2.9）给 serialize 加 httpupgrade 分支时 grpc 分支没有 break，
    // 落入其中，在 path 之后多写了 host、path（第二个 path 与第一个是同一个字段，字节完全相同）；
    // 4326aab 补上 break，b39ac9a 把版本号升到 4。各版本号写出的 grpc：
    //   0：布局 A（e805fe7 之前，≤1.2.8）只写 path；布局 B（e805fe7–fb55430^）多写；
    //      布局 C（fb55430–b34c012^）多写，并带 ECH 块（见 deserialize 里 version == 0 的说明）
    //   1、2：一律多写
    //   3：布局 a（912a066–4326aab^）多写；布局 b（4326aab–b39ac9a^）只写 path
    //   4 起：只写 path
    // 只有 0、3 两种布局并存，只在这两个版本号上调用本方法。做法：把 path 之后的剩余字节按「多写」「只写 path」
    // 各读一遍（版本号 0 的多写再分无 ECH 块、有 ECH 块两种），一直读到末尾，只检查写出端对任何取值都成立的条件：
    //   - 多写时 host 之后的字符串与 path 逐字节相同；
    //   - 字符串能完整读出（预读用有长度上限的输入，长度超出剩余字节算读不通）；
    //   - boolean 字节只能是 0 或 1（Kryo writeBoolean 的写法）；
    //   - security 等于 "tls" 才有 7 个 TLS 字段，与 serialize 的判断相同；不依赖 security 的取值范围
    //     （分享链接、v2rayN 的 JSON 能写进任意字符串，未规整时还有 "" / null）；
    //   - 子类字段：VMess 没有；Trojan 是 password；HTTP 是 username、password；ShadowTLS 是 int version、password
    //     （这两个版本号所在的年代里这些布局没有变过）；
    //   - 末尾是 AbstractBean.serializeToBuffer 写的 extraVersion（自首个提交起恒为 1）和 name、customOutboundJson、
    //     customConfigJson，且恰好读到字节末尾：bean 的字节总是单独一段（Room 列、分享链接是 KryoConverters.serialize
    //     的整段输出，备份与 Parcel 里实体内嵌的 bean 也是这样一段，读取时单独解出）。
    // int 字段（packetEncoding、mux、ShadowTLS version）不检查取值：被旧版读错位后又写回的行里可以是任意值。
    // 取舍：只有「只写 path」读得通而「多写」读不通时才按只写 path 读；其余情况（都读得通、都读不通，例如损坏的
    // 数据或不认识的子类）维持原来的多写读法。多写的真实字节必然按多写读得通，所以凡是现在读得对的字节，读法不变。
    // 判为只写 path 的真实数据只能是布局 A 或 b，接下来 version == 0 的 ECH 块判断面对的字节与非 grpc 的布局 A 节点相同。
    // 残留歧义：只写 path 的真实字节若同时也按多写读得通，仍按多写读错，表现与修复前相同（字段错位，可能抛异常，
    // 也可能读出错位的值）。必要条件是 security 之后的那个字符串与 path 逐字节相同，即下列之一：
    //   - security 为 "tls" 且 sni 与 path 相同（包括同为空串，这是常见情形，靠后面的检查排除）：多写读法把 alpn
    //     当作 security，之后还要一路对齐到末尾。alpn 不是 "tls" 时，版本号 3 与布局 C 紧接着要求 certificates 的首字节
    //     为 0 / 1（certificates 是以 U+0000 / U+0001 开头的 2–32 个 ASCII 字符）；布局 B 紧接着是不检查的
    //     packetEncoding，之后要子类字段与 extraVersion = 1 恰好对齐；alpn 恰为 "tls" 时多写读法再读 7 个 TLS 字段。
    //     这两种情况都要靠字段内容巧合才能对齐到末尾（例如字段里含 U+0000 一类的控制字符）。
    //   - security 不为 "tls"：path 的首字节须等于其后 boolean（版本号 3）或 packetEncoding（版本号 0）的首字节 0–2，
    //     即 path 是以 U+0000–U+0002 开头的 2–32 个 ASCII 字符。
    // 预读在剩余字节的副本上进行，不移动 input 的读取位置；预读中的任何异常都算读不通。
    private boolean grpcPathRepeated(ByteBufferInput input, int version, int pathStart) {
        int pathLength = input.position() - pathStart;
        ByteBuffer buffer = input.getByteBuffer();
        byte[] rest = new byte[input.limit() - pathStart];
        for (int i = 0; i < rest.length; i++) {
            rest[i] = buffer.get(pathStart + i);
        }
        ByteBufferInput probe = KryosKt.byteBuffer(rest);
        boolean repeated = grpcLayoutFits(probe, rest, pathLength, version, true, false)
                || version == 0 && grpcLayoutFits(probe, rest, pathLength, version, true, true);
        if (repeated) return true;
        return !grpcLayoutFits(probe, rest, pathLength, version, false, false);
    }

    // 从 path 之后按一种布局把 rest（path 起到 bean 字节末尾）读完，每一步都对得上返回 true。
    // repeated：path 之后多写了 host、path；echBlock：版本号 0 布局 C 的 ECH 块
    private boolean grpcLayoutFits(ByteBufferInput in, byte[] rest, int pathLength, int version,
                                   boolean repeated, boolean echBlock) {
        try {
            in.setPosition(pathLength);
            if (repeated) {
                in.readString(); // host
                int start = in.position();
                if (rest.length - start < pathLength) return false;
                for (int i = 0; i < pathLength; i++) {
                    if (rest[start + i] != rest[i]) return false; // 多写的 path 与 path 逐字节相同
                }
                in.setPosition(start + pathLength);
            }
            if ("tls".equals(in.readString())) { // security
                in.readString(); // sni
                in.readString(); // alpn
                in.readString(); // certificates
                if (!readBooleanByte(in)) return false; // allowInsecure
                in.readString(); // utlsFingerprint
                in.readString(); // realityPubKey
                in.readString(); // realityShortId
            }
            if (version == 3) {
                if (!readBooleanByte(in)) return false; // enableECH
                in.readString(); // echConfig
            } else if (echBlock) {
                byte echFlag = in.readByte(); // enableECH
                if (echFlag == 1) {
                    if (!readBooleanByte(in) || !readBooleanByte(in)) return false; // enablePqSignature、disabledDRS
                    in.readString(); // echConfig
                } else if (echFlag != 0) {
                    return false;
                }
            }
            in.readInt(); // packetEncoding
            if (version == 3) {
                if (!readBooleanByte(in) || !readBooleanByte(in)) return false; // enableMux、muxPadding
                in.readInt(); // muxType
                in.readInt(); // muxConcurrency
            }
            if (this instanceof TrojanBean) {
                in.readString(); // password
            } else if (this instanceof HttpBean) {
                in.readString(); // username
                in.readString(); // password
            } else if (this instanceof ShadowTLSBean) {
                in.readInt(); // version
                in.readString(); // password
            } else if (!(this instanceof VMessBean)) {
                return false; // 不认识的子类：两种布局都算读不通，维持原读法
            }
            if (in.readInt() != 1) return false; // extraVersion
            in.readString(); // name
            in.readString(); // customOutboundJson
            in.readString(); // customConfigJson
            return in.position() == rest.length;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean readBooleanByte(ByteBufferInput in) {
        byte b = in.readByte();
        return b == 0 || b == 1;
    }

    public static final String FLOW_VISION = "xtls-rprx-vision";

    public boolean isVLESS() {
        if (this instanceof VMessBean) {
            Integer aid = ((VMessBean) this).alterId;
            return aid != null && aid == -1;
        }
        return false;
    }

    // for VLESS, `encryption` holds the flow value
    public boolean isVisionFlow() {
        return isVLESS() && FLOW_VISION.equals(encryption);
    }

}
