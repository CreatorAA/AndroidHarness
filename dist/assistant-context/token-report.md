# 手机助手的第一句话占多少上下文？

只打开新面板而不发送消息、也不恢复在途任务，不会发起聊天模型请求。发送「你好」后，仅这两个字是 **1 token**；首轮请求还要携带系统指令和当时可用工具的完整定义。

## 两份合成首轮请求

两个样本共用固定的 UTC 时钟，使用 `gpt-6.2-sol`、Medium、普通 Phone 会话，无历史消息和已保存的个人记忆。共享页样本只增加「设置」这一示例应用名、静态页面标记和一个按需读取页面的工具，不包含截图像素。[样本生成条件](context-source.md)

| 请求正文 | 系统指令 | 工具定义 | 输入消息 | 本地分项合计 |
|---|---:|---:|---:|---:|
| [无共享页面](first-phone-request.json) | 6,312 | 5,240（26 个） | 20 | **11,572** |
| [共享“设置”页面标记](first-phone-shared-screen-request.json) | 6,316 | 5,346（27 个） | 78 | **11,740** |

共享页样本多 **168** 个 token：系统指令 +4，工具 +106，输入消息 +58。输入栏包含 Responses 消息结构；它不是只对「你好」分词。

## 富回复规则和按需指南

无共享页面时，[常驻富回复指引](rich-rules.txt)占 **3,137** 个 token，即系统指令的 49.7%、分项合计的 27.1%。其中选择呈现形式占 373，原生组件目录、语法和用例占 1,968。单算 `visual_guide` 的工具名、说明和参数定义是 187，已含在工具定义里。

首次请求没有携带按需指南的正文。调用后，[图表指南](visual-guide-chart.txt)返回 1,240 个 token，[交互指南](visual-guide-interactive.txt)返回 1,084 个；[一次取两个模块](visual-guide-chart-interactive.txt)为 1,483 个，因为 841 个 token 的基础说明只返回一次。这里计返回文本，不计工具消息的外层结构。

## 在本机复算

在本目录使用 tiktoken 0.14.0 运行：

```sh
python count_context.py --snapshot first-phone-request.json
python count_context.py --snapshot first-phone-shared-screen-request.json
```

结果分别对应[无共享页面明细](default-token-counts.json)和[共享页面明细](shared-screen-token-counts.json)。`--snapshot` 使用这里归档的常驻规则和指南正文，而不是以后可能改变的源码。tiktoken 0.14.0 没有为该模型声明 tokenizer 映射，因此这组测量采用 `o200k_base`。系统指令按原文编码；工具列表和输入消息各按紧凑 JSON 编码。三项的和用于比较请求内容，不等于接口账单上的输入 token 数。
