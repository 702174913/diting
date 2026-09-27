package com.diting.service.tingwu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * 按路径读取响应字段的小工具。
 * 只做“取值”，不做任何兜底猜测：取不到就是 null，由调用方决定是报错还是视为缺省。
 */
public final class JsonNodeReader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JsonNode root;

    private JsonNodeReader(JsonNode root) {
        this.root = root;
    }

    public static JsonNodeReader of(Map<String, ?> response) {
        return new JsonNodeReader(MAPPER.valueToTree(response));
    }

    public static JsonNodeReader of(JsonNode node) {
        return new JsonNodeReader(node);
    }

    /** 读取点号路径，例如 Data.Result.Transcription；任一层缺失返回 null */
    public String text(String dottedPath) {
        JsonNode node = node(dottedPath);
        return node == null || node.isNull() || !node.isValueNode() ? null : node.asText();
    }

    public JsonNode node(String dottedPath) {
        JsonNode current = root;
        for (String segment : dottedPath.split("\\.")) {
            if (current == null || current.isNull()) {
                return null;
            }
            current = current.get(segment);
        }
        return current;
    }
}
