package moe.matsuri.nb4a.proxy.config;


import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;
import com.google.gson.JsonObject;


import io.nekohasekai.sagernet.fmt.internal.InternalBean;
import moe.matsuri.nb4a.utils.JavaUtil;

public class ConfigBean extends InternalBean {

    public Integer type; // 0=config 1=outbound
    public String config;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (type == null) type = 0;
        if (config == null) config = "";
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(0);
        super.serialize(output);
        output.writeInt(type);
        output.writeString(config);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        type = input.readInt();
        config = input.readString();
    }

    @Override
    public String displayName() {
        if (JavaUtil.isNotBlank(name)) {
            return name;
        } else {
            // 只按 config 取哈希：hashCode() 每次都要整份 Kryo 序列化，且改自定义
            // JSON 等无关字段也会改名
            return "Custom " + Math.abs((config == null ? "" : config).hashCode());
        }
    }

    public String displayType() {
        if (type != null && type == 1 && JavaUtil.isNotBlank(config)) {
            try {
                JsonObject json = JavaUtil.gson.fromJson(config, JsonObject.class);
                if (json != null && json.has("type")) {
                    return json.get("type").getAsString() + " (sing-box)";
                }
            } catch (Exception ignored) {
            }
        }
        return type != null && type == 0 ? "sing-box config" : "sing-box outbound";
    }

}