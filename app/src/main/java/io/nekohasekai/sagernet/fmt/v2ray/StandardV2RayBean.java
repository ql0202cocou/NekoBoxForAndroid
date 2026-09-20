package io.nekohasekai.sagernet.fmt.v2ray;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean;
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
        output.writeInt(6);
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
                path = input.readString();
                if (version < 4) {
                    // 解决老版本数据的读取问题
                    input.readString();
                    input.readString();
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
            // 两种布局。Kryo 的 int 是小端 4 字节、boolean 是单字节 0/1、字符串
            // varint 长度首字节必带 0x80 标志位。从当前位置预读 1 字节加 1 个 int
            // 交叉校验（packetEncoding 仅取 0/1/2，小端高 3 字节恒为 0）：
            //   有 ECH 块且 enableECH=false：[0][packetEncoding]
            //     → 预读 int 即 packetEncoding（0/1/2）
            //   有 ECH 块且 enableECH=true ：[1][pq][drs][echConfig 长度…]
            //     → 预读 int 低 24 位含 echConfig 长度字节的 0x80，恒非 0
            //   无 ECH 块：[packetEncoding][extraVersion=1 / Trojan 的 password]
            //     → 预读 int 低 24 位恒为 0（VMess 其后是 extraVersion 小端首字节
            //       0x01，Trojan 其后是 password 的 varint 长度字节）
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
