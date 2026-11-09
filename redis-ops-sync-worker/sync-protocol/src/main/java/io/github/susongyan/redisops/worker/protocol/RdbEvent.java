package io.github.susongyan.redisops.worker.protocol;

/**
 * Parser callbacks for one RDB stream. The parser allocates a fresh array per string it reads
 * ({@code RdbStreamParser.readString} never reuses buffers), so events carry the one and only copy
 * and accessors return it directly — a full-payload defensive copy per access would multiply the
 * heap cost of every large key. Callers therefore must not mutate the arrays behind an event.
 */
public sealed interface RdbEvent permits RdbEvent.SelectDb, RdbEvent.KeyValue, RdbEvent.FunctionLibrary, RdbEvent.End {
    record SelectDb(int database) implements RdbEvent {
    }
    record KeyValue(int database, byte[] key, long absoluteExpireMillis, byte[] dumpPayload) implements RdbEvent {
    }
    record FunctionLibrary(byte[] payload) implements RdbEvent {
    }
    enum End implements RdbEvent {
        INSTANCE
    }
}
