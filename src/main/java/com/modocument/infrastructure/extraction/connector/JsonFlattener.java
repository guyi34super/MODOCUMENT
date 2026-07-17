package com.modocument.infrastructure.extraction.connector;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns API JSON into flat string records: the array at a dot-path becomes
 * one record per element, nested objects flatten to dotted keys, nested
 * arrays are kept as compact JSON strings.
 */
public final class JsonFlattener {

    private JsonFlattener() {
    }

    /** Node at a dot-path ("data.items"); empty/null path = root. */
    public static JsonNode atPath(JsonNode root, String dotPath) {
        if (root == null || dotPath == null || dotPath.isBlank()) {
            return root;
        }
        JsonNode node = root;
        for (String part : dotPath.split("\\.")) {
            if (node == null) {
                return null;
            }
            node = node.get(part);
        }
        return node;
    }

    public static List<Map<String, String>> records(JsonNode root, String recordsPath) {
        JsonNode node = atPath(root, recordsPath);
        List<Map<String, String>> records = new ArrayList<>();
        if (node == null) {
            return records;
        }
        if (node.isArray()) {
            for (JsonNode element : node) {
                records.add(flatten(element));
            }
        } else {
            records.add(flatten(node));
        }
        return records;
    }

    public static Map<String, String> flatten(JsonNode node) {
        Map<String, String> flat = new LinkedHashMap<>();
        flattenInto(node, "", flat);
        return flat;
    }

    private static void flattenInto(JsonNode node, String prefix, Map<String, String> flat) {
        if (node == null || node.isNull()) {
            if (!prefix.isEmpty()) {
                flat.put(prefix, "");
            }
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> flattenInto(entry.getValue(),
                    prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey(), flat));
        } else if (node.isArray()) {
            flat.put(prefix.isEmpty() ? "value" : prefix, node.toString());
        } else {
            flat.put(prefix.isEmpty() ? "value" : prefix, node.asText());
        }
    }
}
