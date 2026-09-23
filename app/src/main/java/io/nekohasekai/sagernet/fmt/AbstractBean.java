package io.nekohasekai.sagernet.fmt;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

import io.nekohasekai.sagernet.ktx.NetsKt;
import moe.matsuri.nb4a.utils.JavaUtil;

public abstract class AbstractBean extends Serializable {

    public String serverAddress;
    public Integer serverPort;

    public String name;

    //

    public String customOutboundJson;
    public String customConfigJson;

    //
    public transient String finalAddress;
    public transient int finalPort;

    public String displayName() {
        if (JavaUtil.isNotBlank(name)) {
            return name;
        } else {
            return displayAddress();
        }
    }

    public String displayAddress() {
        return NetsKt.wrapIPV6Host(serverAddress) + ":" + serverPort;
    }

    public String network() {
        return "tcp,udp";
    }

    public boolean canTCPing() {
        return true;
    }

    public boolean canMapping() {
        return true;
    }

    @Override
    public void initializeDefaultValues() {
        if (JavaUtil.isNullOrBlank(serverAddress)) {
            serverAddress = "127.0.0.1";
        } else if (serverAddress.startsWith("[") && serverAddress.endsWith("]")) {
            serverAddress = NetsKt.unwrapIPV6Host(serverAddress);
        }
        if (serverPort == null) {
            serverPort = 1080;
        }
        if (name == null) name = "";

        finalAddress = serverAddress;
        finalPort = serverPort;

        if (customOutboundJson == null) customOutboundJson = "";
        if (customConfigJson == null) customConfigJson = "";
    }


    private transient boolean serializeWithoutName;

    @Override
    public void serializeToBuffer(@NonNull ByteBufferOutput output) {
        serialize(output);

        output.writeInt(1);
        if (!serializeWithoutName) {
            output.writeString(name);
        }
        output.writeString(customOutboundJson);
        output.writeString(customConfigJson);
    }

    @Override
    public void deserializeFromBuffer(@NonNull ByteBufferInput input) {
        deserialize(input);

        int extraVersion = input.readInt();

        name = input.readString();
        customOutboundJson = input.readString();
        customConfigJson = input.readString();
    }

    public void serialize(ByteBufferOutput output) {
        output.writeString(serverAddress);
        output.writeInt(serverPort);
    }

    public void deserialize(ByteBufferInput input) {
        serverAddress = input.readString();
        serverPort = input.readInt();
    }

    // Kryo round-trip into a fresh instance of the concrete class; every bean
    // has a public no-arg constructor (the parsers and Room rely on it too).
    @NotNull
    @Override
    public AbstractBean clone() {
        AbstractBean copy;
        try {
            copy = getClass().getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return KryoConverters.deserialize(copy, KryoConverters.serialize(this));
    }

    // 比较前临时去掉 name 再序列化；持有本实例监视器，并发的 equals/hashCode
    // 看不到这次翻转。KryoConverters.serialize 也抢同一把锁，因此 Room 写库、
    // 分享链接和 clone() 同样观察不到。
    private synchronized byte[] serializeForComparison() {
        try {
            serializeWithoutName = true;
            return KryoConverters.serialize(this);
        } finally {
            serializeWithoutName = false;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        // each side is serialized under its own monitor, one at a time; never
        // holding both locks avoids AB-BA deadlock on symmetric equals calls
        return Arrays.equals(serializeForComparison(), ((AbstractBean) o).serializeForComparison());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(serializeForComparison());
    }

    @NotNull
    @Override
    public String toString() {
        return getClass().getSimpleName() + " " + JavaUtil.gson.toJson(this);
    }
}
