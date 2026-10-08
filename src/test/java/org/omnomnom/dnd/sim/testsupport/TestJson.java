package org.omnomnom.dnd.sim.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** JSON helpers shared by the cross-language parity tests. */
public final class TestJson {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private TestJson() {}

    public static JsonNode load(String resource) throws IOException {
        try (InputStream in = TestJson.class.getResourceAsStream(resource)) {
            return MAPPER.readTree(in);
        }
    }

    /**
     * JSON with recursively sorted keys and no whitespace; identical to {@code canon} in the TypeScript generators
     * (which also drop {@code undefined}-valued keys; build Java nodes without the corresponding keys).
     */
    public static String canonical(JsonNode n) {
        if (n.isObject()) {
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            n.properties().forEach(e -> sorted.put(e.getKey(), e.getValue()));
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (var e : sorted.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(MAPPER.valueToTree(e.getKey()).toString()).append(':').append(canonical(e.getValue()));
            }
            return sb.append('}').toString();
        }
        if (n.isArray()) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < n.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(canonical(n.get(i)));
            }
            return sb.append(']').toString();
        }
        return n.toString();
    }

    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
