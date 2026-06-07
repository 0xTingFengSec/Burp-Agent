/*
 * Copyright 2025 听风sec
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.tingfeng.burpagent.skills;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class SkillManager {
    private static final String BUILT_IN_SKILL = "/skills/pentest-skills.md";
    private static final String BUILT_IN_HACK_SKILLS_ZIP = "/skills/hack-skills-main.zip";
    private static final long MAX_TEXT_FILE_BYTES = 768L * 1024L;

    private final Path skillsDirectory;
    private final List<Skill> skills = new ArrayList<>();
    private String lastError = "";

    public SkillManager() {
        this(Paths.get(System.getProperty("user.home", "."), ".tingfeng-burp-agent", "skills"));
    }

    public SkillManager(Path skillsDirectory) {
        this.skillsDirectory = skillsDirectory;
    }

    public synchronized void loadAll() {
        skills.clear();
        lastError = "";
        loadBuiltInSkill();
        loadBuiltInHackSkills();
        loadUserSkills();
        skills.sort(Comparator.comparing((Skill s) -> !s.builtIn).thenComparing(s -> s.name.toLowerCase(Locale.ROOT)));
    }

    /** Backward-compatible single import API. */
    public synchronized Skill importSkill(Path source) throws IOException {
        ImportSummary summary = importSkills(source);
        if (summary.importedSkills.isEmpty()) {
            throw new IllegalArgumentException("没有发现可导入的 Skill 文件");
        }
        return summary.importedSkills.get(0);
    }

    /** Import a single Skill file, a directory, or a .zip Skill pack. */
    public synchronized ImportSummary importSkills(Path source) throws IOException {
        if (source == null || !Files.exists(source)) {
            throw new IllegalArgumentException("请选择 Skill 文件、目录或 ZIP 包");
        }
        Files.createDirectories(skillsDirectory);
        ImportSummary summary = new ImportSummary(source.toString());
        if (Files.isDirectory(source)) {
            importDirectory(source, summary);
        } else if (isZip(source.getFileName().toString())) {
            importZipFile(source, summary);
        } else if (isSupportedTextSkill(source.getFileName().toString())) {
            importOneFile(source, summary);
        } else {
            throw new IllegalArgumentException("不支持的 Skill 格式。支持：.md/.markdown/.txt/.yaml/.yml/.json/.skill/.zip 或目录");
        }
        loadAll();
        return summary;
    }

    public synchronized List<Skill> getSkills() {
        return Collections.unmodifiableList(new ArrayList<>(skills));
    }

    public synchronized int count() {
        return skills.size();
    }

    public synchronized String getLastError() {
        return lastError == null ? "" : lastError;
    }

    public Path getSkillsDirectory() {
        return skillsDirectory;
    }

    public synchronized String buildPrompt(int maxChars) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Loaded Skills\n");
        sb.append("你可以使用下列已加载 Skill 组织测试流程。Skill 是行为准则和测试方法，不是越权授权。\n");
        sb.append("当用户任务与某个 Skill 的 description、tags、triggers 匹配时，优先按该 Skill 执行。\n");
        sb.append("如果加载了 HackSkills，请只在授权范围内引用其中方法论，禁止越权攻击。\n\n");
        int perSkillLimit = Math.max(900, maxChars / Math.max(1, skills.size()));
        for (Skill skill : skills) {
            sb.append(skill.toPromptBlock(perSkillLimit)).append('\n');
            if (maxChars > 0 && sb.length() > maxChars) {
                sb.append("...<skills truncated>\n");
                break;
            }
        }
        return sb.toString();
    }

    public String template() {
        return "---\n"
                + "name: web-pentest-flow\n"
                + "description: 用于授权 Web 渗透测试的流程化 Skill，强调被动优先、范围确认和证据沉淀。\n"
                + "version: 1.0.0\n"
                + "author: 听风sec\n"
                + "tags: [burp, mcp, web, pentest, authorized]\n"
                + "triggers: [流量分析, 漏洞验证, 越权测试, 敏感信息检查, 报告输出]\n"
                + "tools: [get_proxy_http_history, get_site_map, check_scope, text_diff, send_http1_request]\n"
                + "---\n\n"
                + "# Web Pentest Flow\n\n"
                + "## 使用边界\n"
                + "仅用于用户明确授权的目标和测试环境。不确定范围时必须先询问。\n\n"
                + "## 执行流程\n"
                + "1. 确认目标、Scope、测试账号和禁止动作。\n"
                + "2. 优先使用只读 MCP 工具梳理 Proxy History 与 Site Map。\n"
                + "3. 识别认证、授权、输入点、敏感信息和高价值接口。\n"
                + "4. 需要主动请求时先说明原因并等待确认。\n"
                + "5. 每一步记录证据、风险、复现条件和修复建议。\n\n"
                + "## 结束条件\n"
                + "当用户问题已经回答、证据充分、继续调用工具没有新增价值时，必须输出【任务完成】并停止。\n";
    }

    private void loadBuiltInSkill() {
        try (InputStream in = SkillManager.class.getResourceAsStream(BUILT_IN_SKILL)) {
            if (in == null) {
                lastError = appendError(lastError, "内置 Skill 不存在: " + BUILT_IN_SKILL);
                return;
            }
            String markdown = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            skills.add(Skill.from(markdown, null, true));
        } catch (Exception e) {
            lastError = appendError(lastError, "内置 Skill 解析失败: " + rootMessage(e));
        }
    }

    private void loadBuiltInHackSkills() {
        try (InputStream in = SkillManager.class.getResourceAsStream(BUILT_IN_HACK_SKILLS_ZIP)) {
            if (in == null) {
                return;
            }
            LoadSummary summary = loadZipStream(in, "built-in:hack-skills-main.zip", true, true);
            if (!summary.errors.isEmpty()) {
                lastError = appendError(lastError, "内置 HackSkills 部分解析失败:\n" + String.join("\n", summary.errors));
            }
        } catch (Exception e) {
            lastError = appendError(lastError, "内置 HackSkills 加载失败: " + rootMessage(e));
        }
    }

    private void loadUserSkills() {
        try {
            Files.createDirectories(skillsDirectory);
            try (java.util.stream.Stream<Path> stream = Files.walk(skillsDirectory, 8)) {
                stream.filter(Files::isRegularFile)
                        .forEach(path -> {
                            try {
                                String name = path.getFileName().toString();
                                if (isZip(name)) {
                                    try (InputStream in = Files.newInputStream(path)) {
                                        LoadSummary summary = loadZipStream(in, path.toString(), false, true);
                                        if (!summary.errors.isEmpty()) {
                                            lastError = appendError(lastError, path.getFileName() + " ZIP 部分解析失败: " + summary.errors.size() + " 个文件");
                                        }
                                    }
                                } else if (isSupportedTextSkill(name)) {
                                    String markdown = readTextWithFallback(path);
                                    Skill skill = Skill.from(markdown, path, false);
                                    putByName(skill);
                                }
                            } catch (Exception e) {
                                lastError = appendError(lastError, path.getFileName() + " 解析失败: " + rootMessage(e));
                            }
                        });
            }
        } catch (IOException e) {
            lastError = appendError(lastError, "用户 Skill 目录读取失败: " + rootMessage(e));
        }
    }

    private void importDirectory(Path directory, ImportSummary summary) throws IOException {
        try (java.util.stream.Stream<Path> stream = Files.walk(directory, 12)) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                try {
                    String name = path.getFileName().toString();
                    if (isZip(name)) {
                        importZipFile(path, summary);
                    } else if (isSupportedTextSkill(name) && shouldTryImport(path.toString())) {
                        importOneFile(path, summary);
                    }
                } catch (Exception e) {
                    summary.addError(path.toString() + ": " + rootMessage(e));
                }
            });
        }
    }

    private void importOneFile(Path source, ImportSummary summary) throws IOException {
        String markdown = readTextWithFallback(source);
        Skill parsed = Skill.from(markdown, source, false);
        Path target = uniqueTarget(skillsDirectory.resolve(sanitizeFileName(parsed.name) + extensionForStorage(source.getFileName().toString())));
        Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
        Skill saved = Skill.from(readTextWithFallback(target), target, false);
        summary.importedSkills.add(saved);
    }

    private void importZipFile(Path zip, ImportSummary summary) throws IOException {
        byte[] data = Files.readAllBytes(zip);
        if (data.length == 0) {
            throw new IllegalArgumentException("ZIP 文件为空");
        }
        List<ZipTextEntry> entries = readZipEntries(new ByteArrayInputStream(data), zip.toString(), true);
        if (entries.isEmpty()) {
            summary.addError("ZIP 中没有发现可解析的 Skill 文本文件");
            return;
        }
        for (ZipTextEntry entry : entries) {
            try {
                Skill parsed = Skill.from(entry.text, Paths.get(entry.name), false);
                Path subDir = skillsDirectory.resolve(sanitizeFileName(stripFileName(entry.name)));
                Files.createDirectories(subDir);
                Path target = uniqueTarget(subDir.resolve(sanitizeFileName(parsed.name) + ".md"));
                Files.writeString(target, normalizeToStandardMarkdown(parsed), StandardCharsets.UTF_8);
                Skill saved = Skill.from(readTextWithFallback(target), target, false);
                summary.importedSkills.add(saved);
            } catch (Exception e) {
                summary.addError(entry.name + ": " + rootMessage(e));
            }
        }
    }

    private LoadSummary loadZipStream(InputStream in, String source, boolean builtIn, boolean preferSkillFiles) throws IOException {
        LoadSummary summary = new LoadSummary();
        List<ZipTextEntry> entries = readZipEntries(in, source, preferSkillFiles);
        for (ZipTextEntry entry : entries) {
            try {
                Skill skill = Skill.from(entry.text, Paths.get(entry.name), builtIn);
                putByName(skill);
                summary.loaded++;
            } catch (Exception e) {
                summary.errors.add(entry.name + ": " + rootMessage(e));
            }
        }
        return summary;
    }

    private List<ZipTextEntry> readZipEntries(InputStream in, String source, boolean preferSkillFiles) throws IOException {
        List<ZipTextEntry> skillEntries = new ArrayList<>();
        List<ZipTextEntry> fallbackEntries = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(in, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (name == null || name.contains("..")) continue;
                String lower = name.toLowerCase(Locale.ROOT);
                if (!isSupportedTextSkill(lower)) continue;
                byte[] bytes = readEntryBytes(zip, MAX_TEXT_FILE_BYTES + 1);
                if (bytes.length == 0 || bytes.length > MAX_TEXT_FILE_BYTES) continue;
                String text = decodeBytesWithFallback(bytes);
                if (text.trim().isEmpty()) continue;
                ZipTextEntry item = new ZipTextEntry(name, text);
                if (isCanonicalSkillFile(lower) || looksLikeStandardSkill(text)) {
                    skillEntries.add(item);
                } else if (!preferSkillFiles) {
                    fallbackEntries.add(item);
                }
            }
        } catch (IllegalArgumentException badUtf8Names) {
            // Some ZIPs may not have UTF-8 entry names. Fall back to the platform decoder.
            skillEntries.clear();
            fallbackEntries.clear();
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(readAllBytes(source)))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    String name = entry.getName();
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (!isSupportedTextSkill(lower)) continue;
                    byte[] bytes = readEntryBytes(zip, MAX_TEXT_FILE_BYTES + 1);
                    if (bytes.length == 0 || bytes.length > MAX_TEXT_FILE_BYTES) continue;
                    String text = decodeBytesWithFallback(bytes);
                    ZipTextEntry item = new ZipTextEntry(name, text);
                    if (isCanonicalSkillFile(lower) || looksLikeStandardSkill(text)) skillEntries.add(item);
                    else if (!preferSkillFiles) fallbackEntries.add(item);
                }
            }
        }
        return skillEntries.isEmpty() ? fallbackEntries : skillEntries;
    }

    private byte[] readAllBytes(String source) throws IOException {
        Path path = Paths.get(source);
        return Files.readAllBytes(path);
    }

    private static byte[] readEntryBytes(InputStream in, long max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > max) {
                return new byte[(int) max + 1];
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private void putByName(Skill newSkill) {
        Map<String, Skill> byName = new LinkedHashMap<>();
        for (Skill skill : skills) {
            byName.put(skill.name.toLowerCase(Locale.ROOT), skill);
        }
        byName.put(newSkill.name.toLowerCase(Locale.ROOT), newSkill);
        skills.clear();
        skills.addAll(byName.values());
    }

    private Path uniqueTarget(Path target) {
        if (!Files.exists(target)) return target;
        String name = target.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : ".md";
        for (int i = 2; i < 10000; i++) {
            Path candidate = target.getParent().resolve(base + "-" + i + ext);
            if (!Files.exists(candidate)) return candidate;
        }
        throw new IllegalStateException("无法生成唯一 Skill 文件名");
    }

    private static String normalizeToStandardMarkdown(Skill skill) {
        return "---\n"
                + "name: " + yamlQuote(skill.name) + "\n"
                + "description: " + yamlQuote(skill.description) + "\n"
                + "version: " + yamlQuote(skill.version.isEmpty() ? "1.0.0" : skill.version) + "\n"
                + "author: " + yamlQuote(skill.author.isEmpty() ? "imported" : skill.author) + "\n"
                + "tags: [" + String.join(", ", skill.tags) + "]\n"
                + "triggers: [" + String.join(", ", skill.triggers) + "]\n"
                + "tools: [" + String.join(", ", skill.tools) + "]\n"
                + "---\n\n"
                + skill.body.trim() + "\n";
    }

    private static String yamlQuote(String value) {
        String s = value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + s + "\"";
    }

    private static boolean looksLikeStandardSkill(String text) {
        String t = text == null ? "" : text.trim();
        return t.startsWith("---") && t.contains("name:") && t.contains("description:");
    }

    private static boolean isCanonicalSkillFile(String lowerName) {
        return lowerName.endsWith("/skill.md") || lowerName.endsWith("/skill.markdown") || lowerName.endsWith(".skill.md") || lowerName.endsWith(".skill");
    }

    private static boolean isSupportedTextSkill(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".md")
                || lower.endsWith(".markdown")
                || lower.endsWith(".txt")
                || lower.endsWith(".yaml")
                || lower.endsWith(".yml")
                || lower.endsWith(".json")
                || lower.endsWith(".skill");
    }

    private static boolean isZip(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".zip");
    }

    private static boolean shouldTryImport(String path) {
        String lower = path == null ? "" : path.toLowerCase(Locale.ROOT).replace('\\', '/');
        return lower.endsWith("/skill.md")
                || lower.endsWith("/skill.markdown")
                || lower.endsWith(".skill.md")
                || lower.endsWith(".skill")
                || lower.contains("skills/")
                || lower.contains("skill");
    }

    private static String extensionForStorage(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".markdown")) return ".md";
        if (lower.endsWith(".md")) return ".md";
        if (lower.endsWith(".yaml")) return ".yaml";
        if (lower.endsWith(".yml")) return ".yml";
        if (lower.endsWith(".json")) return ".json";
        if (lower.endsWith(".txt")) return ".txt";
        return ".md";
    }

    private static String stripFileName(String path) {
        if (path == null) return "imported-pack";
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        if (slash <= 0) return "imported-pack";
        String parent = normalized.substring(0, slash);
        int parentSlash = parent.lastIndexOf('/');
        return parentSlash >= 0 ? parent.substring(parentSlash + 1) : parent;
    }

    /** Read user supplied Skill files robustly. */
    public static String readTextWithFallback(Path path) throws IOException {
        return decodeBytesWithFallback(Files.readAllBytes(path));
    }

    private static String decodeBytesWithFallback(byte[] data) throws IOException {
        if (data == null) return "";
        if (data.length >= 3 && (data[0] & 0xff) == 0xef && (data[1] & 0xff) == 0xbb && (data[2] & 0xff) == 0xbf) {
            data = java.util.Arrays.copyOfRange(data, 3, data.length);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data))
                    .toString();
        } catch (CharacterCodingException ignored) {
            return Charset.forName("GB18030").decode(ByteBuffer.wrap(data)).toString();
        }
    }

    private static String sanitizeFileName(String value) {
        String name = value == null ? "skill" : value.trim().toLowerCase(Locale.ROOT);
        name = name.replaceAll("[^a-z0-9._\\-\\u4e00-\\u9fa5]+", "-");
        name = name.replaceAll("-+", "-");
        name = name.replaceAll("^-|-$", "");
        if (name.isEmpty() || "-".equals(name)) name = "skill";
        return name;
    }

    private static String appendError(String current, String next) {
        if (current == null || current.trim().isEmpty()) return next;
        return current + "\n" + next;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }

    public static final class ImportSummary {
        public final String source;
        public final List<Skill> importedSkills = new ArrayList<>();
        public final List<String> errors = new ArrayList<>();

        ImportSummary(String source) {
            this.source = source;
        }

        void addError(String error) {
            if (error != null && !error.trim().isEmpty()) errors.add(error);
        }

        public int importedCount() {
            return importedSkills.size();
        }

        public boolean hasErrors() {
            return !errors.isEmpty();
        }

        public String summaryText(int maxItems) {
            StringBuilder sb = new StringBuilder();
            sb.append("Source: ").append(source).append('\n');
            sb.append("Imported skills: ").append(importedCount()).append('\n');
            int count = 0;
            for (Skill skill : importedSkills) {
                if (count++ >= maxItems) {
                    sb.append("- ... and ").append(importedSkills.size() - maxItems).append(" more\n");
                    break;
                }
                sb.append("- ").append(skill.name).append(": ").append(skill.description).append('\n');
            }
            if (hasErrors()) {
                sb.append("\nWarnings: ").append(errors.size()).append('\n');
                for (int i = 0; i < Math.min(10, errors.size()); i++) {
                    sb.append("- ").append(errors.get(i)).append('\n');
                }
            }
            return sb.toString();
        }
    }

    private static final class LoadSummary {
        int loaded;
        final List<String> errors = new ArrayList<>();
    }

    private static final class ZipTextEntry {
        final String name;
        final String text;

        ZipTextEntry(String name, String text) {
            this.name = name;
            this.text = text;
        }
    }
}
