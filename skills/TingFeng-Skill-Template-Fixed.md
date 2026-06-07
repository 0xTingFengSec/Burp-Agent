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

## 结束条件

当用户问题已经回答、证据充分、继续调用工具没有新增价值时，必须输出【任务完成】并停止。
