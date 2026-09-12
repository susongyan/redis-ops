package io.github.redisops.sync.protocol;

public sealed interface RdbEvent permits RdbEvent.SelectDb, RdbEvent.KeyValue, RdbEvent.FunctionLibrary, RdbEvent.End {
    record SelectDb(int database) implements RdbEvent {
    }
    record KeyValue(int database, byte[] key, long absoluteExpireMillis, byte[] dumpPayload) implements RdbEvent {
        public KeyValue {
            key = key.clone();
            dumpPayload = dumpPayload.clone();
        }
        @Override
        public byte[] key() {
            return key.clone();
        }
        @Override
        public byte[] dumpPayload() {
            return dumpPayload.clone();
        }
    }
    record FunctionLibrary(byte[] payload) implements RdbEvent {
        public FunctionLibrary {
            payload = payload.clone();
        }
        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }
    enum End implements RdbEvent {
        INSTANCE
    }
}
