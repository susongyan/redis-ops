import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.susongyan.redisops.platform.domain.distribution.*;
import io.github.susongyan.redisops.platform.infrastructure.distribution.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Run with -Xmx64m; synthetic pages, no Redis credentials or business data. */
public class DistributionMemoryProbe {
    static long heap() throws Exception {
        System.gc(); Thread.sleep(200);
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }
    public static void main(String[] args) throws Exception {
        var rule = new DistributionRule("r", "test", "", DistributionRule.Kind.SEGMENTS, ":", 1);
        var classifier = new DistributionClassifier(List.of(rule));
        var counter = new DistributionCounter(DistributionCounter.Mode.TOP_K, 1000, List.of(rule));
        var json = new DistributionJson(new ObjectMapper());
        long baseline = 0, last = 0;
        for (int page = 0; page < 10000; page++) {
            var bytes = new ByteArrayOutputStream();
            bytes.write("*2\r\n$1\r\n1\r\n*200\r\n".getBytes(StandardCharsets.US_ASCII));
            for (int i = 0; i < 200; i++) {
                var key = ("business-" + (page * 200L + i) + ":id").getBytes(StandardCharsets.UTF_8);
                bytes.write(("$" + key.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
                bytes.write(key); bytes.write(new byte[]{13, 10});
            }
            var received = new BoundedResp(new ByteArrayInputStream(bytes.toByteArray()), () -> {}).scan();
            for (byte[] key : received.keys()) counter.accept(classifier.classify(key));
            if (page % 100 == 0) json.encode(counter.snapshot(), 2 * 1024 * 1024);
            if (page == 999 || page == 4999 || page == 9999) {
                last = heap(); if (baseline == 0) baseline = last;
                System.out.println("observations=" + counter.observed() + " counters=" + counter.groupCount() + " retainedHeapBytes=" + last);
            }
        }
        if (counter.groupCount() != 1000 || last - baseline > 8 * 1024 * 1024)
            throw new AssertionError("Retained heap growth exceeded bounded safety margin");
        System.out.println("PASS: two million distinct groups through RESP, classification, Top-K and bounded encoding under -Xmx64m");
    }
}
