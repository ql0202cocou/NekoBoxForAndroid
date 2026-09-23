package moe.matsuri.nb4a.proxy.neko;


import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;
import org.json.JSONObject;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.ktx.Logs;

public class NekoBean extends AbstractBean {

    public String plgId;
    public String protocolId;
    public JSONObject sharedStorage = new JSONObject();

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (protocolId == null) protocolId = "";
        if (plgId == null) plgId = "moe.matsuri.plugin.donotexist";
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(0);
        super.serialize(output);
        output.writeString(plgId);
        output.writeString(protocolId);
        output.writeString(sharedStorage.toString());
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        plgId = input.readString();
        protocolId = input.readString();
        sharedStorage = tryParseJSON(input.readString());
    }

    @NotNull
    public static JSONObject tryParseJSON(String input) {
        JSONObject ret;
        try {
            ret = new JSONObject(input);
        } catch (Exception e) {
            ret = new JSONObject();
            Logs.INSTANCE.e(e);
        }
        return ret;
    }

    public String displayType() {
        return "invalid";
    }

    @Override
    public boolean canMapping() {
        return false;
    }

    @Override
    public boolean canTCPing() {
        return false;
    }

}