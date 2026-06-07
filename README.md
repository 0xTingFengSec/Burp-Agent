# TingFeng Burp Agent

听风 Burp Agent 是一个 Burp Suite AI Agent 插件。它通过 **PortSwigger 官方 Burp MCP SSE Server** 操作 Burp，让你可以用自然语言让 AI 读取流量、调用工具、分析响应、加载 Skills，并输出结构化安全测试结论。

> 仅用于授权安全测试、学习研究与合法的安全评估场景。

## 组件搭配

本项目建议与官方 Burp MCP 一起使用：

1. 在 Burp 中加载官方 MCP 插件或启动 PortSwigger MCP Server。
2. 确认官方 MCP SSE 地址可用，默认：`http://127.0.0.1:9876`。
3. 加载 `TingFeng-Burp-Agent-SkillPack.jar`。
4. 在插件【设置】中填写 AI Endpoint、Model、API Key、官方 MCP SSE URL。
5. 点击【连接官方 MCP】和【刷新工具】。

## 主要能力

- AI 流式对话。
- 官方 Burp MCP 工具调用。
- 工具调用记录与响应分析。
- 主动工具确认。
- 中断按钮。
- AI 思考内容颜色区分显示。
- SchemaGuard：按照官方 `tools/list` 的 `inputSchema` 清洗参数，减少 unknown key 错误。
- ResponseGuard：发包、读取响应、分析响应流程约束。
- Skills 加载、导入、重载、模板查看和目录管理。
- 内置 HackSkills Skill Pack。

## Skills 支持格式

插件支持以下 Skill 输入：

- `.md`
- `.markdown`
- `.txt`
- `.yaml`
- `.yml`
- `.json`
- `.skill`
- `.zip`
- 目录导入

推荐标准格式是：**YAML front matter + Markdown 正文**。

```markdown
---
name: web-pentest-flow
description: 用于授权 Web 渗透测试的流程化 Skill，强调被动优先、范围确认和证据沉淀。
version: 1.0.0
author: 听风sec
tags: [burp, mcp, web, pentest, authorized]
triggers: [流量分析, 漏洞验证, 越权测试, 敏感信息检查, 报告输出]
tools: [get_proxy_http_history, get_site_map, check_scope, text_diff, send_http1_request]
---

# Web Pentest Flow

## 使用边界

仅用于用户明确授权的目标和测试环境。不确定范围时必须先询问。

## 执行流程

1. 确认目标、Scope、测试账号和禁止动作。
2. 优先使用只读 MCP 工具梳理 Proxy History 与 Site Map。
3. 识别认证、授权、输入点、敏感信息和高价值接口。
4. 需要主动请求时先说明原因并等待确认。
5. 每一步记录证据、风险、复现条件和修复建议。
```

## HackSkills 支持

本版本已经内置 `hack-skills-main.zip`，启动时会自动加载其中 `skills/**/SKILL.md`。你也可以在【设置】→【Skills】中手动导入 HackSkills ZIP 包或其他 Skill Pack ZIP。

ZIP 解析策略：

- 优先解析 `SKILL.md`、`SKILL.markdown`、`*.skill.md`、`*.skill`。
- 支持 ZIP 内多级目录。
- 支持 UTF-8、UTF-8 BOM、GB18030/GBK 编码兼容。
- 对非标准 Markdown/TXT/YAML/JSON，会自动包装成可用 Skill。
- 大文件和二进制资源会被跳过。

## 乱码修复

本版本修复了【查看模板】与 Skills 加载时的中文乱码问题：

- 源码和资源统一 UTF-8。
- Skill 文件读取优先 UTF-8，失败后回退 GB18030。
- Skill 模板窗口使用支持中文的 UI 字体，不再强制使用纯英文等宽字体。
- Tool Calls 与 Skills 展示区使用中文友好的字体。

## 使用建议

### 查看流量

```text
帮我查看最近 20 条代理流量，并总结可疑接口。
```

### 分析敏感信息

```text
帮我检查最近 50 条流量有没有 token、JWT、密钥、内部 IP 或调试信息泄露。
```

### 发包读取响应

```text
在授权测试范围内，把这个 HTTP/1.1 请求发出去，读取响应并分析是否存在敏感信息或越权风险。
```

### Repeater 场景

```text
把这个请求创建到 Repeater，等我运行后你再帮我分析响应。
```

## 注意事项

- 听风 Burp Agent 本身不启动本地 MCP Server，只连接你配置的官方 Burp MCP SSE。
- 主动请求、重放、Intruder、修改 Scope、启停拦截等动作应先确认。
- 官方 MCP 工具参数以 `tools/list` 返回的 schema 为准。
- 如果官方 MCP 未启动、Burp 版本不兼容、目标不可达或工具未暴露，插件会给出错误提示。

## 开源信息

- 项目名：TingFeng Burp Agent
- 作者：听风sec
- 微信：Yunaaaa8888
- License：Apache-2.0
