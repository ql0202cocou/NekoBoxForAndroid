package moe.matsuri.nb4a.proxy.anytls;


import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;


import io.nekohasekai.sagernet.fmt.AbstractBean;

public class AnyTLSBean extends AbstractBean {

    public static final int CERTIFICATE_POLICY_LEGACY = -1;
    public static final int CERTIFICATE_POLICY_PIN = 0;
    public static final int CERTIFICATE_POLICY_TRUST = 1;

    // 来源只决定订阅刷新时能否替换策略；通用分享不会把发布方的手动设置变成接收方的本地覆写。
    public static final int POLICY_DEFAULT = 0;
    public static final int POLICY_PUBLISHED = 1;
    public static final int POLICY_MIGRATED = 2;
    public static final int POLICY_LOCAL = 3;
    public static final int POLICY_SOURCE_UNKNOWN = 4;

    public String password;
    public String sni;
    public String alpn;
    public String certificates;
    public String certificateFingerprint;
    public String utlsFingerprint;
    public Boolean allowInsecure;
    // In sing-box, this seemed can be used with REALITY.
    // But even mihomo appended many options, it still not provide REALITY.
    // https://github.com/anytls/anytls-go/blob/4636d90462fa21a510420512d7706a9acf69c7b9/docs/faq.md?plain=1#L25-L37

    public String echConfig;
    // enable-only ECH (no explicit config; the core discovers it over DNS)
    public Boolean enableECH;
    // 历史 v3 测试标记：保留存储布局与旧备份字节，不再参与运行、选核或分享。
    public Boolean compatibilityTest;
    // 与核心选择分开保存；旧字节须在迁移或导入时依据原核心解析，不能默认降为固定。
    public Integer certificatePolicy;
    public Integer certificatePolicySource = POLICY_DEFAULT;
    // 旧 mihomo 节点的非空出站覆写作用于本机 SOCKS 跳。
    public Boolean localSocksOutboundJson = false;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (password == null) password = "";
        if (sni == null) sni = "";
        if (alpn == null) alpn = "";
        if (certificates == null) certificates = "";
        if (certificateFingerprint == null) certificateFingerprint = "";
        if (utlsFingerprint == null) utlsFingerprint = "";
        if (allowInsecure == null) allowInsecure = false;
        if (echConfig == null) echConfig = "";
        if (enableECH == null) enableECH = false;
        if (compatibilityTest == null) compatibilityTest = false;
        if (certificatePolicy == null) certificatePolicy = CERTIFICATE_POLICY_PIN;
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(5);
        super.serialize(output);
        output.writeString(password);
        output.writeString(sni);
        output.writeString(alpn);
        output.writeString(certificates);
        output.writeString(utlsFingerprint);
        output.writeBoolean(allowInsecure);
        output.writeString(echConfig);
        output.writeString(certificateFingerprint);
        output.writeBoolean(enableECH);
        output.writeBoolean(compatibilityTest);
        output.writeInt(certificatePolicy);
        output.writeInt(certificatePolicySource);
        output.writeBoolean(localSocksOutboundJson);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        certificatePolicy = CERTIFICATE_POLICY_LEGACY;
        int version = input.readInt();
        super.deserialize(input);
        password = input.readString();
        sni = input.readString();
        alpn = input.readString();
        certificates = input.readString();
        utlsFingerprint = input.readString();
        allowInsecure = input.readBoolean();
        echConfig = input.readString();
        if (version >= 1) {
            certificateFingerprint = input.readString();
        }
        if (version >= 2) {
            enableECH = input.readBoolean();
        }
        compatibilityTest = version >= 3 && input.readBoolean();
        certificatePolicy = version >= 4 ? input.readInt() : CERTIFICATE_POLICY_LEGACY;
        certificatePolicySource = version >= 5 ? input.readInt() : POLICY_SOURCE_UNKNOWN;
        localSocksOutboundJson = version >= 5 && input.readBoolean();
    }

}
