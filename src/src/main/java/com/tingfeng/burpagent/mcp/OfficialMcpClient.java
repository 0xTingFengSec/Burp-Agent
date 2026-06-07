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
package com.tingfeng.burpagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal MCP SSE client for the official Burp MCP server.
 *
 * Expected server config:
 * {
 *   "type": "sse",
 *   "url": "http://127.0.0.1:9876"
 * }
 */
public final class OfficialMcpClient implements AutoCloseable {
    private static final String MCP_PROTOCOL_VERSION = "2024-11-05";

    private final ObjectMapper mapper;
    private final AtomicLong idGenerator = new AtomicLong(1);
    private final ConcurrentHashMap<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

    private volatile String sseUrl;
    private volatile URL messageUrl;
    private volatile boolean connected;
    private volatile Thread readerThread;
    private volatile HttpURLConnection sseConnection;
    private volatile ArrayNode toolDefinitions;
    private volatile String lastError;

    private CountDownLatch endpointLatch;

    public OfficialMcpClient(ObjectMapper mapper) {
        this.mapper = mapper;
        this.toolDefinitions = mapper.createArrayNode();
    }

    public synchronized void connect(String rawUrl, int timeoutSeconds) throws Exception {
        String target = normalizeUrl(rawUrl);
        if (connected && target.equals(sseUrl) && messageUrl != null) {
            return;
        }

        close();

        this.sseUrl = target;
        this.connected = false;
        this.lastError = null;
        this.endpointLatch = new CountDownLatch(1);

        URL url = new URL(target);
        readerThread = new Thread(() -> readSseLoop(url), "tingfeng-burp-agent-official-mcp-sse");
        readerThread.setDaemon(true);
        readerThread.start();

        int waitSeconds = Math.max(3, timeoutSeconds);
        if (!endpointLatch.await(waitSeconds, TimeUnit.SECONDS)) {
            throw new IOException("MCP endpoint event not received from " + target + ". Confirm the official Burp MCP SSE server is running at this URL.");
        }
        if (messageUrl == null) {
            throw new IOException("MCP endpoint URL is empty. Check the official MCP server SSE response.");
        }

        initialize(timeoutSeconds);
        refreshTools(timeoutSeconds);
        connected = true;
    }

    public synchronized ArrayNode refreshTools(int timeoutSeconds) throws Exception {
        ObjectNode params = mapper.createObjectNode();
        JsonNode response = request("tools/list", params, timeoutSeconds);
        JsonNode tools = response.path("result").path("tools");
        if (tools.isArray()) {
            toolDefinitions = (ArrayNode) tools;
        } else {
            toolDefinitions = mapper.createArrayNode();
        }
        return toolDefinitions;
    }

    public JsonNode callTool(String toolName, ObjectNode arguments, int timeoutSeconds) throws Exception {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", arguments == null ? mapper.createObjectNode() : arguments);
        return request("tools/call", params, timeoutSeconds);
    }

    public boolean isConnected() {
        return connected && messageUrl != null && readerThread != null && readerThread.isAlive();
    }

    public String getSseUrl() {
        return sseUrl;
    }

    public String getMessageUrl() {
        return messageUrl == null ? "" : messageUrl.toString();
    }

    public String getLastError() {
        return lastError;
    }

    public ArrayNode getToolDefinitions() {
        return toolDefinitions;
    }

    public String buildToolSummary(int maxChars) {
        ArrayNode tools = toolDefinitions;
        if (tools == null || tools.size() == 0) {
            return "No tools loaded yet. The assistant should ask the operator to check the official Burp MCP server.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Available official Burp MCP tools:\n");
        for (JsonNode tool : tools) {
            String name = tool.path("name").asText("");
            String description = tool.path("description").asText("");
            sb.append("- ").append(name);
            if (!description.isEmpty()) {
                sb.append(": ").append(description.replace('\n', ' '));
            }
            JsonNode props = tool.path("inputSchema").path("properties");
            if (props.isObject()) {
                sb.append(" Args: ");
                boolean first = true;
                Iterator<Map.Entry<String, JsonNode>> fields = props.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    if (!first) {
                        sb.append(", ");
                    }
                    first = false;
                    sb.append(entry.getKey()).append(":").append(entry.getValue().path("type").asText("any"));
                }
            }
            sb.append('\n');
            if (sb.length() > maxChars) {
                sb.append("... tool list truncated. Use the exact tool names shown above.\n");
                break;
            }
        }
        return sb.toString();
    }

    @Override
    public synchronized void close() {
        connected = false;
        for (CompletableFuture<JsonNode> future : pending.values()) {
            future.completeExceptionally(new IOException("MCP client closed"));
        }
        pending.clear();
        if (sseConnection != null) {
            sseConnection.disconnect();
            sseConnection = null;
        }
        if (readerThread != null) {
            readerThread.interrupt();
            readerThread = null;
        }
        messageUrl = null;
    }

    private void initialize(int timeoutSeconds) throws Exception {
        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", MCP_PROTOCOL_VERSION);
        params.set("capabilities", mapper.createObjectNode());
        ObjectNode clientInfo = mapper.createObjectNode();
        clientInfo.put("name", "tingfeng-burp-agent");
        clientInfo.put("version", "0.1.0");
        params.set("clientInfo", clientInfo);

        request("initialize", params, timeoutSeconds);

        ObjectNode notification = mapper.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", "notifications/initialized");
        notification.set("params", mapper.createObjectNode());
        postJson(notification, timeoutSeconds);
    }

    private JsonNode request(String method, ObjectNode params, int timeoutSeconds) throws Exception {
        long id = idGenerator.getAndIncrement();
        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.set("params", params == null ? mapper.createObjectNode() : params);

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(String.valueOf(id), future);
        try {
            String directBody = postJson(request, timeoutSeconds);
            if (directBody != null && !directBody.trim().isEmpty()) {
                tryCompleteFromDirectBody(directBody);
            }
            JsonNode response = future.get(Math.max(3, timeoutSeconds), TimeUnit.SECONDS);
            if (response.hasNonNull("error")) {
                throw new IOException("MCP error: " + response.path("error").toString());
            }
            return response;
        } catch (TimeoutException e) {
            throw new IOException("Timed out waiting for MCP response to " + method + " from " + getMessageUrl(), e);
        } finally {
            pending.remove(String.valueOf(id));
        }
    }

    private String postJson(ObjectNode json, int timeoutSeconds) throws IOException {
        URL url = messageUrl;
        if (url == null) {
            throw new IOException("MCP message endpoint is not ready");
        }
        byte[] body = mapper.writeValueAsBytes(json);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(Math.max(3000, timeoutSeconds * 1000));
        conn.setReadTimeout(Math.max(3000, timeoutSeconds * 1000));
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json, text/event-stream");
        conn.setRequestProperty("Content-Length", String.valueOf(body.length));
        try (OutputStream out = conn.getOutputStream()) {
            out.write(body);
        }

        int status = conn.getResponseCode();
        String responseBody = readBody(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
        if (status < 200 || status >= 300) {
            throw new IOException("MCP POST " + url + " returned HTTP " + status + ": " + responseBody);
        }
        return responseBody;
    }

    private void tryCompleteFromDirectBody(String body) {
        String trimmed = body.trim();
        if (!trimmed.startsWith("{")) {
            return;
        }
        try {
            JsonNode node = mapper.readTree(trimmed);
            completeJsonRpcResponse(node);
        } catch (Exception ignored) {
        }
    }

    private void readSseLoop(URL url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            sseConnection = conn;
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(0);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setRequestProperty("Cache-Control", "no-cache");

            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                lastError = "MCP SSE GET returned HTTP " + status + ": " + readBody(conn.getErrorStream());
                CountDownLatch latch = endpointLatch;
                if (latch != null) latch.countDown();
                return;
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String event = null;
                StringBuilder data = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        handleSseEvent(event, data.toString());
                        event = null;
                        data.setLength(0);
                        continue;
                    }
                    if (line.startsWith(":")) {
                        continue;
                    }
                    if (line.startsWith("event:")) {
                        event = line.substring("event:".length()).trim();
                    } else if (line.startsWith("data:")) {
                        if (data.length() > 0) {
                            data.append('\n');
                        }
                        data.append(line.substring("data:".length()).trim());
                    }
                }
            }
        } catch (Exception e) {
            lastError = e.getMessage() == null ? e.toString() : e.getMessage();
            for (CompletableFuture<JsonNode> future : pending.values()) {
                future.completeExceptionally(e);
            }
        } finally {
            connected = false;
            CountDownLatch latch = endpointLatch;
            if (latch != null) latch.countDown();
        }
    }

    private void handleSseEvent(String event, String data) {
        if (data == null || data.trim().isEmpty()) {
            return;
        }
        String type = event == null || event.trim().isEmpty() ? "message" : event.trim();
        if ("endpoint".equals(type)) {
            try {
                messageUrl = resolveEndpointUrl(data.trim());
                CountDownLatch latch = endpointLatch;
                if (latch != null) latch.countDown();
            } catch (Exception e) {
                lastError = "Invalid MCP endpoint event: " + data + " - " + e.getMessage();
                CountDownLatch latch = endpointLatch;
                if (latch != null) latch.countDown();
            }
            return;
        }

        if ("message".equals(type) || data.trim().startsWith("{")) {
            try {
                JsonNode node = mapper.readTree(data.trim());
                completeJsonRpcResponse(node);
            } catch (Exception e) {
                lastError = "Failed to parse MCP SSE message: " + e.getMessage();
            }
        }
    }

    private void completeJsonRpcResponse(JsonNode node) {
        JsonNode id = node.path("id");
        if (!id.isMissingNode() && !id.isNull()) {
            String key = id.isTextual() ? id.asText() : String.valueOf(id.asLong());
            CompletableFuture<JsonNode> future = pending.remove(key);
            if (future != null) {
                future.complete(node);
            }
        }
    }

    private URL resolveEndpointUrl(String rawData) throws Exception {
        String data = rawData.trim();
        if ((data.startsWith("\"") && data.endsWith("\"")) || (data.startsWith("'") && data.endsWith("'"))) {
            data = data.substring(1, data.length() - 1);
        }
        if (data.startsWith("http://") || data.startsWith("https://")) {
            return new URL(data);
        }
        URL base = new URL(sseUrl);
        if (data.startsWith("/")) {
            int port = base.getPort();
            String authority = base.getHost() + (port >= 0 ? ":" + port : "");
            return new URL(base.getProtocol() + "://" + authority + data);
        }
        return new URL(base, data);
    }

    private String normalizeUrl(String rawUrl) {
        String value = rawUrl == null ? "" : rawUrl.trim();
        if (value.isEmpty()) {
            value = "http://127.0.0.1:9876";
        }
        while (value.endsWith("/") && value.length() > "http://x".length()) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private String readBody(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, read);
                if (sb.length() > 4096) {
                    sb.append("...");
                    break;
                }
            }
        }
        return sb.toString();
    }
}
