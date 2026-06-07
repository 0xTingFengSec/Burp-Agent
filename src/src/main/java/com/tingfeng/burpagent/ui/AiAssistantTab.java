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
package com.tingfeng.burpagent.ui;

import burp.api.montoya.MontoyaApi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tingfeng.burpagent.ai.AiChatClient;
import com.tingfeng.burpagent.ai.ChatMessage;
import com.tingfeng.burpagent.ai.ChatSettings;
import com.tingfeng.burpagent.http.Json;
import com.tingfeng.burpagent.mcp.OfficialMcpClient;
import com.tingfeng.burpagent.skills.Skill;
import com.tingfeng.burpagent.skills.SkillManager;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.border.AbstractBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.lang.reflect.Method;
import java.awt.Desktop;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AiAssistantTab extends JPanel {
    private static final String PREF_ENDPOINT = "tingfeng.burp.agent.chat.endpoint";
    private static final String PREF_MODEL = "tingfeng.burp.agent.chat.model";
    private static final String PREF_API_KEY = "tingfeng.burp.agent.chat.apiKey";
    private static final String PREF_TEMPERATURE = "tingfeng.burp.agent.chat.temperature";
    private static final String PREF_TIMEOUT = "tingfeng.burp.agent.chat.timeout";
    private static final String PREF_SAVE_KEY = "tingfeng.burp.agent.chat.saveApiKey";
    private static final String PREF_CONFIRM = "tingfeng.burp.agent.chat.promptBeforeDangerousTools";
    private static final String PREF_MCP_URL = "tingfeng.burp.agent.officialMcpUrl";

    private static final int QUOTE_ROTATE_MS = 4200;
    private static final int MAX_AGENT_TOOL_CALLS = 15;
    private static final int MAX_FORMAT_REPAIRS = 2;
    private static final int MAX_REPEAT_SAME_TOOL = 3;
    private static final Pattern JSON_BLOCK = Pattern.compile("```(?:json|jsonc)?\\s*([\\s\\S]*?)\\s*```", Pattern.CASE_INSENSITIVE);

    private static final String[] HACKER_QUOTES = {
            "所谓安全，不是一件产品，而是一个持续的过程。—— Bruce Schneier",
            "你越安静，越能听见系统真正发出的声音。—— 安全箴言",
            "默认不信任，验证每一次访问。—— 零信任原则",
            "最强的防御，来自对资产、对手和自己的清醒认知。—— 蓝队箴言",
            "攻击者按路径思考，防守者按资产治理。—— 安全工程实践",
            "真正的安全不是没有漏洞，而是能持续发现、验证和修复。—— 听风sec"
    };

    private final MontoyaApi api;
    private final ObjectMapper mapper;
    private final AiChatClient aiClient;
    private final OfficialMcpClient mcpClient;
    private final SkillManager skillManager;
    private final List<ChatMessage> conversation;

    private final JTextField endpointField;
    private final JTextField modelField;
    private final JPasswordField apiKeyField;
    private final JTextField mcpUrlField;
    private final JTextField temperatureField;
    private final JTextField timeoutField;
    private final JCheckBox saveKeyCheck;
    private final JCheckBox confirmDangerousCheck;
    private final JPanel chatList;
    private final JPanel toolList;
    private final JTextArea inputArea;
    private final JButton sendButton;
    private final JButton saveButton;
    private final JButton clearButton;
    private final JButton connectButton;
    private final JButton refreshToolsButton;
    private final JButton importSkillButton;
    private final JButton reloadSkillsButton;
    private final JButton skillTemplateButton;
    private final JButton openSkillsDirButton;
    private final JButton configToolsButton;
    private final JButton stopButton;
    private final JLabel quoteLabel;
    private final JLabel skillStatusLabel;
    private final JLabel endpointStatusLabel;
    private final JLabel mcpStatusLabel;
    private volatile boolean stopRequested;
    private volatile SwingWorker<?, ?> activeWorker;
    private int quoteIndex;
    private int toolCallCount;
    private int formatRepairCount;
    private String lastToolSignature;
    private int repeatedToolCount;

    public AiAssistantTab(MontoyaApi api) {
        this.api = api;
        this.mapper = Json.mapper();
        this.aiClient = new AiChatClient();
        this.mcpClient = new OfficialMcpClient(mapper);
        this.skillManager = new SkillManager();
        this.skillManager.loadAll();
        this.conversation = new ArrayList<>();

        this.endpointField = new JTextField(nonBlank(prefString(PREF_ENDPOINT, ""), "http://127.0.0.1:11434/v1/chat/completions"));
        this.modelField = new JTextField(prefString(PREF_MODEL, "qwen2.5-coder:7b"));
        this.apiKeyField = new JPasswordField(prefString(PREF_API_KEY, ""));
        this.mcpUrlField = new JTextField(nonBlank(prefString(PREF_MCP_URL, ""), "http://127.0.0.1:9876"));
        this.temperatureField = new JTextField(prefString(PREF_TEMPERATURE, "0.2"));
        this.timeoutField = new JTextField(prefString(PREF_TIMEOUT, "1800"));
        this.saveKeyCheck = new JCheckBox("Save key", prefBoolean(PREF_SAVE_KEY, false));
        this.confirmDangerousCheck = new JCheckBox("Confirm active tools", prefBoolean(PREF_CONFIRM, true));
        this.chatList = verticalPanel();
        this.toolList = verticalPanel();
        this.inputArea = new JTextArea(4, 40);
        this.sendButton = new JButton("Send");
        this.saveButton = new JButton("保存");
        this.clearButton = new JButton("清空");
        this.connectButton = new JButton("连接官方 MCP");
        this.refreshToolsButton = new JButton("刷新工具");
        this.importSkillButton = new JButton("导入 Skill / ZIP");
        this.reloadSkillsButton = new JButton("重载 Skills");
        this.skillTemplateButton = new JButton("模板");
        this.openSkillsDirButton = new JButton("目录");
        this.configToolsButton = new JButton("配置 / Skills");
        this.stopButton = new JButton("中断");
        this.quoteLabel = new JLabel();
        this.skillStatusLabel = new JLabel();
        this.endpointStatusLabel = new JLabel();
        this.mcpStatusLabel = new JLabel();
        this.quoteIndex = 0;
        this.stopRequested = false;
        this.activeWorker = null;
        this.toolCallCount = 0;
        this.formatRepairCount = 0;
        this.lastToolSignature = "";
        this.repeatedToolCount = 0;

        applyBurpThemeCompat(this);
        build();
        bindActions();
        updateEndpointLabels();
        startQuoteCarousel();
    }

    private void build() {
        setLayout(new BorderLayout(16, 16));
        setBorder(new EmptyBorder(18, 18, 18, 18));
        setBackground(appBackground());

        add(buildHeroPanel(), BorderLayout.NORTH);

        JPanel config = buildConfigPanel();
        JPanel chat = buildChatPanel();
        JPanel tools = buildToolPanel();

        JSplitPane rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, chat, tools);
        rightSplit.setResizeWeight(0.80);
        rightSplit.setDividerSize(9);
        rightSplit.setBorder(BorderFactory.createEmptyBorder());
        rightSplit.setOpaque(false);

        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, config, rightSplit);
        mainSplit.setResizeWeight(0.14);
        mainSplit.setDividerSize(9);
        mainSplit.setBorder(BorderFactory.createEmptyBorder());
        mainSplit.setOpaque(false);

        add(mainSplit, BorderLayout.CENTER);
    }

    private JPanel buildHeroPanel() {
        GradientPanel hero = new GradientPanel();
        hero.setLayout(new BorderLayout(18, 12));
        hero.setBorder(new EmptyBorder(20, 22, 20, 22));

        JPanel titleBox = new JPanel();
        titleBox.setOpaque(false);
        titleBox.setLayout(new BoxLayout(titleBox, BoxLayout.Y_AXIS));

        JLabel title = new JLabel("听风 Burp Agent");
        title.setForeground(Color.WHITE);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 27f));

        updateQuoteText();
        quoteLabel.setForeground(new Color(255, 241, 226));
        quoteLabel.setFont(quoteLabel.getFont().deriveFont(Font.BOLD, 13f));
        quoteLabel.setBorder(new EmptyBorder(7, 0, 0, 0));

        JLabel brand = new JLabel("听风sec  ·  微信：Yunaaaa8888");
        brand.setForeground(new Color(255, 226, 205));
        brand.setFont(brand.getFont().deriveFont(Font.PLAIN, 12f));
        brand.setBorder(new EmptyBorder(6, 0, 0, 0));

        titleBox.add(title);
        titleBox.add(quoteLabel);
        titleBox.add(brand);

        JPanel badges = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        badges.setOpaque(false);
        badges.add(createHeroBadge("● Official MCP", "SSE"));
        badges.add(createHeroBadge("● Burp Agent", "Connected"));
        badges.add(createHeroBadge("● Local Server", "Disabled"));

        hero.add(titleBox, BorderLayout.WEST);
        hero.add(badges, BorderLayout.EAST);

        return hero;
    }

    private JPanel buildConfigPanel() {
        JPanel panel = roundedPanel();
        panel.setLayout(new BorderLayout(12, 14));
        panel.setPreferredSize(new Dimension(255, 560));

        panel.add(sectionHeader("控制台", "所有配置、API、MCP 与 Skills 统一在设置中管理"), BorderLayout.NORTH);

        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));

        stylePrimaryButton(configToolsButton);
        configToolsButton.setText("设置");
        configToolsButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        configToolsButton.setAlignmentX(Component.LEFT_ALIGNMENT);

        styleSecondaryButton(connectButton);
        connectButton.setText("连接官方 MCP");
        connectButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        connectButton.setAlignmentX(Component.LEFT_ALIGNMENT);

        styleSecondaryButton(refreshToolsButton);
        refreshToolsButton.setText("刷新工具");
        refreshToolsButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        refreshToolsButton.setAlignmentX(Component.LEFT_ALIGNMENT);

        styleSecondaryButton(clearButton);
        clearButton.setText("清空对话");
        clearButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        clearButton.setAlignmentX(Component.LEFT_ALIGNMENT);

        body.add(createMiniStatusCard("AI 配置", "点击【设置】配置 API 地址、模型和密钥"));
        body.add(Box.createVerticalStrut(10));
        body.add(createMiniStatusCard("官方 MCP", "点击【设置】配置 SSE 地址；默认 127.0.0.1:9876"));
        body.add(Box.createVerticalStrut(10));
        body.add(createMiniStatusCard("Skills", "当前已加载 " + skillManager.count() + " 个；可在设置中导入/重载"));
        body.add(Box.createVerticalStrut(16));
        body.add(configToolsButton);
        body.add(Box.createVerticalStrut(10));
        body.add(connectButton);
        body.add(Box.createVerticalStrut(10));
        body.add(refreshToolsButton);
        body.add(Box.createVerticalStrut(10));
        body.add(clearButton);
        body.add(Box.createVerticalGlue());

        JLabel brand = new JLabel("听风sec  ·  微信：Yunaaaa8888");
        brand.setForeground(mutedTextColor());
        brand.setFont(brand.getFont().deriveFont(Font.PLAIN, 12f));
        brand.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(brand);

        panel.add(body, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildChatPanel() {
        JPanel panel = roundedPanel();
        panel.setLayout(new BorderLayout(12, 12));
        panel.add(sectionHeader("Chat 工作区", "流式对话 · AI 自动通过官方 Burp MCP 调用工具"), BorderLayout.NORTH);

        chatList.setBorder(new EmptyBorder(8, 8, 8, 8));
        chatList.setBackground(appBackground());

        JScrollPane scroll = new JScrollPane(chatList);
        scroll.setBorder(new RoundedBorder(borderColor(), 14));
        scroll.getViewport().setBackground(appBackground());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        panel.add(scroll, BorderLayout.CENTER);

        JPanel composer = new JPanel(new BorderLayout(10, 10));
        composer.setOpaque(false);
        composer.setBorder(new EmptyBorder(4, 0, 0, 0));

        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(true);
        inputArea.setFont(UIManager.getFont("TextArea.font").deriveFont(13f));
        inputArea.setBackground(inputBackground());
        inputArea.setForeground(textColor());
        inputArea.setCaretColor(textColor());
        inputArea.setBorder(new EmptyBorder(12, 12, 12, 12));

        JScrollPane inputScroll = new JScrollPane(inputArea);
        inputScroll.setPreferredSize(new Dimension(100, 132));
        inputScroll.setBorder(new RoundedBorder(borderColor(), 16));

        stylePrimaryButton(sendButton);
        sendButton.setPreferredSize(new Dimension(104, 44));
        styleDangerButton(stopButton);
        stopButton.setPreferredSize(new Dimension(104, 44));
        stopButton.setEnabled(false);

        JPanel actionButtons = new JPanel();
        actionButtons.setOpaque(false);
        actionButtons.setLayout(new BoxLayout(actionButtons, BoxLayout.Y_AXIS));
        actionButtons.add(sendButton);
        actionButtons.add(Box.createVerticalStrut(8));
        actionButtons.add(stopButton);

        composer.add(inputScroll, BorderLayout.CENTER);
        composer.add(actionButtons, BorderLayout.EAST);
        panel.add(composer, BorderLayout.SOUTH);

        addChatBubble("assistant", "Ready. 已切换为官方 MCP 模式。你可以问：查看可用 MCP 工具、查看最近 20 条代理流量、把这个请求发包并读取响应、分析登录接口等。\n\nAPI Endpoint、模型、密钥、MCP 和 Skills 都在左侧【设置】里统一配置。");
        return panel;
    }

    private JPanel buildToolPanel() {
        JPanel panel = roundedPanel();
        panel.setLayout(new BorderLayout(12, 12));
        panel.add(sectionHeader("Official MCP Calls", "工具调用轨迹、返回结果和 Agent 执行链"), BorderLayout.NORTH);

        toolList.setBorder(new EmptyBorder(8, 8, 8, 8));
        toolList.setBackground(appBackground());

        JScrollPane scroll = new JScrollPane(toolList);
        scroll.setBorder(new RoundedBorder(borderColor(), 14));
        scroll.getViewport().setBackground(appBackground());
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        panel.add(scroll, BorderLayout.CENTER);
        addToolCard("system", "idle", true, "No official MCP calls yet.");
        return panel;
    }

    private void bindActions() {
        saveButton.addActionListener(event -> saveSettings());
        clearButton.addActionListener(event -> {
            conversation.clear();
            chatList.removeAll();
            toolList.removeAll();
            addChatBubble("assistant", "Conversation cleared.");
            addToolCard("system", "idle", true, "Tool log cleared.");
            refresh(chatList);
            refresh(toolList);
        });
        connectButton.addActionListener(event -> connectOfficialMcp());
        refreshToolsButton.addActionListener(event -> refreshOfficialMcpTools());
        importSkillButton.addActionListener(event -> importSkillFile());
        reloadSkillsButton.addActionListener(event -> reloadSkills());
        skillTemplateButton.addActionListener(event -> showSkillTemplate());
        openSkillsDirButton.addActionListener(event -> openSkillsDirectory());
        configToolsButton.addActionListener(event -> showConfigToolsDialog());
        endpointField.addActionListener(event -> updateEndpointLabels());
        mcpUrlField.addActionListener(event -> updateEndpointLabels());
        sendButton.addActionListener(event -> sendChat());
        stopButton.addActionListener(event -> requestStop());
    }

    private void sendChat() {
        String userText = inputArea.getText().trim();
        if (userText.isEmpty()) {
            return;
        }

        ChatSettings settings;
        try {
            settings = readSettings();
        } catch (RuntimeException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Invalid AI Settings", JOptionPane.ERROR_MESSAGE);
            return;
        }

        inputArea.setText("");
        stopRequested = false;
        toolCallCount = 0;
        formatRepairCount = 0;
        lastToolSignature = "";
        repeatedToolCount = 0;
        setBusy(true);
        conversation.add(new ChatMessage("user", userText));
        addChatBubble("user", userText);

        ThoughtStreamUi streamUi = addThoughtStreamingBubbles();

        SwingWorker<String, Void> worker = new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                ensureNotStopped();
                ensureOfficialMcp(settings.timeoutSeconds);
                ensureNotStopped();
                settings.systemPrompt = defaultSystemPrompt(mcpClient.buildToolSummary(12000));
                return aiClient.sendStreamTyped(settings, conversation, (type, delta) -> appendTypedStreamDelta(streamUi, type, delta), AiAssistantTab.this::isCancelledOrStopped);
            }

            @Override
            protected void done() {
                try {
                    if (isCancelledOrStopped()) {
                        updateStreamBubble(streamUi.answerBubble, "已中断当前 AI 任务。");
                        finalizeThoughtBubble(streamUi);
                        finishBusy();
                        return;
                    }
                    String assistant = finalAnswerFromStream(streamUi, get());
                    updateStreamBubble(streamUi.answerBubble, assistant);
                    finalizeThoughtBubble(streamUi);
                    conversation.add(new ChatMessage("assistant", assistant));
                    boolean startedTool = handleMcpToolCallIfPresent(assistant, settings, 1);
                    if (!startedTool) {
                        finishBusy();
                    }
                } catch (CancellationException e) {
                    updateStreamBubble(streamUi.answerBubble, "已中断当前 AI 任务。");
                    finalizeThoughtBubble(streamUi);
                    finishBusy();
                } catch (Exception e) {
                    String message = isCancelledOrStopped() ? "已中断当前 AI 任务。" : "AI or official MCP request failed: " + rootMessage(e);
                    updateStreamBubble(streamUi.answerBubble, message);
                    finalizeThoughtBubble(streamUi);
                    if (!isCancelledOrStopped()) {
                        addToolCard("mcp", "connect/call", false, rootMessage(e));
                    }
                    finishBusy();
                }
            }
        };
        startWorker(worker);
    }

    private boolean handleMcpToolCallIfPresent(String assistantText, ChatSettings settings, int depth) {
        if (isCancelledOrStopped()) {
            finishBusy();
            return true;
        }

        if (isTerminalAssistantMessage(assistantText)) {
            return false;
        }

        McpToolCall call;
        try {
            call = parseMcpToolCall(assistantText);
        } catch (Exception e) {
            if (looksLikeToolIntent(assistantText)) {
                return requestFormatRepair(settings, assistantText, "工具调用 JSON 解析失败：" + rootMessage(e));
            }
            return false;
        }

        if (call == null || call.name == null || call.name.trim().isEmpty()) {
            if (looksLikeToolIntent(assistantText)) {
                return requestFormatRepair(settings, assistantText, "检测到疑似工具调用，但缺少 name 字段或格式不完整");
            }
            return false;
        }

        call.name = resolveOfficialToolName(call.name.trim());
        normalizeArgumentsForTool(call);

        if (!officialToolExists(call.name)) {
            return requestFormatRepair(settings, assistantText,
                    "工具名不存在：" + call.name + "。必须从 Official MCP tools/list 返回的 name 中选择。可用工具包括：" + compactToolNames(60));
        }

        if (toolCallCount >= MAX_AGENT_TOOL_CALLS) {
            addChatBubble("assistant", "【任务暂停】\n\n本轮 Agent 已达到最大工具调用次数 " + MAX_AGENT_TOOL_CALLS
                    + " 次。为避免无限循环，已自动停止。\n\n请确认是否继续深度分析，或缩小目标范围后重新发起任务。");
            finishBusy();
            return true;
        }

        String signature = call.name + ":" + call.arguments.toString();
        if (signature.equals(lastToolSignature)) {
            repeatedToolCount++;
        } else {
            lastToolSignature = signature;
            repeatedToolCount = 1;
        }
        if (repeatedToolCount >= MAX_REPEAT_SAME_TOOL) {
            addChatBubble("assistant", "【任务暂停】\n\n检测到连续重复调用同一个 MCP 工具且参数相同：" + call.name
                    + "。为避免无意义循环，已自动停止。\n\n请调整问题或确认是否继续。");
            finishBusy();
            return true;
        }

        if (settings.promptBeforeDangerousTools && isDangerousTool(call.name)) {
            int choice = JOptionPane.showConfirmDialog(
                    this,
                    "Run active official MCP tool call?\n\n" + call.name + "\n" + safe(call.comment),
                    "Confirm Official MCP Tool Call",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE
            );
            if (choice != JOptionPane.YES_OPTION) {
                addToolCard("official-mcp", call.name, false, "Cancelled by user.");
                conversation.add(new ChatMessage("user", "【等待确认】Official MCP tool call was cancelled by the operator. Do not continue active testing unless the operator confirms."));
                finishBusy();
                return true;
            }
            // Do not pass UI confirmation flags to the official PortSwigger MCP server.
            // Its Kotlin serializers reject unknown keys such as "confirmed".
        }

        toolCallCount++;
        addToolCard("official-mcp", call.name, true,
                "Running official MCP tool call [" + toolCallCount + "/" + MAX_AGENT_TOOL_CALLS + "]: " + call.name);
        setBusy(true);

        McpToolCall finalCall = call;
        SwingWorker<JsonNode, Void> worker = new SwingWorker<JsonNode, Void>() {
            @Override
            protected JsonNode doInBackground() throws Exception {
                ensureNotStopped();
                ensureOfficialMcp(settings.timeoutSeconds);
                ensureNotStopped();
                return callOfficialToolWithSchemaRetry(finalCall, settings);
            }

            @Override
            protected void done() {
                try {
                    if (isCancelledOrStopped()) {
                        addToolCard("agent", "interrupt", false, "Operator interrupted before reading tool result.");
                        finishBusy();
                        return;
                    }
                    JsonNode result = get();
                    boolean success = !result.path("result").path("isError").asBoolean(false);
                    String resultText = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result.path("result"));
                    addToolCard("official-mcp", finalCall.name, success, resultText);
                    conversation.add(new ChatMessage("user", buildToolResultMessage(finalCall.name, resultText, success)));
                    if (isCancelledOrStopped()) {
                        finishBusy();
                    } else {
                        requestFollowUp(settings, depth + 1);
                    }
                } catch (CancellationException e) {
                    addToolCard("agent", "interrupt", false, "Operator interrupted MCP tool call.");
                    finishBusy();
                } catch (Exception e) {
                    if (isCancelledOrStopped()) {
                        addToolCard("agent", "interrupt", false, "Operator interrupted MCP tool call.");
                    } else {
                        addToolCard("official-mcp", finalCall.name, false, rootMessage(e));
                        addChatBubble("error", "Official MCP tool call failed: " + rootMessage(e));
                        conversation.add(new ChatMessage("user", "【任务暂停】Official MCP tool call failed: " + rootMessage(e)
                                + "\n请先解释失败原因，不要继续盲目调用工具。"));
                    }
                    finishBusy();
                }
            }
        };
        startWorker(worker);
        return true;
    }

    private void requestFollowUp(ChatSettings settings, int depth) {
        if (isCancelledOrStopped()) {
            finishBusy();
            return;
        }
        setBusy(true);
        ThoughtStreamUi streamUi = addThoughtStreamingBubbles();

        SwingWorker<String, Void> worker = new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                ensureNotStopped();
                settings.systemPrompt = defaultSystemPrompt(mcpClient.buildToolSummary(12000));
                return aiClient.sendStreamTyped(settings, conversation, (type, delta) -> appendTypedStreamDelta(streamUi, type, delta), AiAssistantTab.this::isCancelledOrStopped);
            }

            @Override
            protected void done() {
                try {
                    if (isCancelledOrStopped()) {
                        updateStreamBubble(streamUi.answerBubble, "已中断当前 AI 任务。");
                        finalizeThoughtBubble(streamUi);
                        finishBusy();
                        return;
                    }
                    String assistant = finalAnswerFromStream(streamUi, get());
                    updateStreamBubble(streamUi.answerBubble, assistant);
                    finalizeThoughtBubble(streamUi);
                    conversation.add(new ChatMessage("assistant", assistant));
                    boolean startedTool = handleMcpToolCallIfPresent(assistant, settings, depth);
                    if (!startedTool) {
                        finishBusy();
                    }
                } catch (CancellationException e) {
                    updateStreamBubble(streamUi.answerBubble, "已中断当前 AI 任务。");
                    finalizeThoughtBubble(streamUi);
                    finishBusy();
                } catch (Exception e) {
                    String message = isCancelledOrStopped() ? "已中断当前 AI 任务。" : "Follow-up failed: " + rootMessage(e);
                    updateStreamBubble(streamUi.answerBubble, message);
                    finalizeThoughtBubble(streamUi);
                    finishBusy();
                }
            }
        };
        startWorker(worker);
    }


    private void showConfigToolsDialog() {
        updateEndpointLabels();
        updateSkillStatusLabel();

        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBorder(new EmptyBorder(14, 14, 14, 14));
        root.setBackground(panelColor());
        root.setPreferredSize(new Dimension(900, 640));

        JLabel title = new JLabel("听风 Burp Agent 设置");
        title.setForeground(textColor());
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20f));
        title.setBorder(new EmptyBorder(0, 0, 8, 0));
        root.add(title, BorderLayout.NORTH);

        javax.swing.JTabbedPane tabs = new javax.swing.JTabbedPane();
        tabs.addTab("基础配置", buildSettingsTab());
        tabs.addTab("Skills", buildSkillsTab());
        tabs.addTab("官方 MCP 工具", buildMcpToolsTab());
        root.add(tabs, BorderLayout.CENTER);

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        footer.setOpaque(false);
        JButton save = new JButton("保存配置");
        JButton connect = new JButton("连接官方 MCP");
        JButton refresh = new JButton("刷新工具");
        JButton close = new JButton("关闭");
        stylePrimaryButton(save);
        styleSecondaryButton(connect);
        styleSecondaryButton(refresh);
        styleSecondaryButton(close);
        footer.add(save);
        footer.add(connect);
        footer.add(refresh);
        footer.add(close);
        root.add(footer, BorderLayout.SOUTH);

        java.awt.Window owner = SwingUtilities.getWindowAncestor(this);
        final javax.swing.JDialog dialog = new javax.swing.JDialog(owner, "听风 Burp Agent 设置", java.awt.Dialog.ModalityType.MODELESS);
        dialog.setContentPane(root);
        dialog.pack();
        dialog.setLocationRelativeTo(this);

        save.addActionListener(e -> saveSettings());
        connect.addActionListener(e -> connectOfficialMcp());
        refresh.addActionListener(e -> refreshOfficialMcpTools());
        close.addActionListener(e -> dialog.dispose());

        dialog.setVisible(true);
    }

    private JPanel buildSettingsTab() {
        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setOpaque(false);

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 0, 6, 0);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1;
        gbc.gridx = 0;
        gbc.gridy = 0;

        styleTextField(endpointField);
        styleTextField(modelField);
        styleTextField(apiKeyField);
        styleTextField(mcpUrlField);
        styleTextField(temperatureField);
        styleTextField(timeoutField);

        addField(form, gbc, "AI Endpoint", endpointField);
        addField(form, gbc, "Model", modelField);
        addField(form, gbc, "API key", apiKeyField);
        addField(form, gbc, "Official MCP SSE URL", mcpUrlField);
        addField(form, gbc, "Temperature", temperatureField);
        addField(form, gbc, "Timeout seconds", timeoutField);

        saveKeyCheck.setOpaque(false);
        saveKeyCheck.setForeground(textColor());
        confirmDangerousCheck.setOpaque(false);
        confirmDangerousCheck.setForeground(textColor());
        JPanel checks = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        checks.setOpaque(false);
        checks.add(saveKeyCheck);
        checks.add(Box.createHorizontalStrut(16));
        checks.add(confirmDangerousCheck);
        gbc.gridy++;
        form.add(checks, gbc);

        JPanel status = new JPanel();
        status.setOpaque(false);
        status.setLayout(new BoxLayout(status, BoxLayout.Y_AXIS));
        endpointStatusLabel.setForeground(mutedTextColor());
        endpointStatusLabel.setFont(endpointStatusLabel.getFont().deriveFont(Font.PLAIN, 12f));
        mcpStatusLabel.setForeground(mutedTextColor());
        mcpStatusLabel.setFont(mcpStatusLabel.getFont().deriveFont(Font.PLAIN, 12f));
        skillStatusLabel.setForeground(mutedTextColor());
        skillStatusLabel.setFont(skillStatusLabel.getFont().deriveFont(Font.PLAIN, 12f));
        updateEndpointLabels();
        updateSkillStatusLabel();
        status.add(endpointStatusLabel);
        status.add(Box.createVerticalStrut(6));
        status.add(mcpStatusLabel);
        status.add(Box.createVerticalStrut(6));
        status.add(skillStatusLabel);

        root.add(form, BorderLayout.NORTH);
        root.add(status, BorderLayout.CENTER);
        return root;
    }

    private JPanel buildSkillsTab() {
        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setOpaque(false);

        JTextArea skillsArea = new JTextArea(buildLoadedSkillsText());
        skillsArea.setEditable(false);
        skillsArea.setLineWrap(true);
        skillsArea.setWrapStyleWord(true);
        skillsArea.setFont(contentFont(12f));
        skillsArea.setBackground(inputBackground());
        skillsArea.setForeground(textColor());
        skillsArea.setBorder(new EmptyBorder(10, 10, 10, 10));
        JScrollPane skillScroll = new JScrollPane(skillsArea);
        skillScroll.setBorder(new RoundedBorder(borderColor(), 14));

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.setOpaque(false);
        JButton importBtn = new JButton("导入 Skill / ZIP");
        JButton reloadBtn = new JButton("重载 Skills");
        JButton templateBtn = new JButton("查看模板");
        JButton dirBtn = new JButton("打开目录");
        stylePrimaryButton(importBtn);
        styleSecondaryButton(reloadBtn);
        styleSecondaryButton(templateBtn);
        styleSecondaryButton(dirBtn);
        importBtn.addActionListener(e -> importSkillFile());
        reloadBtn.addActionListener(e -> {
            reloadSkills();
            skillsArea.setText(buildLoadedSkillsText());
        });
        templateBtn.addActionListener(e -> showSkillTemplate());
        dirBtn.addActionListener(e -> openSkillsDirectory());
        actions.add(importBtn);
        actions.add(reloadBtn);
        actions.add(templateBtn);
        actions.add(dirBtn);

        root.add(skillScroll, BorderLayout.CENTER);
        root.add(actions, BorderLayout.SOUTH);
        return root;
    }

    private JPanel buildMcpToolsTab() {
        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setOpaque(false);
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(contentFont(12f));
        area.setBackground(inputBackground());
        area.setForeground(textColor());
        area.setBorder(new EmptyBorder(10, 10, 10, 10));
        String summary = mcpClient.buildToolSummary(12000);
        if (summary == null || summary.trim().isEmpty()) {
            summary = "尚未加载官方 MCP 工具。点击【连接官方 MCP】或【刷新工具】后会显示 tools/list 结果。";
        }
        area.setText(summary);
        JScrollPane scroll = new JScrollPane(area);
        scroll.setBorder(new RoundedBorder(borderColor(), 14));
        root.add(scroll, BorderLayout.CENTER);
        return root;
    }

    private JPanel createMiniStatusCard(String titleText, String bodyText) {
        RoundedPanel card = new RoundedPanel(cardBackground(), 16);
        card.setLayout(new BorderLayout(0, 6));
        card.setBorder(new EmptyBorder(10, 12, 10, 12));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 88));

        JLabel title = new JLabel(titleText);
        title.setForeground(textColor());
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));

        JTextArea body = new JTextArea(bodyText);
        body.setEditable(false);
        body.setLineWrap(true);
        body.setWrapStyleWord(true);
        body.setOpaque(false);
        body.setForeground(mutedTextColor());
        body.setFont(contentFont(12f));
        body.setBorder(BorderFactory.createEmptyBorder());

        card.add(title, BorderLayout.NORTH);
        card.add(body, BorderLayout.CENTER);
        return card;
    }

    private JLabel createInfoLine(String label, String value) {
        String safeValue = safe(value);
        JLabel line = new JLabel(label + "：" + abbreviateMiddle(safeValue, 92));
        line.setForeground(mutedTextColor());
        line.setFont(line.getFont().deriveFont(Font.PLAIN, 12f));
        line.setToolTipText(safeValue);
        return line;
    }

    private String skillStatusText() {
        String text = "已加载 " + skillManager.count() + " 个 Skills";
        String error = skillManager.getLastError();
        if (error != null && !error.isEmpty()) {
            text += "，存在解析警告";
        }
        return text;
    }

    private String buildLoadedSkillsText() {
        StringBuilder sb = new StringBuilder();
        sb.append("Skills 目录：").append(skillManager.getSkillsDirectory()).append("\n");
        sb.append("已加载数量：").append(skillManager.count()).append("\n\n");
        if (skillManager.getSkills().isEmpty()) {
            sb.append("暂无已加载 Skill。可以点击【导入 Skill / ZIP】导入标准 Skill、HackSkills ZIP 或目录。\n");
            return sb.toString();
        }
        for (Skill skill : skillManager.getSkills()) {
            sb.append("- ").append(skill.name)
                    .append(skill.builtIn ? " [built-in]" : " [user]")
                    .append("\n  version: ").append(skill.version)
                    .append("\n  author: ").append(skill.author)
                    .append("\n  description: ").append(skill.description)
                    .append("\n  triggers: ").append(skill.triggers)
                    .append("\n\n");
        }
        return sb.toString();
    }

    private void importSkillFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("导入 TingFeng Skill 文件、目录或 ZIP 包");
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        chooser.setAcceptAllFileFilterUsed(true);
        chooser.setFileFilter(new FileNameExtensionFilter(
                "Skill files (*.md, *.markdown, *.txt, *.yaml, *.yml, *.json, *.skill, *.zip)",
                "md", "markdown", "txt", "yaml", "yml", "json", "skill", "zip"));
        int result = chooser.showOpenDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path path = chooser.getSelectedFile().toPath();
        try {
            SkillManager.ImportSummary summary = skillManager.importSkills(path);
            updateSkillStatusLabel();
            addToolCard("skills", "import", !summary.hasErrors(), summary.summaryText(30)
                    + "\nDirectory: " + skillManager.getSkillsDirectory());
            addChatBubble("assistant", "已导入 " + summary.importedCount()
                    + " 个 Skill。支持单个 Skill、目录和 ZIP 包；后续对话会自动把已加载 Skills 注入 System Prompt。");
            if (summary.hasErrors()) {
                JOptionPane.showMessageDialog(this,
                        "导入完成，但有部分文件未能解析。\n\n" + summary.summaryText(12),
                        "Skills Import Warnings",
                        JOptionPane.WARNING_MESSAGE);
            }
        } catch (Exception e) {
            addToolCard("skills", "import", false, rootMessage(e));
            JOptionPane.showMessageDialog(this,
                    "Skill 导入失败：\n" + rootMessage(e)
                            + "\n\n支持格式：.md/.markdown/.txt/.yaml/.yml/.json/.skill/.zip 或目录。"
                            + "\n标准推荐：YAML front matter + Markdown 正文。",
                    "Invalid Skill",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void reloadSkills() {
        skillManager.loadAll();
        updateSkillStatusLabel();
        String error = skillManager.getLastError();
        StringBuilder detail = new StringBuilder();
        detail.append("Loaded skills: ").append(skillManager.count()).append('\n');
        for (Skill skill : skillManager.getSkills()) {
            detail.append("- ").append(skill.name)
                    .append(skill.builtIn ? " [built-in]" : " [user]")
                    .append(": ").append(skill.description).append('\n');
        }
        if (!error.isEmpty()) {
            detail.append("\nWarnings:\n").append(error);
        }
        addToolCard("skills", "reload", error.isEmpty(), detail.toString());
    }

    private void showSkillTemplate() {
        JTextArea area = new JTextArea(skillManager.template(), 28, 86);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(contentFont(13f));
        area.setBackground(inputBackground());
        area.setForeground(textColor());
        area.setCaretColor(textColor());
        area.setBorder(new EmptyBorder(10, 10, 10, 10));
        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(new Dimension(860, 580));
        scroll.setBorder(new RoundedBorder(borderColor(), 14));

        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        panel.add(scroll, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton copy = new JButton("复制模板");
        stylePrimaryButton(copy);
        copy.addActionListener(e -> {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(area.getText()), null);
        });
        actions.add(copy);
        panel.add(actions, BorderLayout.SOUTH);
        JOptionPane.showMessageDialog(this, panel, "TingFeng Skill 标准模板", JOptionPane.INFORMATION_MESSAGE);
    }

    private void openSkillsDirectory() {
        try {
            Files.createDirectories(skillManager.getSkillsDirectory());
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(skillManager.getSkillsDirectory().toFile());
            } else {
                addToolCard("skills", "directory", true, skillManager.getSkillsDirectory().toString());
            }
        } catch (Exception e) {
            addToolCard("skills", "directory", false, rootMessage(e));
            JOptionPane.showMessageDialog(this, "无法打开目录：\n" + rootMessage(e), "Open Skills Directory", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void updateSkillStatusLabel() {
        if (skillStatusLabel == null) {
            return;
        }
        String error = skillManager.getLastError();
        String text = "Skills：" + skillManager.count() + " 个已加载";
        if (!error.isEmpty()) {
            text += "，存在解析警告";
        }
        skillStatusLabel.setText(text);
    }

    private void connectOfficialMcp() {
        ChatSettings settings;
        try {
            settings = readSettings();
        } catch (RuntimeException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Invalid Settings", JOptionPane.ERROR_MESSAGE);
            return;
        }
        setBusy(true);
        addToolCard("official-mcp", "connect", true, "Connecting to " + mcpUrlField.getText().trim());
        new SwingWorker<Integer, Void>() {
            @Override
            protected Integer doInBackground() throws Exception {
                ensureOfficialMcp(settings.timeoutSeconds);
                return mcpClient.getToolDefinitions().size();
            }

            @Override
            protected void done() {
                try {
                    int count = get();
                    addToolCard("official-mcp", "connect", true, "Connected. Message endpoint: " + mcpClient.getMessageUrl() + "\nLoaded tools: " + count);
                    updateEndpointLabels();
                    addChatBubble("assistant", "官方 MCP 已连接，已加载 " + count + " 个工具。\nAI API：" + normalizeEndpoint(endpointField.getText().trim()) + "\n官方 MCP：" + mcpUrlField.getText().trim() + "\n你可以问：查看可用 MCP 工具，或让 Agent 发包读取响应并分析。 ");
                } catch (Exception e) {
                    addToolCard("official-mcp", "connect", false, rootMessage(e));
                    addChatBubble("error", "连接官方 MCP 失败：" + rootMessage(e));
                } finally {
                    setBusy(false);
                }
            }
        }.execute();
    }

    private void refreshOfficialMcpTools() {
        ChatSettings settings;
        try {
            settings = readSettings();
        } catch (RuntimeException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Invalid Settings", JOptionPane.ERROR_MESSAGE);
            return;
        }
        setBusy(true);
        new SwingWorker<Integer, Void>() {
            @Override
            protected Integer doInBackground() throws Exception {
                ensureOfficialMcp(settings.timeoutSeconds);
                mcpClient.refreshTools(settings.timeoutSeconds);
                return mcpClient.getToolDefinitions().size();
            }

            @Override
            protected void done() {
                try {
                    int count = get();
                    addToolCard("official-mcp", "tools/list", true, "Loaded tools: " + count + "\n" + mcpClient.buildToolSummary(4000));
                } catch (Exception e) {
                    addToolCard("official-mcp", "tools/list", false, rootMessage(e));
                    addChatBubble("error", "刷新官方 MCP 工具失败：" + rootMessage(e));
                } finally {
                    setBusy(false);
                }
            }
        }.execute();
    }

    private void ensureOfficialMcp(int timeoutSeconds) throws Exception {
        String url = mcpUrlField.getText().trim();
        mcpClient.connect(url, timeoutSeconds);
    }

    private ChatSettings readSettings() {
        ChatSettings settings = new ChatSettings();
        settings.endpoint = normalizeEndpoint(endpointField.getText().trim());
        settings.model = modelField.getText().trim();
        settings.apiKey = new String(apiKeyField.getPassword());
        settings.temperature = Double.parseDouble(temperatureField.getText().trim());
        settings.timeoutSeconds = Integer.parseInt(timeoutField.getText().trim());
        settings.saveApiKey = saveKeyCheck.isSelected();
        settings.promptBeforeDangerousTools = confirmDangerousCheck.isSelected();
        settings.systemPrompt = defaultSystemPrompt(mcpClient.buildToolSummary(12000));
        updateEndpointLabels();
        if (settings.endpoint.isEmpty()) {
            throw new IllegalArgumentException("AI Endpoint is required");
        }
        if (settings.model.isEmpty()) {
            throw new IllegalArgumentException("Model is required");
        }
        if (mcpUrlField.getText().trim().isEmpty()) {
            throw new IllegalArgumentException("Official MCP SSE URL is required");
        }
        if (settings.timeoutSeconds < 5 || settings.timeoutSeconds > 7200) {
            throw new IllegalArgumentException("Timeout must be between 5 and 7200 seconds");
        }
        return settings;
    }

    private String normalizeEndpoint(String endpoint) {
        String trimmed = endpoint == null ? "" : endpoint.trim();
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.endsWith("/v1")) {
            return trimmed + "/chat/completions";
        }
        return trimmed;
    }

    private void saveSettings() {
        ChatSettings settings;
        try {
            settings = readSettings();
        } catch (RuntimeException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Invalid AI Settings", JOptionPane.ERROR_MESSAGE);
            return;
        }
        prefSetString(PREF_ENDPOINT, settings.endpoint);
        prefSetString(PREF_MODEL, settings.model);
        prefSetString(PREF_MCP_URL, mcpUrlField.getText().trim());
        prefSetString(PREF_TEMPERATURE, String.valueOf(settings.temperature));
        prefSetString(PREF_TIMEOUT, String.valueOf(settings.timeoutSeconds));
        prefSetBoolean(PREF_SAVE_KEY, settings.saveApiKey);
        prefSetBoolean(PREF_CONFIRM, settings.promptBeforeDangerousTools);
        if (settings.saveApiKey) {
            prefSetString(PREF_API_KEY, settings.apiKey);
        } else {
            prefDelete(PREF_API_KEY);
        }
        updateEndpointLabels();
        addToolCard("config", "save", true, "Settings saved. AI API: " + settings.endpoint + "\nOfficial MCP URL: " + mcpUrlField.getText().trim());
    }

    private McpToolCall parseMcpToolCall(String raw) throws Exception {
        String json = extractJson(raw);
        if (json == null) {
            return null;
        }
        JsonNode root = mapper.readTree(json);
        if (root.isArray() && root.size() > 0) {
            root = root.get(0);
        }
        if (!root.isObject()) {
            return null;
        }

        if (root.has("method") && "tools/call".equals(root.path("method").asText())) {
            JsonNode params = root.path("params");
            McpToolCall call = new McpToolCall();
            call.name = firstText(params, "name", "toolName", "tool_name");
            call.arguments = objectNode(firstNode(params, "arguments", "args", "params", "input"));
            call.comment = firstText(root, "comment", "reason", "note");
            return call;
        }

        if (root.has("name") || root.has("toolName") || root.has("tool_name")) {
            McpToolCall call = new McpToolCall();
            call.name = firstText(root, "name", "toolName", "tool_name");
            JsonNode args = firstNode(root, "arguments", "args", "params", "input");
            call.arguments = objectNode(args);
            call.comment = firstText(root, "comment", "reason", "note", "description");
            return call;
        }

        if (root.has("tool") && root.has("action")) {
            McpToolCall call = new McpToolCall();
            call.name = normalizeLegacyToolAction(root.path("tool").asText(""), root.path("action").asText(""));
            call.arguments = objectNode(firstNode(root, "params", "arguments", "args", "input"));
            call.comment = firstText(root, "comment", "reason", "note");
            return call;
        }

        return null;
    }

    private ObjectNode objectNode(JsonNode node) {
        if (node != null && node.isObject()) {
            return (ObjectNode) node.deepCopy();
        }
        return mapper.createObjectNode();
    }

    private JsonNode firstNode(JsonNode node, String... names) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isMissingNode() && !value.isNull()) {
                return value;
            }
        }
        return null;
    }

    private String firstText(JsonNode node, String... names) {
        JsonNode value = firstNode(node, names);
        return value == null ? "" : value.asText("");
    }

    private String extractJson(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher matcher = JSON_BLOCK.matcher(raw);
        while (matcher.find()) {
            String block = matcher.group(1).trim();
            String candidate = extractBalancedJson(block);
            if (candidate != null) {
                return stripJsonComments(candidate);
            }
        }
        String candidate = extractBalancedJson(raw.trim());
        return candidate == null ? null : stripJsonComments(candidate);
    }

    private String extractBalancedJson(String text) {
        if (text == null) {
            return null;
        }
        int start = -1;
        char open = 0;
        char close = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{' || c == '[') {
                start = i;
                open = c;
                close = c == '{' ? '}' : ']';
                break;
            }
        }
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (c == '\\') {
                escape = inString;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1).trim();
                }
            }
        }
        return null;
    }

    private String stripJsonComments(String json) {
        // 兼容部分模型输出的 jsonc 注释，避免因为 // 或 /* */ 导致解析失败。
        StringBuilder out = new StringBuilder();
        boolean inString = false;
        boolean escape = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            char next = i + 1 < json.length() ? json.charAt(i + 1) : 0;
            if (escape) {
                out.append(c);
                escape = false;
                continue;
            }
            if (c == '\\') {
                out.append(c);
                escape = inString;
                continue;
            }
            if (c == '"') {
                out.append(c);
                inString = !inString;
                continue;
            }
            if (!inString && c == '/' && next == '/') {
                while (i < json.length() && json.charAt(i) != '\n') i++;
                out.append('\n');
                continue;
            }
            if (!inString && c == '/' && next == '*') {
                i += 2;
                while (i + 1 < json.length() && !(json.charAt(i) == '*' && json.charAt(i + 1) == '/')) i++;
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString().trim();
    }

    private boolean requestFormatRepair(ChatSettings settings, String badText, String reason) {
        if (formatRepairCount >= MAX_FORMAT_REPAIRS) {
            addChatBubble("assistant", "【任务暂停】\n\n连续出现工具调用格式错误，已停止自动修复，避免循环。\n\n原因：" + reason
                    + "\n\n请重新描述任务，或要求我先查看可用 MCP 工具。");
            addToolCard("agent", "format", false, reason);
            finishBusy();
            return true;
        }
        formatRepairCount++;
        addToolCard("agent", "format-repair", false,
                "Format repair " + formatRepairCount + "/" + MAX_FORMAT_REPAIRS + ": " + reason);
        conversation.add(new ChatMessage("user", "【格式修复请求】\n"
                + "你的上一条回复没有通过插件的工具调用格式校验。\n"
                + "错误原因：" + reason + "\n\n"
                + "请严格二选一：\n"
                + "1. 如果需要继续调用工具，只输出一个 Markdown JSON 代码块：```json\n{\"name\":\"official_mcp_tool_name\",\"arguments\":{},\"comment\":\"说明\"}\n```\n"
                + "2. 如果任务已经可以回答，输出以【任务完成】开头的中文 Markdown 结论。\n"
                + "禁止输出旧格式 {tool, action}，禁止在工具 JSON 外添加解释。\n\n"
                + "可用工具名：" + compactToolNames(80)));
        requestFollowUp(settings, 1);
        return true;
    }

    private boolean isTerminalAssistantMessage(String text) {
        String value = safe(text).trim();
        return value.startsWith("【任务完成】")
                || value.startsWith("【等待确认】")
                || value.startsWith("【需要补充信息】")
                || value.startsWith("【任务暂停】");
    }

    private boolean looksLikeToolIntent(String text) {
        String value = safe(text).toLowerCase(Locale.ROOT);
        return value.contains("\"name\"")
                || value.contains("\"tool\"")
                || value.contains("tools/call")
                || value.contains("```json")
                || value.contains("arguments")
                || value.contains("params");
    }

    private String resolveOfficialToolName(String name) {
        String normalized = normalizeToolName(name);
        if (officialToolExists(normalized)) {
            return normalized;
        }
        String key = looseKey(normalized);
        for (JsonNode tool : mcpClient.getToolDefinitions()) {
            String actual = tool.path("name").asText("");
            if (looseKey(actual).equals(key)) {
                return actual;
            }
        }
        for (JsonNode tool : mcpClient.getToolDefinitions()) {
            String actual = tool.path("name").asText("");
            String actualKey = looseKey(actual);
            if (!key.isEmpty() && (actualKey.endsWith(key) || key.endsWith(actualKey))) {
                return actual;
            }
        }
        return normalized;
    }

    private boolean officialToolExists(String name) {
        JsonNode tools = mcpClient.getToolDefinitions();
        if (tools == null || !tools.isArray() || tools.size() == 0) {
            return true;
        }
        for (JsonNode tool : tools) {
            if (name.equals(tool.path("name").asText(""))) {
                return true;
            }
        }
        return false;
    }

    private String compactToolNames(int max) {
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode tool : mcpClient.getToolDefinitions()) {
            String name = tool.path("name").asText("");
            if (!name.isEmpty()) names.add(name);
            if (names.size() >= max) break;
        }
        if (names.isEmpty()) {
            return "尚未加载 tools/list，请先连接官方 MCP";
        }
        return String.join(", ", names);
    }

    private String looseKey(String value) {
        return safe(value).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /**
     * PortSwigger's official MCP server uses strict Kotlin serialization.
     * Unknown keys like limit/input/confirmed will make the tool fail.
     *
     * This method normalizes common LLM aliases and then filters arguments
     * against the exact inputSchema returned by tools/list.
     */
    private void normalizeArgumentsForTool(McpToolCall call) {
        if (call.arguments == null) {
            call.arguments = mapper.createObjectNode();
        }

        ObjectNode original = (ObjectNode) call.arguments.deepCopy();
        ObjectNode schemaProps = inputSchemaProperties(call.name);

        // No schema available: still remove known bad control fields.
        if (schemaProps == null || schemaProps.size() == 0) {
            original.remove("confirmed");
            original.remove("limit");
            if (original.has("input") && !original.has("content")) {
                original.set("content", original.get("input"));
                original.remove("input");
            }
            call.arguments = original;
            return;
        }

        ObjectNode fixed = mapper.createObjectNode();
        java.util.Iterator<java.util.Map.Entry<String, JsonNode>> fields = schemaProps.fields();
        while (fields.hasNext()) {
            java.util.Map.Entry<String, JsonNode> entry = fields.next();
            String expected = entry.getKey();
            JsonNode value = valueForExpectedArgument(call.name, expected, original);
            if (value != null && !value.isMissingNode() && !value.isNull()) {
                fixed.set(expected, coerceForSchema(value, entry.getValue()));
            }
        }

        // Safe defaults for PortSwigger paginated tools.
        if (schemaProps.has("count") && !fixed.has("count")) {
            fixed.put("count", 20);
        }
        if (schemaProps.has("offset") && !fixed.has("offset")) {
            fixed.put("offset", 0);
        }

        // If user/AI gave a URL for an HTTP tool, complete service fields.
        enrichHttpServiceArgsFromUrl(call.name, original, fixed, schemaProps);

        call.arguments = fixed;
    }

    private ObjectNode inputSchemaProperties(String toolName) {
        JsonNode tools = mcpClient.getToolDefinitions();
        if (tools == null || !tools.isArray()) {
            return null;
        }
        for (JsonNode tool : tools) {
            if (toolName.equals(tool.path("name").asText(""))) {
                JsonNode props = tool.path("inputSchema").path("properties");
                if (props.isObject()) {
                    return (ObjectNode) props;
                }
            }
        }
        return null;
    }

    private JsonNode valueForExpectedArgument(String toolName, String expected, ObjectNode original) {
        JsonNode exact = original.get(expected);
        if (exact != null && !exact.isNull()) {
            return exact;
        }

        switch (expected) {
            case "count":
                return firstNode(original, "count", "limit", "max", "maxResults", "max_results", "pageSize", "size", "number");
            case "offset":
                return firstNode(original, "offset", "start", "skip", "from");
            case "content":
                return firstNode(original, "content", "input", "text", "value", "data", "string", "rawRequest", "raw_request", "request", "httpRequest", "http_request", "message");
            case "regex":
                return firstNode(original, "regex", "pattern", "query", "keyword", "filter");
            case "targetHostname":
                return firstNode(original, "targetHostname", "targetHost", "target_host", "hostname", "host", "domain", "server", "target", "authority");
            case "targetPort":
                return firstNode(original, "targetPort", "target_port", "port");
            case "usesHttps":
                return firstNode(original, "usesHttps", "useHttps", "https", "ssl", "tls", "secure");
            case "tabName":
                return firstNode(original, "tabName", "tab", "name", "title");
            case "pseudoHeaders":
                return firstNode(original, "pseudoHeaders", "pseudo_headers", "pseudo", "http2PseudoHeaders");
            case "headers":
                return firstNode(original, "headers", "headerMap");
            case "requestBody":
                return firstNode(original, "requestBody", "body", "data", "content", "payload");
            case "length":
                return firstNode(original, "length", "size", "count");
            case "characterSet":
                return firstNode(original, "characterSet", "charset", "chars", "alphabet");
            case "json":
                return firstNode(original, "json", "config", "options", "body", "content");
            case "running":
                return firstNode(original, "running", "enabled", "run", "active");
            case "intercepting":
                return firstNode(original, "intercepting", "enabled", "intercept", "active");
            case "text":
                return firstNode(original, "text", "content", "input", "value", "data", "body");
            case "customData":
                return firstNode(original, "customData", "custom_data", "data", "value");
            case "payloadId":
                return firstNode(original, "payloadId", "payload_id", "id");
            case "request":
                return firstNode(original, "request", "content", "rawRequest", "raw_request", "httpRequest", "http_request");
            case "httpRequest":
                return firstNode(original, "httpRequest", "http_request", "request", "content", "rawRequest", "raw_request");
            case "method":
                return firstNode(original, "method", "httpMethod", "http_method", "verb");
            case "path":
                return firstNode(original, "path", "uri", "urlPath", "url_path", "requestTarget", "request_target");
            case "host":
                return firstNode(original, "host", "hostname", "targetHostname", "targetHost", "domain");
            default:
                return null;
        }
    }

    private JsonNode coerceForSchema(JsonNode value, JsonNode schema) {
        String type = schema == null ? "" : schema.path("type").asText("");
        if ("integer".equals(type)) {
            if (value.isInt() || value.isLong()) return value;
            String s = value.asText("").trim();
            try {
                return mapper.getNodeFactory().numberNode(Integer.parseInt(s));
            } catch (Exception ignored) {
                return mapper.getNodeFactory().numberNode(0);
            }
        }
        if ("boolean".equals(type)) {
            if (value.isBoolean()) return value;
            String s = value.asText("").trim().toLowerCase(Locale.ROOT);
            boolean b = "true".equals(s) || "1".equals(s) || "yes".equals(s) || "y".equals(s) || "on".equals(s) || "https".equals(s);
            return mapper.getNodeFactory().booleanNode(b);
        }
        if ("object".equals(type)) {
            if (value.isObject()) return value;
            return mapper.createObjectNode();
        }
        if ("array".equals(type)) {
            if (value.isArray()) return value;
            return mapper.createArrayNode();
        }
        if (value.isTextual()) return value;
        return mapper.getNodeFactory().textNode(value.asText(""));
    }

    private void enrichHttpServiceArgsFromUrl(String toolName, ObjectNode original, ObjectNode fixed, ObjectNode schemaProps) {
        if (!schemaProps.has("targetHostname") && !schemaProps.has("targetPort") && !schemaProps.has("usesHttps")) {
            return;
        }
        JsonNode urlNode = firstNode(original, "url", "targetUrl", "target_url", "uri");
        if (urlNode == null || urlNode.isNull()) {
            return;
        }
        String rawUrl = urlNode.asText("").trim();
        if (rawUrl.isEmpty()) {
            return;
        }
        try {
            java.net.URI uri = new java.net.URI(rawUrl);
            String scheme = uri.getScheme();
            boolean https = scheme == null || scheme.equalsIgnoreCase("https");
            int port = uri.getPort() > 0 ? uri.getPort() : (https ? 443 : 80);
            String host = uri.getHost();
            if (host != null && !host.isEmpty() && schemaProps.has("targetHostname") && !fixed.has("targetHostname")) {
                fixed.put("targetHostname", host);
            }
            if (schemaProps.has("targetPort") && !fixed.has("targetPort")) {
                fixed.put("targetPort", port);
            }
            if (schemaProps.has("usesHttps") && !fixed.has("usesHttps")) {
                fixed.put("usesHttps", https);
            }

            // For send_http1_request/create_repeater_tab/send_to_intruder, content is required.
            if (schemaProps.has("content") && !fixed.has("content")) {
                String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
                if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) {
                    path += "?" + uri.getRawQuery();
                }
                String method = firstText(original, "method");
                if (method.isEmpty()) method = "GET";
                StringBuilder request = new StringBuilder();
                request.append(method.toUpperCase(Locale.ROOT)).append(' ').append(path).append(" HTTP/1.1\\r\\n");
                request.append("Host: ").append(host);
                if ((https && port != 443) || (!https && port != 80)) {
                    request.append(':').append(port);
                }
                request.append("\\r\\n");
                JsonNode headers = firstNode(original, "headers");
                if (headers != null && headers.isObject()) {
                    java.util.Iterator<java.util.Map.Entry<String, JsonNode>> it = headers.fields();
                    while (it.hasNext()) {
                        java.util.Map.Entry<String, JsonNode> h = it.next();
                        if (!"host".equalsIgnoreCase(h.getKey())) {
                            request.append(h.getKey()).append(": ").append(h.getValue().asText()).append("\\r\\n");
                        }
                    }
                }
                request.append("\\r\\n");
                JsonNode body = firstNode(original, "body", "requestBody");
                if (body != null && !body.isNull()) {
                    request.append(body.asText(""));
                }
                fixed.put("content", request.toString());
            }
        } catch (Exception ignored) {
            // URL parsing is best-effort. Schema filtering still protects MCP calls.
        }
    }

    private void copyFirstArgument(ObjectNode args, String target, String... aliases) {
        for (String alias : aliases) {
            JsonNode value = args.get(alias);
            if (value != null && !value.isNull()) {
                args.set(target, value);
                return;
            }
        }
    }

    private JsonNode callOfficialToolWithSchemaRetry(McpToolCall call, ChatSettings settings) throws Exception {
        JsonNode first = mcpClient.callTool(call.name, call.arguments, settings.timeoutSeconds);
        if (!officialMcpResultIsError(first)) {
            return first;
        }

        String errorText = officialMcpResultText(first);
        if (!looksLikeStrictSchemaError(errorText)) {
            return first;
        }

        addToolCard("official-mcp", call.name, false,
                "Strict schema error detected, auto-repairing arguments and retrying once.\n" + errorText);

        removeUnknownKeyFromArguments(call, errorText);
        mcpClient.refreshTools(settings.timeoutSeconds);
        normalizeArgumentsForTool(call);
        return mcpClient.callTool(call.name, call.arguments, settings.timeoutSeconds);
    }

    private boolean officialMcpResultIsError(JsonNode response) {
        if (response == null) {
            return true;
        }
        if (response.hasNonNull("error")) {
            return true;
        }
        return response.path("result").path("isError").asBoolean(false);
    }

    private String officialMcpResultText(JsonNode response) {
        if (response == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (response.hasNonNull("error")) {
            sb.append(response.path("error").toString()).append('\n');
        }
        JsonNode content = response.path("result").path("content");
        if (content.isArray()) {
            for (JsonNode item : content) {
                String text = item.path("text").asText("");
                if (!text.isEmpty()) {
                    sb.append(text).append('\n');
                }
            }
        }
        if (sb.length() == 0) {
            sb.append(response.toString());
        }
        return sb.toString();
    }

    private boolean looksLikeStrictSchemaError(String text) {
        String value = safe(text).toLowerCase(Locale.ROOT);
        return value.contains("unknown key")
                || value.contains("ignoreunknownkeys")
                || value.contains("missingfieldexception")
                || value.contains("required field")
                || value.contains("is required");
    }

    private void removeUnknownKeyFromArguments(McpToolCall call, String errorText) {
        if (call == null || call.arguments == null) {
            return;
        }
        Matcher m = Pattern.compile("unknown key '([^']+)'", Pattern.CASE_INSENSITIVE).matcher(safe(errorText));
        while (m.find()) {
            call.arguments.remove(m.group(1));
        }
        call.arguments.remove("confirmed");
        call.arguments.remove("limit");
        call.arguments.remove("input");
        call.arguments.remove("params");
    }

    private String buildToolResultMessage(String toolName, String resultText, boolean success) {
        return "Official Burp MCP tool result for " + toolName + " (success=" + success + "):\n```json\n" + resultText + "\n```\n\n"
                + "请根据结果判断：\n"
                + "1. 目标是否已经完成；完成就输出【任务完成】。\n"
                + "2. 如果工具返回 HTTP 响应，请提取 status、headers、body、关键字段、敏感信息、异常差异并分析风险。\n"
                + "3. 如果 send/create_repeater_tab 只创建了 Repeater 标签但没有响应，说明该工具不会自动读取响应；需要用户确认后改用 send_http1_request 发包读取响应。\n"
                + "4. 如果响应为空、超时或连接失败，输出【任务暂停】，说明可能原因：目标不可达、TLS/端口错误、请求 Host/path/body 不完整、Burp 官方 MCP 工具限制或服务端未响应，不要盲目重复。\n"
                + "5. 是否需要主动操作；需要就输出【等待确认】，不要直接调用。\n"
                + "6. 是否缺少信息；缺少就输出【需要补充信息】。\n"
                + "7. 是否还需要只读工具补充证据；需要时只输出一个合法工具 JSON。";
    }

    private String normalizeLegacyToolAction(String tool, String action) {
        String t = safe(tool).trim();
        String a = safe(action).trim();
        if ("proxy".equals(t)) {
            if ("get_proxy_history".equals(a) || "proxy_history".equals(a) || "history".equals(a)) return "get_proxy_http_history";
            if ("extract_sensitive_data".equals(a) || "extract_sensitive".equals(a)) return "extract_sensitive_data";
            if ("get_site_map".equals(a) || "site_map".equals(a)) return "get_site_map";
        }
        if ("traffic_analyzer".equals(t)) {
            if ("proxy_history".equals(a) || "get_proxy_history".equals(a)) return "get_proxy_http_history";
            if ("extract_sensitive".equals(a) || "extract_sensitive_data".equals(a)) return "extract_sensitive_data";
            if ("site_map".equals(a) || "get_site_map".equals(a)) return "get_site_map";
        }
        if ("scope".equals(t)) {
            if ("check".equals(a) || "check_scope".equals(a)) return "check_scope";
            if ("add".equals(a) || "add_to_scope".equals(a)) return "add_to_scope";
            if ("remove".equals(a) || "remove_from_scope".equals(a)) return "remove_from_scope";
        }
        if ("scanner".equals(t) && ("issues".equals(a) || "get_scanner_issues".equals(a))) return "get_scanner_issues";
        if ("send_request".equals(t) && "send".equals(a)) return "send_http1_request";
        if ("intruder".equals(t) && "send".equals(a)) return "send_to_intruder";
        if ("repeater".equals(t) && "send".equals(a)) return "send_to_repeater";
        if ("workspace".equals(t)) {
            if ("status".equals(a)) return "workspace_status";
            if ("save_result".equals(a)) return "save_workspace_result";
            if ("load_context".equals(a)) return "load_workspace_context";
            if ("export_markdown".equals(a)) return "export_markdown_report";
        }
        if ("proxy_intercept".equals(t)) {
            if ("enable".equals(a)) return "enable_proxy_intercept";
            if ("disable".equals(a)) return "disable_proxy_intercept";
        }
        return t + "." + a;
    }

    private String normalizeToolName(String name) {
        String n = safe(name).trim();
        String key = looseKey(n);

        // Legacy/self-defined names -> PortSwigger official MCP names.
        if (key.equals("getproxyhistory") || key.equals("proxygetproxyhistory") || key.equals("trafficanalyzerproxyhistory")) return "get_proxy_http_history";
        if (key.equals("getproxyhttphistory")) return "get_proxy_http_history";
        if (key.equals("getproxyhistoryregex") || key.equals("searchproxyhistory") || key.equals("proxyhistoryregex")) return "get_proxy_http_history_regex";
        if (key.equals("getscannerissues") || key.equals("scannerissues")) return "get_scanner_issues";
        if (key.equals("sendhttprequest") || key.equals("sendrequestsend") || key.equals("sendhttp1") || key.equals("sendhttp1request")) return "send_http1_request";
        if (key.equals("sendhttp2request")) return "send_http2_request";
        if (key.equals("sendtorepeater") || key.equals("repeatersend")) return "create_repeater_tab";
        if (key.equals("createrepeatertabhttp2")) return "create_repeater_tab_http2";
        if (key.equals("sendtointruder") || key.equals("intrudersend")) return "send_to_intruder";
        if (key.equals("base64decode")) return "base64_decode";
        if (key.equals("base64encode")) return "base64_encode";
        if (key.equals("urldecode")) return "url_decode";
        if (key.equals("urlencode")) return "url_encode";
        if (key.equals("generaterandomstring")) return "generate_random_string";
        if (key.equals("setproxyinterceptstate") || key.equals("proxyinterceptenable") || key.equals("proxyinterceptdisable")) return "set_proxy_intercept_state";
        if (key.equals("settaskexecutionenginestate")) return "set_task_execution_engine_state";
        if (key.equals("getactiveeditorcontents")) return "get_active_editor_contents";
        if (key.equals("setactiveeditorcontents")) return "set_active_editor_contents";

        if ("proxy.get_proxy_history".equals(n) || "traffic_analyzer.proxy_history".equals(n)) return "get_proxy_http_history";
        if ("scanner.issues".equals(n)) return "get_scanner_issues";
        if ("send_request.send".equals(n)) return "send_http1_request";
        if ("intruder.send".equals(n)) return "send_to_intruder";
        if ("repeater.send".equals(n)) return "create_repeater_tab";
        return n;
    }

    private boolean isDangerousTool(String name) {
        String n = safe(name).toLowerCase(Locale.ROOT);
        if (n.contains("history") || n.contains("site_map") || n.contains("scope_check") || n.equals("check_scope")) {
            return false;
        }
        return n.startsWith("send")
                || n.contains("replay")
                || n.contains("compare")
                || n.contains("intruder")
                || n.contains("add")
                || n.contains("remove")
                || n.contains("delete")
                || n.contains("enable")
                || n.contains("disable")
                || n.contains("start")
                || n.contains("stop")
                || n.contains("request")
                || n.contains("scan");
    }

    private String defaultSystemPrompt(String toolSummary) {
        return "你是听风 Burp Agent，一个通过官方 Burp MCP 操作 Burp Suite 的授权安全测试 Agent。"
                + "你只允许在用户明确授权的目标、范围和测试环境内工作；目标不清楚时必须先要求补充信息。"
                + "\n\n# 输出模式硬规则"
                + "\n你每次回复只能选择以下两种模式之一："
                + "\n模式 A：工具调用。需要 Burp 数据或动作时，只输出一个 Markdown JSON 代码块，不要附加任何解释。"
                + "\n格式必须完全符合：```json\\n{\\\"name\\\":\\\"official_mcp_tool_name\\\",\\\"arguments\\\":{},\\\"comment\\\":\\\"简短说明\\\"}\\n```"
                + "\n模式 B：最终/暂停回复。不调用工具时，必须用中文 Markdown 输出，并且开头必须是【任务完成】、【等待确认】、【需要补充信息】或【任务暂停】之一。"
                + "\n禁止把说明文字和工具 JSON 混在同一条回复里。禁止输出旧格式 {tool, action}。禁止编造工具名。"
                + "\n如果你不确定工具名或参数，先输出【需要补充信息】或使用 tools/list 已提供的工具名，不要猜。"
                + "\n\n# Agent 自动收尾规则"
                + "\n你可以多步骤分析，但不能无限循环。每次工具结果返回后都必须判断目标是否已经完成。"
                + "\n如果用户目标已经回答、证据足够、继续调用工具不会增加有效信息，必须输出【任务完成】并停止。"
                + "\n如果需要主动请求、重放、扫描、Intruder、修改 Scope、启停拦截或导出报告，必须先输出【等待确认】说明原因和影响，等待用户确认。"
                + "\n如果缺少目标、域名、接口、Scope、测试账号、授权边界或必要上下文，必须输出【需要补充信息】并停止。"
                + "\n如果工具失败或返回信息不足，必须输出【任务暂停】说明原因和下一步建议，不要盲目重试。"
                + "\n最终结论必须包含：测试目标、已执行步骤、核心发现、证据摘要、风险判断、修复建议、下一步建议。"
                + "\n\n# 工具调用参数规则"
                + "\narguments 必须是 JSON object，不能是字符串。"
                + "\n所有 arguments 必须严格匹配 tools/list 里的 inputSchema.properties，只能使用 schema 中存在的字段，严禁多传字段。"
                + "\nPortSwigger 官方工具常用参数：get_proxy_http_history 使用 count 和 offset，不要用 limit；base64_decode/base64_encode/url_decode/url_encode 使用 content，不要用 input；send_http1_request 使用 content、targetHostname、targetPort、usesHttps，不要传 confirmed。"
                + "\n如果用户要‘发包并读取响应/分析响应’，优先使用 send_http1_request 或 send_http2_request，而不是只创建 Repeater 标签；create_repeater_tab/send_to_repeater 只负责把请求放进 Repeater，不一定会自动返回响应。"
                + "\n如果用户要求‘发送到 Repeater 后由用户运行’，你应先创建 Repeater 标签，然后等待用户说明已运行；若需要自动分析响应，应请求确认后用 send_http1_request 发送同等请求并读取响应。"
                + "\n如果用户要求‘拦截报文’，只能在 tools/list 中存在对应 proxy/intercept 工具时才调用；没有对应工具时输出【任务暂停】说明官方 MCP 当前未暴露该能力。"
                + "\n主动工具必须等待用户确认，但确认结果由插件处理，绝不要在 arguments 里添加 confirmed。"
                + "\n\n# 已加载 Skills\n"
                + skillManager.buildPrompt(24000)
                + "\n\n# Official MCP tools/list\n"
                + toolSummary;
    }

    private String pentestSkills() {
        return "# 渗透测试 Skills（内置）\n"
                + "1. 范围确认：先确认目标、环境、Scope、授权边界；不确定时先询问。\n"
                + "2. 被动优先：优先读取 Proxy History、Site Map、Scanner Issues、编码解码结果，不急于主动发包。\n"
                + "3. 资产梳理：按域名、路径、接口、参数、认证态、响应码、敏感字段建立测试地图。\n"
                + "4. 敏感信息：检查 Token、JWT、AK/SK、Cookie、内部 IP、错误栈、调试信息、密钥片段。\n"
                + "5. 认证授权：关注登录、找回密码、用户信息、订单、角色、租户、越权访问、IDOR、JWT 配置。\n"
                + "6. 输入验证：关注 SQL/NoSQL 注入、命令注入、路径穿越、SSRF、XSS、模板注入、文件上传。\n"
                + "7. 业务逻辑：关注价格、积分、优惠券、支付、状态机、重复提交、并发、权限绕过。\n"
                + "8. 对比验证：对原始请求和修改请求做响应码、长度、关键字段、权限字段、缓存行为对比。\n"
                + "9. 证据沉淀：每一步保留请求、响应、参数、判断依据，不确定的结论标记为待验证。\n"
                + "10. 输出报告：按风险等级、影响面、复现条件、修复建议和验证方法整理。";
    }


    private void startWorker(SwingWorker<?, ?> worker) {
        activeWorker = worker;
        worker.execute();
    }

    private void requestStop() {
        stopRequested = true;
        SwingWorker<?, ?> worker = activeWorker;
        if (worker != null) {
            worker.cancel(true);
        }
        addToolCard("agent", "interrupt", false, "Operator requested interruption. The current stream or tool call will stop as soon as the connection returns control.");
        sendButton.setEnabled(true);
        stopButton.setEnabled(false);
        stopButton.setText("已中断");
    }

    private boolean isCancelledOrStopped() {
        SwingWorker<?, ?> worker = activeWorker;
        return stopRequested || Thread.currentThread().isInterrupted() || (worker != null && worker.isCancelled());
    }

    private void ensureNotStopped() throws InterruptedException {
        if (isCancelledOrStopped()) {
            throw new InterruptedException("Interrupted by operator");
        }
    }

    private void finishBusy() {
        activeWorker = null;
        setBusy(false);
    }

    private void startQuoteCarousel() {
        javax.swing.Timer timer = new javax.swing.Timer(QUOTE_ROTATE_MS, event -> {
            quoteIndex = (quoteIndex + 1) % HACKER_QUOTES.length;
            updateQuoteText();
        });
        timer.setRepeats(true);
        timer.start();
    }

    private void updateQuoteText() {
        if (quoteLabel == null) {
            return;
        }
        String quote = HACKER_QUOTES[Math.floorMod(quoteIndex, HACKER_QUOTES.length)];
        // Keep this as plain text. Some Burp/Swing themes display JLabel HTML literally,
        // which caused tags such as <html> and &quot; to appear in the UI.
        quoteLabel.setText(quote);
    }

    private void addField(JPanel form, GridBagConstraints gbc, String label, JComponent field) {
        JLabel l = new JLabel(label);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 12f));
        l.setForeground(mutedTextColor());
        gbc.gridy++;
        form.add(l, gbc);
        gbc.gridy++;
        form.add(field, gbc);
    }

    private void addChatBubble(String role, String text) {
        boolean user = "user".equals(role);
        boolean error = "error".equals(role);
        boolean thinking = "thinking".equals(role);

        JPanel row = new JPanel(new FlowLayout(user ? FlowLayout.RIGHT : FlowLayout.LEFT, 0, 7));
        row.setOpaque(false);

        RoundedPanel bubbleWrap = new RoundedPanel(user ? accentColor() : error ? dangerSoftColor() : thinking ? thinkingBackgroundColor() : cardBackground(), 18);
        bubbleWrap.setLayout(new BorderLayout(0, 6));
        bubbleWrap.setBorder(new EmptyBorder(10, 12, 10, 12));

        JLabel roleLabel = new JLabel(user ? "You" : error ? "错误" : thinking ? "AI 思考" : "听风 Agent");
        roleLabel.setForeground(user ? new Color(255, 245, 235) : mutedTextColor());
        roleLabel.setFont(roleLabel.getFont().deriveFont(Font.BOLD, 11f));
        roleLabel.setHorizontalAlignment(user ? SwingConstants.RIGHT : SwingConstants.LEFT);

        JTextArea bubble = new JTextArea(text);
        bubble.setEditable(false);
        bubble.setLineWrap(true);
        bubble.setWrapStyleWord(true);
        bubble.setFont(UIManager.getFont("Label.font").deriveFont(13f));
        bubble.setColumns(68);
        bubble.setOpaque(false);
        bubble.setForeground(foregroundForRole(role));
        bubble.setBorder(BorderFactory.createEmptyBorder());

        bubbleWrap.add(roleLabel, BorderLayout.NORTH);
        bubbleWrap.add(bubble, BorderLayout.CENTER);
        bubbleWrap.setMaximumSize(new Dimension(860, Integer.MAX_VALUE));

        row.add(bubbleWrap);
        chatList.add(row);
        refresh(chatList);
        scrollToBottom(chatList);
    }


    private JTextArea addStreamingChatBubble(String role, String initialText) {
        boolean user = "user".equals(role);
        boolean error = "error".equals(role);
        boolean thinking = "thinking".equals(role);

        JPanel row = new JPanel(new FlowLayout(user ? FlowLayout.RIGHT : FlowLayout.LEFT, 0, 7));
        row.setOpaque(false);

        RoundedPanel bubbleWrap = new RoundedPanel(user ? accentColor() : error ? dangerSoftColor() : thinking ? thinkingBackgroundColor() : cardBackground(), 18);
        bubbleWrap.setLayout(new BorderLayout(0, 6));
        bubbleWrap.setBorder(new EmptyBorder(10, 12, 10, 12));

        JLabel roleLabel = new JLabel(user ? "You" : error ? "错误" : thinking ? "AI 思考" : "听风 Agent");
        roleLabel.setForeground(user ? new Color(255, 245, 235) : mutedTextColor());
        roleLabel.setFont(roleLabel.getFont().deriveFont(Font.BOLD, 11f));
        roleLabel.setHorizontalAlignment(user ? SwingConstants.RIGHT : SwingConstants.LEFT);

        JTextArea bubble = new JTextArea(initialText == null ? "" : initialText);
        bubble.setEditable(false);
        bubble.setLineWrap(true);
        bubble.setWrapStyleWord(true);
        bubble.setFont(UIManager.getFont("Label.font").deriveFont(13f));
        bubble.setColumns(68);
        bubble.setOpaque(false);
        bubble.setForeground(foregroundForRole(role));
        bubble.setBorder(BorderFactory.createEmptyBorder());

        bubbleWrap.add(roleLabel, BorderLayout.NORTH);
        bubbleWrap.add(bubble, BorderLayout.CENTER);
        bubbleWrap.setMaximumSize(new Dimension(860, Integer.MAX_VALUE));

        row.add(bubbleWrap);
        chatList.add(row);
        refresh(chatList);
        scrollToBottom(chatList);
        return bubble;
    }


    private ThoughtStreamUi addThoughtStreamingBubbles() {
        ThoughtStreamUi ui = new ThoughtStreamUi();
        ui.thoughtBubble = addStreamingChatBubble("thinking", "AI 思考：等待模型返回推理内容...");
        ui.answerBubble = addStreamingChatBubble("assistant", "");
        return ui;
    }

    private void appendTypedStreamDelta(ThoughtStreamUi ui, String type, String delta) {
        if (ui == null || delta == null || delta.isEmpty()) {
            return;
        }
        if ("reasoning".equals(type)) {
            synchronized (ui) {
                ui.reasoningBuffer.append(delta);
            }
            SwingUtilities.invokeLater(() -> updateThoughtAndAnswer(ui));
            return;
        }
        synchronized (ui) {
            ui.rawContent.append(delta);
        }
        SwingUtilities.invokeLater(() -> updateThoughtAndAnswer(ui));
    }

    private void updateThoughtAndAnswer(ThoughtStreamUi ui) {
        if (ui == null) {
            return;
        }
        String raw;
        String reasoning;
        synchronized (ui) {
            raw = ui.rawContent.toString();
            reasoning = ui.reasoningBuffer.toString();
        }
        ThoughtSplit split = splitThoughtText(raw);
        String combinedThought = joinNonBlank(reasoning, split.thought.toString());
        synchronized (ui) {
            ui.answerBuffer.setLength(0);
            ui.answerBuffer.append(split.answer);
            ui.visibleThought.setLength(0);
            ui.visibleThought.append(combinedThought);
        }
        if (combinedThought.trim().isEmpty()) {
            updateStreamBubble(ui.thoughtBubble, "AI 思考：模型尚未返回独立思考内容...");
        } else {
            updateStreamBubble(ui.thoughtBubble, "AI 思考：\n" + combinedThought.trim());
        }
        updateStreamBubble(ui.answerBubble, split.answer.toString());
    }

    private String finalAnswerFromStream(ThoughtStreamUi ui, String returnedText) {
        updateThoughtAndAnswer(ui);
        String answer;
        synchronized (ui) {
            answer = ui.answerBuffer.toString();
        }
        if (!answer.trim().isEmpty()) {
            return answer.trim();
        }
        ThoughtSplit split = splitThoughtText(returnedText == null ? "" : returnedText);
        if (!split.answer.toString().trim().isEmpty()) {
            return split.answer.toString().trim();
        }
        if (returnedText != null && !returnedText.trim().isEmpty()) {
            return returnedText.trim();
        }
        return "【任务暂停】\n\n模型没有返回可执行工具调用或最终结论。";
    }

    private void finalizeThoughtBubble(ThoughtStreamUi ui) {
        if (ui == null) {
            return;
        }
        String thought;
        synchronized (ui) {
            thought = ui.visibleThought.toString();
        }
        if (thought.trim().isEmpty()) {
            updateStreamBubble(ui.thoughtBubble, "AI 思考：模型未返回独立思考内容；已直接输出最终回答。\n提示：DeepSeek/Qwen 等模型输出 <think>...</think> 或 reasoning_content 时，这里会实时显示。 ");
        }
    }

    private ThoughtSplit splitThoughtText(String raw) {
        ThoughtSplit out = new ThoughtSplit();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        int index = 0;
        while (index < raw.length()) {
            TagOpen open = findNextThoughtOpen(lower, index);
            if (open == null) {
                out.answer.append(raw.substring(index));
                break;
            }
            out.answer.append(raw, index, open.start);
            int contentStart = open.end;
            int closeStart = lower.indexOf(open.closeTag, contentStart);
            if (closeStart < 0) {
                out.thought.append(raw.substring(contentStart));
                break;
            }
            out.thought.append(raw, contentStart, closeStart).append('\n');
            index = closeStart + open.closeTag.length();
        }
        return out;
    }

    private TagOpen findNextThoughtOpen(String lower, int from) {
        String[][] tags = {
                {"<think>", "</think>"},
                {"<thinking>", "</thinking>"},
                {"<reasoning>", "</reasoning>"},
                {"<analysis>", "</analysis>"}
        };
        TagOpen best = null;
        for (String[] tag : tags) {
            int start = lower.indexOf(tag[0], from);
            if (start >= 0 && (best == null || start < best.start)) {
                best = new TagOpen(start, start + tag[0].length(), tag[1]);
            }
        }
        return best;
    }

    private String joinNonBlank(String left, String right) {
        String a = left == null ? "" : left.trim();
        String b = right == null ? "" : right.trim();
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        return a + "\n" + b;
    }

    private static final class ThoughtStreamUi {
        JTextArea thoughtBubble;
        JTextArea answerBubble;
        final StringBuilder rawContent = new StringBuilder();
        final StringBuilder reasoningBuffer = new StringBuilder();
        final StringBuilder answerBuffer = new StringBuilder();
        final StringBuilder visibleThought = new StringBuilder();
    }

    private static final class ThoughtSplit {
        final StringBuilder thought = new StringBuilder();
        final StringBuilder answer = new StringBuilder();
    }

    private static final class TagOpen {
        final int start;
        final int end;
        final String closeTag;

        TagOpen(int start, int end, String closeTag) {
            this.start = start;
            this.end = end;
            this.closeTag = closeTag;
        }
    }

    private void appendStreamDelta(JTextArea bubble, StringBuilder buffer, String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        final String text;
        synchronized (buffer) {
            buffer.append(delta);
            text = buffer.toString();
        }
        SwingUtilities.invokeLater(() -> updateStreamBubble(bubble, text));
    }

    private void updateStreamBubble(JTextArea bubble, String text) {
        if (bubble == null) {
            return;
        }
        bubble.setText(text == null ? "" : text);
        refresh(chatList);
        scrollToBottom(chatList);
    }

    private void addToolCard(String tool, String action, boolean success, String detail) {
        RoundedPanel card = new RoundedPanel(cardBackground(), 16);
        card.setLayout(new BorderLayout(10, 8));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 4, 0, 0, success ? successColor() : dangerColor()),
                new EmptyBorder(10, 12, 10, 12)
        ));

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);

        JLabel title = new JLabel(tool + "." + action);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
        title.setForeground(textColor());

        JLabel status = new JLabel(success ? " SUCCESS " : " FAILED ");
        status.setOpaque(true);
        status.setFont(status.getFont().deriveFont(Font.BOLD, 10f));
        status.setForeground(Color.WHITE);
        status.setBackground(success ? successColor() : dangerColor());
        status.setBorder(new EmptyBorder(3, 7, 3, 7));

        top.add(title, BorderLayout.WEST);
        top.add(status, BorderLayout.EAST);

        JTextArea body = new JTextArea(detail);
        body.setEditable(false);
        body.setLineWrap(true);
        body.setWrapStyleWord(true);
        body.setFont(contentFont(12f));
        body.setOpaque(false);
        body.setForeground(mutedTextColor());
        body.setBorder(new EmptyBorder(2, 0, 0, 0));

        card.add(top, BorderLayout.NORTH);
        card.add(body, BorderLayout.CENTER);

        toolList.add(card);
        toolList.add(Box.createVerticalStrut(8));
        refresh(toolList);
        scrollToBottom(toolList);
    }

    private JPanel roundedPanel() {
        RoundedPanel panel = new RoundedPanel(panelColor(), 18);
        panel.setBorder(new EmptyBorder(15, 15, 15, 15));
        return panel;
    }

    private JPanel verticalPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(appBackground());
        return panel;
    }

    private JPanel sectionHeader(String titleText, String subtitleText) {
        JPanel box = new JPanel();
        box.setOpaque(false);
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));

        JLabel title = new JLabel(titleText);
        title.setForeground(textColor());
        title.setFont(title.getFont().deriveFont(Font.BOLD, 17f));

        JLabel subtitle = new JLabel(subtitleText);
        subtitle.setForeground(mutedTextColor());
        subtitle.setFont(subtitle.getFont().deriveFont(Font.PLAIN, 12f));
        subtitle.setBorder(new EmptyBorder(5, 0, 0, 0));

        box.add(title);
        box.add(subtitle);
        return box;
    }

    private JLabel createHeroBadge(String label, String value) {
        JLabel badge = new JLabel(label + "  " + value);
        badge.setOpaque(true);
        badge.setForeground(Color.WHITE);
        badge.setBackground(new Color(255, 255, 255, 42));
        badge.setBorder(new EmptyBorder(6, 10, 6, 10));
        badge.setFont(badge.getFont().deriveFont(Font.BOLD, 11f));
        return badge;
    }

    private void styleTextField(JTextField field) {
        field.setColumns(34);
        field.setPreferredSize(new Dimension(330, 42));
        field.setMinimumSize(new Dimension(260, 42));
        field.setBackground(inputBackground());
        field.setForeground(textColor());
        field.setCaretColor(textColor());
        field.setToolTipText(field.getText());
        field.setBorder(BorderFactory.createCompoundBorder(
                new RoundedBorder(borderColor(), 14),
                new EmptyBorder(7, 10, 7, 10)
        ));
    }

    private void stylePrimaryButton(JButton button) {
        styleButtonBase(button);
        button.setBackground(accentColor());
        button.setForeground(Color.WHITE);
    }

    private void styleSecondaryButton(JButton button) {
        styleButtonBase(button);
        button.setBackground(cardBackground());
        button.setForeground(textColor());
    }

    private void styleDangerButton(JButton button) {
        styleButtonBase(button);
        button.setBackground(dangerColor());
        button.setForeground(Color.WHITE);
    }

    private void styleButtonBase(JButton button) {
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setFont(button.getFont().deriveFont(Font.BOLD, 12f));
        button.setBorder(new EmptyBorder(8, 15, 8, 15));
        try {
            button.setUI(new BasicButtonUI());
        } catch (RuntimeException ignored) {
        }
    }

    private Color foregroundForRole(String role) {
        if ("user".equals(role)) return Color.WHITE;
        if ("error".equals(role)) return isDarkTheme() ? new Color(255, 218, 218) : new Color(132, 24, 24);
        if ("thinking".equals(role)) return thinkingTextColor();
        return textColor();
    }

    private Color panelColor() { return isDarkTheme() ? new Color(32, 35, 42) : new Color(250, 251, 253); }
    private Color cardBackground() { return isDarkTheme() ? new Color(43, 47, 56) : Color.WHITE; }
    private Color inputBackground() { return isDarkTheme() ? new Color(24, 26, 32) : new Color(247, 248, 250); }
    private Color appBackground() { return isDarkTheme() ? new Color(20, 22, 27) : new Color(238, 241, 245); }
    private Color borderColor() { return isDarkTheme() ? new Color(68, 73, 84) : new Color(214, 219, 226); }
    private Color textColor() { return isDarkTheme() ? new Color(238, 241, 246) : new Color(28, 31, 36); }
    private Color mutedTextColor() { return isDarkTheme() ? new Color(166, 173, 187) : new Color(94, 101, 114); }
    private Color accentColor() { return new Color(255, 102, 0); }
    private Color accentDarkColor() { return new Color(210, 75, 0); }
    private Color successColor() { return new Color(46, 160, 67); }
    private Color dangerColor() { return new Color(218, 54, 51); }
    private Color dangerSoftColor() { return isDarkTheme() ? new Color(84, 38, 38) : new Color(255, 232, 232); }
    private Color thinkingBackgroundColor() { return isDarkTheme() ? new Color(34, 48, 70) : new Color(232, 243, 255); }
    private Color thinkingTextColor() { return isDarkTheme() ? new Color(204, 226, 255) : new Color(25, 74, 125); }

    private boolean isDarkTheme() {
        Color bg = UIManager.getColor("Panel.background");
        if (bg == null) return true;
        int brightness = (bg.getRed() * 299 + bg.getGreen() * 587 + bg.getBlue() * 114) / 1000;
        return brightness < 128;
    }

    private void refresh(Component component) {
        component.revalidate();
        component.repaint();
    }

    private void scrollToBottom(JPanel panel) {
        SwingUtilities.invokeLater(() -> panel.scrollRectToVisible(new java.awt.Rectangle(0, panel.getHeight(), 1, 1)));
    }

    private Font contentFont(float size) {
        Font base = UIManager.getFont("Label.font");
        if (base == null) {
            base = new Font("Dialog", Font.PLAIN, 12);
        }
        return base.deriveFont(size);
    }

    private void setBusy(boolean busy) {
        sendButton.setEnabled(!busy);
        connectButton.setEnabled(!busy);
        refreshToolsButton.setEnabled(!busy);
        configToolsButton.setEnabled(true);
        sendButton.setText(busy ? "思考中..." : "发送");
        stopButton.setEnabled(busy);
        stopButton.setText("中断");
        if (!busy) {
            activeWorker = null;
            stopRequested = false;
        }
    }

    private void updateEndpointLabels() {
        if (endpointStatusLabel != null) {
            String endpoint = normalizeEndpoint(endpointField.getText().trim());
            endpointStatusLabel.setText("当前 AI API：" + abbreviateMiddle(endpoint, 54));
            endpointStatusLabel.setToolTipText(endpoint);
        }
        if (mcpStatusLabel != null) {
            String mcp = mcpUrlField.getText().trim();
            mcpStatusLabel.setText("当前官方 MCP：" + abbreviateMiddle(mcp, 54));
            mcpStatusLabel.setToolTipText(mcp);
        }
        if (endpointField != null) {
            endpointField.setToolTipText(endpointField.getText());
        }
        if (mcpUrlField != null) {
            mcpUrlField.setToolTipText(mcpUrlField.getText());
        }
    }

    private String abbreviateMiddle(String value, int maxLength) {
        String text = safe(value);
        if (text.length() <= maxLength) {
            return text;
        }
        int keep = Math.max(8, (maxLength - 3) / 2);
        int tail = Math.max(8, maxLength - keep - 3);
        return text.substring(0, Math.min(keep, text.length())) + "..." + text.substring(Math.max(0, text.length() - tail));
    }

    private String nonBlank(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private String prefString(String key, String defaultValue) {
        try {
            Object value = invokePreference("getString", new Class<?>[]{String.class}, key);
            return value == null ? defaultValue : String.valueOf(value);
        } catch (RuntimeException e) {
            return defaultValue;
        }
    }

    private boolean prefBoolean(String key, boolean defaultValue) {
        try {
            Object value = invokePreference("getBoolean", new Class<?>[]{String.class}, key);
            if (value == null) {
                return defaultValue;
            }
            if (value instanceof Boolean) {
                return (Boolean) value;
            }
            return Boolean.parseBoolean(String.valueOf(value));
        } catch (RuntimeException e) {
            return defaultValue;
        }
    }

    private void prefSetString(String key, String value) {
        try {
            invokePreference("setString", new Class<?>[]{String.class, String.class}, key, value);
        } catch (RuntimeException ignored) {
            // Preferences are optional. The UI should still run if persistence API differs.
        }
    }

    private void prefSetBoolean(String key, boolean value) {
        try {
            invokePreference("setBoolean", new Class<?>[]{String.class, boolean.class}, key, value);
        } catch (RuntimeException first) {
            try {
                invokePreference("setBoolean", new Class<?>[]{String.class, Boolean.class}, key, Boolean.valueOf(value));
            } catch (RuntimeException ignored) {
                // Preferences are optional. The UI should still run if persistence API differs.
            }
        }
    }

    private void prefDelete(String key) {
        try {
            invokePreference("delete", new Class<?>[]{String.class}, key);
        } catch (RuntimeException ignored) {
            // Preferences are optional. The UI should still run if persistence API differs.
        }
    }

    private Object invokePreference(String methodName, Class<?>[] parameterTypes, Object... args) {
        try {
            Object persistence = invokeNoArg(api, "persistence");
            Object preferences = invokeNoArg(persistence, "preferences");
            Method method = findMethod(preferences.getClass(), methodName, parameterTypes);
            return method.invoke(preferences, args);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private void applyBurpThemeCompat(Component component) {
        try {
            Object ui = invokeNoArg(api, "userInterface");
            Method method = findMethod(ui.getClass(), "applyThemeToComponent", Component.class);
            method.invoke(ui, component);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Theme API differs between Montoya versions; default Swing theme is acceptable.
        }
    }

    private Object invokeNoArg(Object target, String methodName) throws ReflectiveOperationException {
        Method method = findMethod(target.getClass(), methodName);
        return method.invoke(target);
    }

    private Method findMethod(Class<?> type, String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        Class<?> current = type;
        while (current != null) {
            try {
                Method method = current.getMethod(name, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        for (Class<?> iface : type.getInterfaces()) {
            try {
                Method method = iface.getMethod(name, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                // keep searching
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }

    private String safe(String value) { return value == null ? "" : value; }

    private static final class McpToolCall {
        String name;
        ObjectNode arguments;
        String comment;
    }

    private final class GradientPanel extends JPanel {
        GradientPanel() { setOpaque(false); }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                GradientPaint paint = new GradientPaint(0, 0, accentColor(), getWidth(), getHeight(), accentDarkColor());
                g.setPaint(paint);
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 24, 24);
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.16f));
                g.setColor(Color.WHITE);
                g.fillOval(getWidth() - 180, -80, 260, 220);
                g.fillOval(getWidth() - 340, 60, 180, 110);
            } finally {
                g.dispose();
            }
            super.paintComponent(graphics);
        }
    }

    private static final class RoundedPanel extends JPanel {
        private final Color background;
        private final int arc;

        RoundedPanel(Color background, int arc) {
            this.background = background;
            this.arc = arc;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(background);
                g.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
            } finally {
                g.dispose();
            }
            super.paintComponent(graphics);
        }
    }

    private static final class RoundedBorder extends AbstractBorder {
        private final Color color;
        private final int arc;

        RoundedBorder(Color color, int arc) {
            this.color = color;
            this.arc = arc;
        }

        @Override
        public void paintBorder(Component component, Graphics graphics, int x, int y, int width, int height) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(color);
                Shape border = new RoundRectangle2D.Double(x, y, width - 1, height - 1, arc, arc);
                g.draw(border);
            } finally {
                g.dispose();
            }
        }

        @Override
        public Insets getBorderInsets(Component component) { return new Insets(1, 1, 1, 1); }

        @Override
        public Insets getBorderInsets(Component component, Insets insets) {
            insets.left = 1;
            insets.right = 1;
            insets.top = 1;
            insets.bottom = 1;
            return insets;
        }
    }
}
