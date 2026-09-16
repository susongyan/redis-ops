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
    public record Description(List<KeyArgument> keys, boolean splittable, Integer destinationDatabase) {
        public Description {
            keys = List.copyOf(keys);
        }
    }
    private CommandKeySemantics() {
    }
    public static Optional<Description> describe(String name, List<byte[]> args) {
        name = name.toUpperCase(Locale.ROOT);
        var keys = new ArrayList<KeyArgument>();
        Integer destinationDatabase = null;
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
            require(args.size() == (name.equals("SMOVE") ? 4 : name.equals("LMOVE") ? 5 : 3));
            if (name.equals("LMOVE")) {
                require(Set.of("LEFT", "RIGHT").contains(text(args.get(3))));
                require(Set.of("LEFT", "RIGHT").contains(text(args.get(4))));
            }
            keys.add(new KeyArgument(1, Role.READ_WRITE));
            keys.add(new KeyArgument(2, Role.READ_WRITE));
        } else if (name.equals("COPY")) {
            require(args.size() >= 3);
            boolean replace = false;
            for (int i = 3; i < args.size(); i++) {
                String option = text(args.get(i));
                if (option.equals("DB")) {
                    require(destinationDatabase == null && ++i < args.size());
                    destinationDatabase = unsignedInteger(args.get(i));
                } else if (option.equals("REPLACE")) {
                    require(!replace);
                    replace = true;
                } else
                    require(false);
            }
            keys.add(new KeyArgument(1, Role.READ));
            keys.add(new KeyArgument(2, Role.WRITE));
        } else if (Set.of("ZUNIONSTORE", "ZINTERSTORE", "ZDIFFSTORE").contains(name)) {
            require(args.size() >= 4);
            int count = unsignedInteger(args.get(2));
            require(count > 0 && count <= args.size() - 3);
            keys.add(new KeyArgument(1, Role.WRITE));
            for (int i = 3; i < 3 + count; i++)
                keys.add(new KeyArgument(i, Role.READ));
            boolean weights = false;
            boolean aggregate = false;
            for (int i = 3 + count; i < args.size(); i++) {
                require(!name.equals("ZDIFFSTORE"));
                String option = text(args.get(i));
                if (option.equals("WEIGHTS")) {
                    require(!weights && count <= args.size() - i - 1);
                    weights = true;
                    for (int weight = 0; weight < count; weight++)
                        require(validWeight(args.get(++i)));
                } else if (option.equals("AGGREGATE")) {
                    require(!aggregate && ++i < args.size());
                    aggregate = true;
                    require(Set.of("SUM", "MIN", "MAX").contains(text(args.get(i))));
                } else
                    require(false);
            }
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
        return Optional.of(new Description(keys, SyncCommandCapabilities.safeSplit(name), destinationDatabase));
    }
    private static int unsignedInteger(byte[] value) {
        require(value.length > 0 && value.length <= 10);
        long result = 0;
        for (byte digit : value) {
            require(digit >= '0' && digit <= '9');
            result = result * 10 + digit - '0';
            require(result <= Integer.MAX_VALUE);
        }
        return (int) result;
    }
    private static boolean validWeight(byte[] value) {
        // Bound scalar parsing; never include command arguments in the resulting error.
        if (value.length == 0 || value.length > 128)
            return false;
        String weight = text(value);
        if (Set.of("INF", "+INF", "-INF").contains(weight))
            return true;
        if (!weight.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:E[+-]?[0-9]+)?"))
            return false;
        try {
            return Double.isFinite(Double.parseDouble(weight));
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.US_ASCII).toUpperCase(Locale.ROOT);
    }
    private static void require(boolean valid) {
        if (!valid)
            throw new IllegalArgumentException("INVALID_COMMAND_KEY_ARGUMENTS");
    }
}
