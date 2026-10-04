package eu.nosini.unifiedshim.transport.protocol

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * One wire frame.
 *
 * Layout:  [int32 length][byte type][length-1 bytes body]
 * `length` covers the type byte plus the body. Control bodies are UTF-8 JSON;
 * the PUSH body is binary (see [Protocol]).
 */
class Frame(val type: Byte, val body: ByteArray) {

    companion object {
        /** Hard cap so a hostile/corrupt peer can't make us allocate unbounded. */
        const val MAX_FRAME = 1 shl 20 // 1 MiB

        fun read(input: DataInputStream): Frame {
            val len = input.readInt()
            if (len < 1 || len > MAX_FRAME) throw IOException("bad frame length $len")
            val type = input.readByte()
            val body = ByteArray(len - 1)
            input.readFully(body)
            return Frame(type, body)
        }
    }

    fun write(output: DataOutputStream) {
        output.writeInt(body.size + 1)
        output.writeByte(type.toInt())
        output.write(body)
        output.flush()
    }
}
