/*
 * Copyright 2025 听风sec
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.tingfeng.burpagent.skills;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class Skill {
    public final String name;
    public final String description;
    public final String version;
    public final String author;
    public final List<String> tags;
    public final List<String> triggers;
    public final List<String> tools;
    public final String body;
    public final Path source;
    public final boolean builtIn;

    Skill(String name,
          String description,
          String version,
          String author,
          List<String> tags,
          List<String> triggers,
          List<String> tools,
          String body,
          Path source,
          boolean builtIn) {
        this.name = value(name);
        this.description = value(description);
        this.version = value(version);
        this.author = value(author);
        this.tags = immutable(tags);
        this.triggers = immutable(triggers);
        this.tools = immutable(tools);
        this.body = value(body);
        this.source = source;
        this.builtIn = builtIn;
    }

    static Skill from(String markdown, Path source, boolean builtIn) {
        SkillParser.ParsedSkill parsed = SkillParser.parse(markdown, source == null ? "memory" : source.toString());
        Map<String, String> meta = parsed.metadata;
        String name = firstNonBlank(meta.get("name"), meta.get("title"));
        String description = firstNonBlank(meta.get("description"), meta.get("summary"));
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Skill front matter must contain name");
        }
        if (description.isEmpty()) {
            description = "Imported skill: " + name;
        }
        if (parsed.body.trim().isEmpty()) {
            throw new IllegalArgumentException("Skill body is empty");
        }
        return new Skill(
                name,
                description,
                firstNonBlank(meta.get("version"), "1.0.0"),
                firstNonBlank(meta.get("author"), "unknown"),
                SkillParser.list(meta.get("tags")),
                SkillParser.list(firstNonBlank(meta.get("triggers"), meta.get("activation"), meta.get("when_to_use"))),
                SkillParser.list(meta.get("tools")),
                parsed.body.trim(),
                source,
                builtIn
        );
    }

    public String toPromptBlock(int maxBodyChars) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Skill: ").append(name).append('\n');
        sb.append("Description: ").append(description).append('\n');
        if (!version.isEmpty()) sb.append("Version: ").append(version).append('\n');
        if (!author.isEmpty()) sb.append("Author: ").append(author).append('\n');
        if (!tags.isEmpty()) sb.append("Tags: ").append(String.join(", ", tags)).append('\n');
        if (!triggers.isEmpty()) sb.append("When to use: ").append(String.join("；", triggers)).append('\n');
        if (!tools.isEmpty()) sb.append("Preferred MCP tools: ").append(String.join(", ", tools)).append('\n');
        sb.append("Instructions:\n");
        String content = body;
        if (maxBodyChars > 0 && content.length() > maxBodyChars) {
            content = content.substring(0, maxBodyChars) + "\n...<skill truncated>";
        }
        sb.append(content).append('\n');
        return sb.toString();
    }

    private static List<String> immutable(List<String> items) {
        if (items == null || items.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(items));
    }

    private static String value(String value) {
        return value == null ? "" : value.trim();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }
}
