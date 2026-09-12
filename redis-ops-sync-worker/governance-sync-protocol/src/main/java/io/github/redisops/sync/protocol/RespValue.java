package io.github.redisops.sync.protocol;

import java.util.List;

public sealed interface RespValue permits RespValue.Simple, RespValue.Error, RespValue.IntegerValue,
        RespValue.Bulk, RespValue.Array, RespValue.NullValue {
    record Simple(String value) implements RespValue {
    }
    record Error(String value) implements RespValue {
    }
    record IntegerValue(long value) implements RespValue {
    }
    record Bulk(byte[] value) implements RespValue {
        public Bulk {
            value = value.clone();
        }
        @Override
        public byte[] value() {
            return value.clone();
        }
    }
    record Array(List<RespValue> values) implements RespValue {
        public Array {
            values = List.copyOf(values);
        }
    }
    enum NullValue implements RespValue {
        INSTANCE
    }
}
