package dev.aragonite.papermill;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Just enough protobuf wire encoding for the records in a .note file. */
final class Proto {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    Proto str(int field, String value) {
        return bytes(field, value.getBytes(StandardCharsets.UTF_8));
    }

    Proto bytes(int field, byte[] value) {
        raw((long) field << 3 | 2);
        raw(value.length);
        out.write(value, 0, value.length);
        return this;
    }

    /** Negative values are written sign-extended to 64 bits, as protobuf int32 does. */
    Proto varint(int field, long value) {
        raw((long) field << 3);
        raw(value);
        return this;
    }

    Proto f32(int field, float value) {
        raw((long) field << 3 | 5);
        int bits = Float.floatToIntBits(value);
        for (int i = 0; i < 4; i++) {
            out.write(bits >>> (8 * i) & 0xFF);
        }
        return this;
    }

    byte[] build() {
        return out.toByteArray();
    }

    private void raw(long value) {
        while ((value & ~0x7FL) != 0) {
            out.write((int) (value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write((int) value);
    }
}
