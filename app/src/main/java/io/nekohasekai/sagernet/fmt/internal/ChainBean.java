package io.nekohasekai.sagernet.fmt.internal;


import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;


import java.util.ArrayList;
import java.util.List;

import moe.matsuri.nb4a.utils.JavaUtil;

public class ChainBean extends InternalBean {

    public List<Long> proxies;

    @Override
    public String displayName() {
        if (JavaUtil.isNotBlank(name)) {
            return name;
        } else {
            return "Chain " + Math.abs(hashCode());
        }
    }

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (name == null) name = "";

        if (proxies == null) {
            proxies = new ArrayList<>();
        }
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(1);
        output.writeInt(proxies.size());
        for (Long proxy : proxies) {
            output.writeLong(proxy);
        }
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        if (version < 1) {
            input.readString();
            input.readInt();
        }
        int length = input.readInt();
        if (length < 0 || length > (input.limit() - input.position()) / Long.BYTES) {
            throw new com.esotericsoftware.kryo.KryoException("Invalid chain length");
        }
        proxies = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            proxies.add(input.readLong());
        }
    }

}
