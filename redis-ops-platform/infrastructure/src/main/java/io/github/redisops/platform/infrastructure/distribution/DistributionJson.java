package io.github.redisops.platform.infrastructure.distribution;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

@Component
public class DistributionJson {
    private final ObjectMapper mapper;
    public DistributionJson(ObjectMapper mapper) {
        this.mapper = mapper;
    }
    public String encode(Object value, int limit) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            OutputStream bounded = new OutputStream() {
                private int count;
                public void write(int b) throws IOException {
                    if (++count > limit)
                        throw new IOException("LIMIT");
                    bytes.write(b);
                }
                public void write(byte[] data, int offset, int length) throws IOException {
                    if (length > limit - count)
                        throw new IOException("LIMIT");
                    count += length;
                    bytes.write(data, offset, length);
                }
            };
            mapper.writeValue(bounded, value);
            return bytes.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalArgumentException("DISTRIBUTION_ENCODING_LIMIT");
        }
    }
    public <T> T decode(String value, Class<T> type) {
        if (value == null || value.length() > 2 * 1024 * 1024)
            throw new IllegalArgumentException("INVALID_DISTRIBUTION_STATE");
        try {
            return mapper.readValue(value, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("INVALID_DISTRIBUTION_STATE");
        }
    }
}
