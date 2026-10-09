package org.omnomnom.dnd.sim.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;

/**
 * Checks JSON against the schemas of {@code docs/api/openapi.yaml}, the contract the service was designed from: every
 * required property present, no property the schema does not declare, values of the declared type, enum, bounds and
 * discriminated variant. A small JSON Schema interpreter covers the subset the contract uses.
 */
public final class OpenApiSchema {

    private OpenApiSchema() {}

    private static final Map<String, Object> SCHEMAS = load();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load() {
        try {
            Map<String, Object> doc = new Yaml().load(Files.readString(Path.of("docs/api/openapi.yaml")));
            return (Map<String, Object>) ((Map<String, Object>) doc.get("components")).get("schemas");
        } catch (IOException e) {
            throw new IllegalStateException("cannot read docs/api/openapi.yaml", e);
        }
    }

    /** The violations of {@code body} against the named component schema (empty when it conforms). */
    public static List<String> violations(JsonNode body, String schemaName) {
        List<String> errors = new ArrayList<>();
        check(body, Map.of("$ref", "#/components/schemas/" + schemaName), schemaName, errors);
        return errors;
    }

    /** Asserts that {@code body} conforms to the named component schema. */
    public static void assertConforms(JsonNode body, String schemaName) {
        assertThat(violations(body, schemaName)).as("%s violations in %s", schemaName, abbreviate(body)).isEmpty();
    }

    // ---- a small JSON Schema interpreter ----------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resolve(Map<String, Object> schema) {
        Object ref = schema.get("$ref");
        if (ref == null) {
            return schema;
        }
        String name = ((String) ref).substring("#/components/schemas/".length());
        Map<String, Object> target = (Map<String, Object>) SCHEMAS.get(name);
        assertThat(target).as("schema " + name).isNotNull();
        return target;
    }

    @SuppressWarnings("unchecked")
    public static void check(JsonNode value, Map<String, Object> schema, String path, List<String> errors) {
        Map<String, Object> s = resolve(schema);
        if (s.containsKey("oneOf")) {
            checkOneOf(value, s, path, errors);
            return;
        }
        if (s.containsKey("const") && !String.valueOf(s.get("const")).equals(value.asString())) {
            errors.add(path + ": expected const " + s.get("const") + " but was " + value);
        }
        Object type = s.get("type");
        if (type != null && !typeMatches(value, type)) {
            errors.add(path + ": expected type " + type + " but was " + value.getNodeType() + " " + abbreviate(value));
            return;
        }
        if (value.isNull()) {
            return;
        }
        if (s.containsKey("enum")) {
            List<Object> allowed = (List<Object>) s.get("enum");
            if (allowed.stream().noneMatch(a -> a != null && String.valueOf(a).equals(value.asString()))) {
                errors.add(path + ": " + value + " is not one of " + allowed);
            }
        }
        if (value.isNumber()) {
            if (s.get("minimum") instanceof Number min && value.asDouble() < min.doubleValue()) {
                errors.add(path + ": " + value + " is below " + min);
            }
            if (s.get("maximum") instanceof Number max && value.asDouble() > max.doubleValue()) {
                errors.add(path + ": " + value + " is above " + max);
            }
        }
        if (value.isObject()) {
            Map<String, Object> props = (Map<String, Object>) s.get("properties");
            List<String> required = (List<String>) s.getOrDefault("required", List.of());
            for (String r : required) {
                if (!value.has(r)) {
                    errors.add(path + ": missing required property " + r);
                }
            }
            Object additional = s.get("additionalProperties");
            for (Map.Entry<String, JsonNode> e : value.properties()) {
                String child = path + "." + e.getKey();
                if (props != null && props.containsKey(e.getKey())) {
                    check(e.getValue(), (Map<String, Object>) props.get(e.getKey()), child, errors);
                } else if (additional instanceof Map<?, ?> m) {
                    check(e.getValue(), (Map<String, Object>) m, child, errors);
                } else if (props != null && !Boolean.TRUE.equals(additional)) {
                    errors.add(child + ": property is not declared in the schema");
                }
            }
        }
        if (value.isArray()) {
            Object items = s.get("items");
            for (int i = 0; items instanceof Map<?, ?> m && i < value.size(); i++) {
                check(value.get(i), (Map<String, Object>) m, path + "[" + i + "]", errors);
            }
            if (s.get("minItems") instanceof Number n && value.size() < n.intValue()) {
                errors.add(path + ": fewer than " + n + " items");
            }
            if (s.get("maxItems") instanceof Number n && value.size() > n.intValue()) {
                errors.add(path + ": more than " + n + " items");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void checkOneOf(JsonNode value, Map<String, Object> s, String path, List<String> errors) {
        List<Map<String, Object>> options = (List<Map<String, Object>>) s.get("oneOf");
        Map<String, Object> discriminator = (Map<String, Object>) s.get("discriminator");
        if (discriminator != null && value.isObject()) {
            String tag = value.path((String) discriminator.get("propertyName")).asString();
            Map<String, String> mapping = (Map<String, String>) discriminator.get("mapping");
            String ref = mapping.get(tag);
            if (ref == null) {
                errors.add(path + ": unknown discriminator value " + tag);
                return;
            }
            check(value, Map.of("$ref", ref), path, errors);
            return;
        }
        int matches = 0;
        List<String> all = new ArrayList<>();
        for (Map<String, Object> option : options) {
            List<String> sub = new ArrayList<>();
            check(value, option, path, sub);
            if (sub.isEmpty()) {
                matches++;
            }
            all.addAll(sub);
        }
        if (matches != 1) {
            errors.add(path + ": matched " + matches + " of the oneOf options; " + all);
        }
    }

    private static boolean typeMatches(JsonNode v, Object type) {
        if (type instanceof List<?> l) {
            return l.stream().anyMatch(t -> typeMatches(v, t));
        }
        return switch ((String) type) {
            case "string" -> v.isString();
            case "integer" -> v.isIntegralNumber();
            case "number" -> v.isNumber();
            case "boolean" -> v.isBoolean();
            case "object" -> v.isObject();
            case "array" -> v.isArray();
            case "null" -> v.isNull();
            default -> throw new IllegalArgumentException("type " + type);
        };
    }

    public static String abbreviate(JsonNode v) {
        String s = v.toString();
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }
}
