package moe.matsuri.nb4a.proxy.anytls;


import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;


import io.nekohasekai.sagernet.fmt.AbstractBean;

public class AnyTLSBean extends AbstractBean {

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
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(2);
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
    }

    @Override
    public void deserialize(ByteBufferInput input) {
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
    }

}