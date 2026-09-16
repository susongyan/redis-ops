package io.github.susongyan.redisops.worker.protocol;

import java.nio.charset.StandardCharsets;
import java.util.*;
import io.github.susongyan.redisops.sync.contract.SyncCommandCapabilities;

/** Worker-owned key positions and roles. Recognizing a command does NOT authorize applying it. */
public final class CommandKeySemantics {
    public enum Role {
        READ, WRITE, READ_WRITE
    }
    public record KeyArgument(int index, Role role) {
    }
    public record Description(List<KeyArgument> keys, boolean splittable) {
        public Description {
            keys = List.copyOf(keys);
        }
    }
    private CommandKeySemantics() {
    }
    public static Optional<Description> describe(String name, List<byte[]> args) {
        name = name.toUpperCase(Locale.ROOT);
        var keys = new ArrayList<KeyArgument>();
        if (name.equals("BITOP")) {
            require(args.size() >= 4);
            String op = text(args.get(1));
            require(Set.of("AND", "OR", "XOR", "NOT").contains(op));
            require(!op.equals("NOT") || args.size() == 4);
            keys.add(new KeyArgument(2, Role.WRITE));
            for (int i = 3; i < args.size(); i++)
                keys.add(new KeyArgument(i, Role.READ));
        } else if (Set.of("SUNIONSTORE", "SINTERSTORE", "SDIFFSTORE", "PFMERGE").contains(name)) {
            require(args.size() >= 3);
            keys.add(new KeyArgument(1, Role.WRITE));
            for (int i = 2; i < args.size(); i++)
                keys.add(new KeyArgument(i, Role.READ));
        } else if (Set.of("RENAME", "RENAMENX", "SMOVE", "LMOVE", "RPOPLPUSH").contains(name)) {
            require(args.size() >= 3);
            keys.add(new KeyArgument(1, Role.READ_WRITE));
            keys.add(new KeyArgument(2, Role.READ_WRITE));
        } else if (name.equals("MSET") || name.equals("MSETNX")) {
            require(args.size() >= 3 && args.size() % 2 == 1);
            for (int i = 1; i < args.size(); i += 2)
                keys.add(new KeyArgument(i, name.equals("MSETNX") ? Role.READ_WRITE : Role.WRITE));
        } else if (name.equals("DEL") || name.equals("UNLINK")) {
            require(args.size() >= 2);
            for (int i = 1; i < args.size(); i++)
                keys.add(new KeyArgument(i, Role.WRITE));
        } else if (SyncCommandCapabilities.singleKey(name)) {
            int index = 1;
            if (name.equals("XGROUP")) {
                require(args.size() >= 4);
                require(Set.of("CREATE", "SETID", "DESTROY", "CREATECONSUMER", "DELCONSUMER")
                        .contains(text(args.get(1))));
                index = 2;
            }
            require(args.size() > index);
            keys.add(new KeyArgument(index, Role.READ_WRITE));
        } else
            return Optional.empty();
        return Optional.of(new Description(keys, SyncCommandCapabilities.safeSplit(name)));
    }
    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.US_ASCII).toUpperCase(Locale.ROOT);
    }
    private static void require(boolean valid) {
        if (!valid)
            throw new IllegalArgumentException("INVALID_COMMAND_KEY_ARGUMENTS");
    }
}
