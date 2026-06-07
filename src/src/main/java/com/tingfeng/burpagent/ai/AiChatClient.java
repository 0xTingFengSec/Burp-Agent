/*
 * Copyright 2025 听风sec
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tingfeng.burpagent.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tingfeng.burpagent.http.Json;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

public final class AiChatClient {
    private final ObjectMapper mapper = Json.mapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    public String send(ChatSettings settings, List<ChatMessage> messages) throws IOException, InterruptedException {
        Map<String, Object> body = baseBody(settings, messages);

        HttpRequest.Builder builder = baseRequest(settings)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));

        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("AI endpoint " + settings.endpoint + " returned HTTP " + response.statusCode() + ": " + truncate(response.body(), 800));
        }

        return parseAssistantText(response.body());
    }

    /**
     * Send chat request with OpenAI-compatible streaming enabled.
     *
     * The method supports normal SSE chunks like:
     * data: {"choices":[{"delta":{"content":"..."}}]}
     *
     * If the endpoint ignores stream=true and returns a normal JSON response,
     * it falls back to parsing choices[0].message.content and emits it once.
     */
    public String sendStream(ChatSettings settings, List<ChatMessage> messages, Consumer<String> onDelta) throws IOException, InterruptedException {
        return sendStream(settings, messages, onDelta, () -> false);
    }

    public String sendStream(ChatSettings settings, List<ChatMessage> messages, Consumer<String> onDelta, BooleanSupplier shouldStop) throws IOException, InterruptedException {
        return sendStreamTyped(settings, messages, (type, delta) -> {
            if ("content".equals(type) && onDelta != null) {
                onDelta.accept(delta);
            }
        }, shouldStop);
    }

    /**
     * Streaming chat with typed deltas.
     * type=content means final assistant answer/tool JSON.
     * type=reasoning means model-provided reasoning/thinking content, such as
     * DeepSeek-style reasoning_content or Qwen-style <think>...</think> text.
     */
    public String sendStreamTyped(ChatSettings settings, List<ChatMessage> messages, BiConsumer<String, String> onDelta, BooleanSupplier shouldStop) throws IOException, InterruptedException {
        if (stopped(shouldStop)) {
            throw new InterruptedException("Interrupted by operator");
        }
        Map<String, Object> body = baseBody(settings, messages);
        body.put("stream", true);

        HttpRequest.Builder builder = baseRequest(settings)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));

        HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String errorBody = readAll(response.body());
            throw new IOException("AI endpoint " + settings.endpoint + " returned HTTP " + response.statusCode() + ": " + truncate(errorBody, 800));
        }

        StringBuilder result = new StringBuilder();
        StringBuilder eventData = new StringBuilder();
        StringBuilder rawBody = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (stopped(shouldStop)) {
                    throw new InterruptedException("Interrupted by operator");
                }
                rawBody.append(line).append('\n');

                if (line.trim().isEmpty()) {
                    if (eventData.length() > 0) {
                        boolean done = handleSseData(eventData.toString(), result, onDelta);
                        eventData.setLength(0);
                        if (done) {
                            break;
                        }
                    }
                    continue;
                }

                if (line.startsWith("data:")) {
                    if (eventData.length() > 0) {
                        eventData.append('\n');
                    }
                    eventData.append(line.substring(5).trim());
                }
            }
        }

        if (eventData.length() > 0) {
            handleSseData(eventData.toString(), result, onDelta);
        }

        if (result.length() > 0) {
            return result.toString();
        }

        // Fallback for endpoints that ignore stream=true and return normal JSON.
        String raw = rawBody.toString().trim();
        if (!raw.isEmpty()) {
            String cleaned = raw.replace("data:", "").replace("[DONE]", "").trim();
            if (!cleaned.isEmpty() && cleaned.startsWith("{")) {
                String parsed = parseAssistantText(cleaned);
                emit(onDelta, "content", parsed);
                return parsed;
            }
        }

        return "";
    }

    private boolean stopped(BooleanSupplier shouldStop) {
        return Thread.currentThread().isInterrupted() || (shouldStop != null && shouldStop.getAsBoolean());
    }

    private Map<String, Object> baseBody(ChatSettings settings, List<ChatMessage> messages) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", settings.model);
        body.put("temperature", settings.temperature);
        body.put("messages", toWireMessages(settings, messages));
        return body;
    }

    private HttpRequest.Builder baseRequest(ChatSettings settings) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(settings.endpoint))
                .timeout(Duration.ofSeconds(settings.timeoutSeconds))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");

        if (settings.apiKey != null && !settings.apiKey.trim().isEmpty()) {
            builder.header("Authorization", "Bearer " + settings.apiKey.trim());
        }
        return builder;
    }

    private List<Map<String, String>> toWireMessages(ChatSettings settings, List<ChatMessage> messages) {
        List<Map<String, String>> wire = new ArrayList<>();
        if (settings.systemPrompt != null && !settings.systemPrompt.trim().isEmpty()) {
            wire.add(message("system", settings.systemPrompt));
        }
        for (ChatMessage message : messages) {
            wire.add(message(message.role, message.content));
        }
        return wire;
    }

    private Map<String, String> message(String role, String content) {
        Map<String, String> item = new LinkedHashMap<>();
        item.put("role", role);
        item.put("content", content == null ? "" : content);
        return item;
    }

    private boolean looksLikeSse(String body) {
        return body != null && (body.contains("data:") || body.contains("[DONE]"));
    }

    private boolean looksLikeJsonLine(String line) {
        String value = line == null ? "" : line.trim();
        return value.startsWith("{") && value.endsWith("}");
    }

    private boolean handleSseData(String data, StringBuilder result, BiConsumer<String, String> onDelta) throws IOException {
        String value = data == null ? "" : data.trim();
        if (value.isEmpty()) {
            return false;
        }
        if ("[DONE]".equals(value)) {
            return true;
        }

        JsonNode root;
        try {
            root = mapper.readTree(value);
        } catch (Exception ignored) {
            return false;
        }

        StreamingDelta delta = extractStreamingDelta(root);
        if (delta.reasoning != null && !delta.reasoning.isEmpty()) {
            emit(onDelta, "reasoning", delta.reasoning);
        }
        if (delta.content != null && !delta.content.isEmpty()) {
            result.append(delta.content);
            emit(onDelta, "content", delta.content);
        }
        return false;
    }

    private StreamingDelta extractStreamingDelta(JsonNode root) {
        StreamingDelta out = new StreamingDelta();
        JsonNode choices = root.path("choices");
        if (choices.isArray() && choices.size() > 0) {
            JsonNode first = choices.get(0);
            JsonNode deltaNode = first.path("delta");

            String reasoning = firstNonEmpty(
                    contentAsText(deltaNode.path("reasoning_content")),
                    contentAsText(deltaNode.path("reasoning")),
                    contentAsText(deltaNode.path("thought")),
                    contentAsText(deltaNode.path("thinking")),
                    contentAsText(first.path("reasoning_content")),
                    contentAsText(first.path("reasoning"))
            );
            if (!reasoning.isEmpty()) {
                out.reasoning = reasoning;
            }

            String content = firstNonEmpty(
                    contentAsText(deltaNode.path("content")),
                    contentAsText(first.path("message").path("content")),
                    contentAsText(first.path("text"))
            );
            if (!content.isEmpty()) {
                out.content = content;
            }
            return out;
        }

        String reasoning = firstNonEmpty(
                contentAsText(root.path("reasoning_content")),
                contentAsText(root.path("reasoning")),
                contentAsText(root.path("thinking"))
        );
        if (!reasoning.isEmpty()) {
            out.reasoning = reasoning;
        }

        String content = firstNonEmpty(
                contentAsText(root.path("response")),
                contentAsText(root.path("content")),
                contentAsText(root.path("output"))
        );
        if (!content.isEmpty()) {
            out.content = content;
        }
        return out;
    }

    private String firstNonEmpty(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private static final class StreamingDelta {
        String content = "";
        String reasoning = "";
    }

    private String contentAsText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : node) {
                if (item.isTextual()) {
                    sb.append(item.asText());
                } else {
                    JsonNode text = item.path("text");
                    if (text.isTextual()) sb.append(text.asText());
                    JsonNode content = item.path("content");
                    if (content.isTextual()) sb.append(content.asText());
                }
            }
            return sb.toString();
        }
        return "";
    }

    private String parseAssistantText(String rawJson) throws IOException {
        JsonNode root = mapper.readTree(rawJson);
        JsonNode choices = root.path("choices");
        if (choices.isArray() && choices.size() > 0) {
            String messageContent = contentAsText(choices.get(0).path("message").path("content"));
            if (!messageContent.isEmpty()) {
                return messageContent;
            }
            String text = contentAsText(choices.get(0).path("text"));
            if (!text.isEmpty()) {
                return text;
            }
        }

        String response = contentAsText(root.path("response"));
        if (!response.isEmpty()) {
            return response;
        }
        String content = contentAsText(root.path("content"));
        if (!content.isEmpty()) {
            return content;
        }
        String output = contentAsText(root.path("output"));
        if (!output.isEmpty()) {
            return output;
        }

        throw new IOException("AI response did not contain assistant text");
    }

    private void emit(BiConsumer<String, String> onDelta, String type, String delta) {
        if (onDelta != null && delta != null && !delta.isEmpty()) {
            onDelta.accept(type, delta);
        }
    }

    private String readAll(InputStream input) throws IOException {
        if (input == null) {
            return "";
        }
        try (InputStream in = input) {
            byte[] bytes = in.readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "...";
    }
}
