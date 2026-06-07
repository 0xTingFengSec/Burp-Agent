/*
 * Copyright 2025 听风sec
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.tingfeng.burpagent.skills;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SkillParser {
    private SkillParser() {}

    static final class ParsedSkill {
        final Map<String, String> metadata;
        final String body;
        final boolean standardTemplate;

        ParsedSkill(Map<String, String> metadata, String body, boolean standardTemplate) {
            this.metadata = metadata;
            this.body = body;
            this.standardTemplate = standardTemplate;
        }
    }

    static ParsedSkill parse(String markdown, String sourceName) {
        String text = normalize(markdown);
        if (text.startsWith("---\n")) {
            int end = text.indexOf("\n---", 4);
            if (end < 0) {
                throw new IllegalArgumentException("Invalid skill template in " + sourceName + ": YAML front matter is not closed with ---");
            }
            String frontMatter = text.substring(4, end).trim();
            String body = text.substring(end + 4).trim();
            Map<String, String> metadata = parseFrontMatter(frontMatter);
            return new ParsedSkill(metadata, body, true);
        }

        // Compatibility mode: allow common public skill files that are plain Markdown/TXT/YAML/JSON.
        // The plugin wraps them as a valid TingFeng Skill so zip skill packs can be imported directly.
        Map<String, String> metadata = new LinkedHashMap<>();
        String inferred = inferName(sourceName, text);
        metadata.put("name", inferred);
        metadata.put("description", inferDescription(text, inferred));
        metadata.put("version", "1.0.0");
        metadata.put("author", "imported");
        metadata.put("tags", "[imported, skill]");
        return new ParsedSkill(metadata, text.trim(), false);
    }

    static Map<String, String> parseFrontMatter(String frontMatter) {
        Map<String, String> meta = new LinkedHashMap<>();
        String currentKey = null;
        StringBuilder currentValue = new StringBuilder();
        boolean blockScalar = false;

        for (String rawLine : frontMatter.split("\n", -1)) {
            String line = rawLine.replace("\t", "    ");
            if (line.trim().isEmpty() || line.trim().startsWith("#")) continue;

            int colon = line.indexOf(':');
            boolean keyValue = colon > 0 && !Character.isWhitespace(line.charAt(0));
            if (keyValue) {
                if (currentKey != null) {
                    meta.put(currentKey, cleanScalar(currentValue.toString().trim()));
                }
                currentKey = line.substring(0, colon).trim().toLowerCase();
                currentValue.setLength(0);
                String value = line.substring(colon + 1).trim();
                blockScalar = isBlockScalarMarker(value);
                if (!blockScalar) {
                    currentValue.append(value);
                }
                continue;
            }

            if (currentKey != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("- ")) {
                    if (currentValue.length() > 0 && !blockScalar) currentValue.append(", ");
                    if (blockScalar && currentValue.length() > 0) currentValue.append('\n');
                    currentValue.append(trimmed.substring(2).trim());
                } else {
                    if (currentValue.length() > 0) currentValue.append(blockScalar ? '\n' : ' ');
                    currentValue.append(trimmed);
                }
            }
        }
        if (currentKey != null) {
            meta.put(currentKey, cleanScalar(currentValue.toString().trim()));
        }
        return meta;
    }

    static List<String> list(String value) {
        if (value == null || value.trim().isEmpty()) return Collections.emptyList();
        String text = cleanScalar(value.trim());
        if (text.startsWith("[") && text.endsWith("]")) {
            text = text.substring(1, text.length() - 1);
        }
        String[] parts = text.split(",");
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            String item = cleanScalar(part.trim());
            if (!item.isEmpty()) result.add(item);
        }
        return result;
    }

    private static String normalize(String value) {
        String text = value == null ? "" : value;
        if (!text.isEmpty() && text.charAt(0) == '\ufeff') {
            text = text.substring(1);
        }
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static boolean isBlockScalarMarker(String value) {
        if (value == null) return false;
        String v = value.trim();
        return v.equals(">") || v.equals(">-") || v.equals(">+") || v.equals("|") || v.equals("|-") || v.equals("|+");
    }

    private static String cleanScalar(String value) {
        if (value == null) return "";
        String text = value.trim();
        if (isBlockScalarMarker(text)) return "";
        if ((text.startsWith("\"") && text.endsWith("\"")) || (text.startsWith("'") && text.endsWith("'"))) {
            return text.substring(1, text.length() - 1).trim();
        }
        return text;
    }

    private static String inferName(String sourceName, String body) {
        String heading = firstHeading(body);
        if (!heading.isEmpty()) return slug(heading);
        String source = sourceName == null ? "imported-skill" : sourceName;
        int slash = Math.max(source.lastIndexOf('/'), source.lastIndexOf('\\'));
        if (slash >= 0) source = source.substring(slash + 1);
        int dot = source.lastIndexOf('.');
        if (dot > 0) source = source.substring(0, dot);
        return slug(source);
    }

    private static String inferDescription(String body, String fallback) {
        String heading = firstHeading(body);
        if (!heading.isEmpty()) return heading;
        if (body != null) {
            for (String line : body.split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("---")) {
                    return trimmed.length() > 160 ? trimmed.substring(0, 160) + "..." : trimmed;
                }
            }
        }
        return "Imported skill: " + fallback;
    }

    private static String firstHeading(String body) {
        if (body == null) return "";
        for (String line : body.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                return trimmed.replaceFirst("^#+\\s*", "").trim();
            }
        }
        return "";
    }

    private static String slug(String value) {
        String text = value == null ? "skill" : value.trim().toLowerCase();
        text = text.replaceAll("[^a-z0-9._\\-\\u4e00-\\u9fa5]+", "-");
        text = text.replaceAll("-+", "-");
        text = text.replaceAll("^-|-$", "");
        if (text.isEmpty()) return "imported-skill";
        return text;
    }
}
