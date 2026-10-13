package io.nekohasekai.sagernet.fmt.shadowsocks;


import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;


import io.nekohasekai.sagernet.fmt.AbstractBean;
import moe.matsuri.nb4a.utils.JavaUtil;

public class ShadowsocksBean extends AbstractBean {

    public String method;
    public String password;
    public String plugin;

    public Boolean sUoT;

    // UDP over TCP 的协议版本，1 或 2。默认 2：sing-box 的 udp_over_tcp 只写 true 时用 2，老数据与
    // 非 Clash 来源的节点都是这样；Clash 订阅按 mihomo 的规则填（不写版本时 mihomo 用 1）
    public Integer sUoTVersion;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();

        if (JavaUtil.isNullOrBlank(method)) method = "aes-256-gcm";
        if (password == null) password = "";
        if (plugin == null) plugin = "";
        if (sUoT == null) sUoT = false;
        if (sUoTVersion == null) sUoTVersion = 2;
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(3);
        super.serialize(output);
        output.writeString(method);
        output.writeString(password);
        output.writeString(plugin);
        output.writeBoolean(sUoT);
        output.writeInt(sUoTVersion);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        method = input.readString();
        password = input.readString();
        plugin = input.readString();
        if (version >= 2) {
            sUoT = input.readBoolean();
        }
        if (version >= 3) {
            sUoTVersion = input.readInt();
        }
    }

}
