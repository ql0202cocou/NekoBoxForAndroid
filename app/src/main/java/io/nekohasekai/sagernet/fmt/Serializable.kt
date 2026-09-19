package io.nekohasekai.sagernet.fmt

import android.os.Parcel
import android.os.Parcelable
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput

// The Kryo contract shared by beans and entities; see KryoConverters.
abstract class Serializable {
    abstract fun initializeDefaultValues()
    abstract fun serializeToBuffer(output: ByteBufferOutput)
    abstract fun deserializeFromBuffer(input: ByteBufferInput)
}

// Entities that cross an activity or process boundary carry their Kryo bytes
// in a Parcel. Beans stay plain Serializable: they only ever travel inside an
// entity, so they need no CREATOR of their own.
abstract class ParcelableSerializable : Serializable(), Parcelable {

    override fun describeContents() = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeByteArray(KryoConverters.serialize(this))
    }

    abstract class CREATOR<T : ParcelableSerializable> : Parcelable.Creator<T> {
        abstract fun newInstance(): T

        override fun createFromParcel(source: Parcel): T {
            return KryoConverters.deserialize(newInstance(), source.createByteArray())
        }
    }
}
