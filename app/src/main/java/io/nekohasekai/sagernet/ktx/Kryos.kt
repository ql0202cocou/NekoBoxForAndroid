package io.nekohasekai.sagernet.ktx

import com.esotericsoftware.kryo.KryoException
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import java.io.OutputStream

// Kryo allocates char[n] / byte[n] from the varint length before it reads the
// data, so a few crafted bytes in a sn:// link, a QR code or a backup file can
// OOM the process. Beans are always deserialized from an in-memory array, so a
// length can be checked against what is actually left in it first: a UTF-8
// string of n chars takes at least n bytes.
class BoundedByteBufferInput(bytes: ByteArray) : ByteBufferInput(bytes) {

    private fun checkRemaining(length: Int, what: String) {
        val remaining = limit() - position()
        if (length < 0 || length > remaining) {
            throw KryoException("$what length $length exceeds the $remaining bytes left")
        }
    }

    override fun readBytes(length: Int): ByteArray {
        checkRemaining(length, "byte array")
        return super.readBytes(length)
    }

    override fun readString(): String? {
        // readVarIntFlag() only peeks the flag bit. ASCII strings are read byte
        // by byte and need no check; the UTF-8 form carries a char count + 1
        // (0 = null, 1 = empty) that is allocated up front.
        if (readVarIntFlag()) {
            val start = position()
            val count = readVarIntFlag(true)
            setPosition(start)
            if (count > 1) checkRemaining(count - 1, "string")
        }
        return super.readString()
    }
}

fun ByteArray.byteBuffer() = BoundedByteBufferInput(this)
fun OutputStream.byteBuffer() = ByteBufferOutput(this)
