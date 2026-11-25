# 手机助手唤起后，模型实际会看到什么？

**仅唤起、不说话、不发送，也没有需要恢复的在途任务：没有聊天模型请求，输入为 0 token。** 面板可以在本机读取上次会话的标识、接收 Android 允许共享的页面和截图。它们不是一次空白的模型请求，打开面板也不会把整张截图发出去。

## 看第一次发送时的完整请求

- [Token 分项和计量口径](token-report.md)：分别看系统正文、工具定义、输入消息以及按需指南。
- [普通首轮：没有可共享的页面](first-phone-request.json)。新会话只发送「你好」；包含完整的 `instructions`、26 个工具的名称、描述及 JSON Schema，以及 Responses 协议参数。
- [同一首轮：系统提供“设置”页的调用快照](first-phone-shared-screen-request.json)。工具数为 27；多出的 `read_screen` 可以按需读取本次共享内容。用户消息增加前台应用名和静态快照说明；这次请求里没有页面正文和图像字节。「设置」是样例应用名。
- [只读系统正文](instructions.txt)。这是第一份 JSON 里的 `instructions` 原值，不含工具定义；只数这份文本会漏算首轮的实际输入。

这些是用当时的生产代码和合成会话生成的**首轮请求样本**，不是从私人会话截取的网络包。采用普通、未托管的手机助手会话：模型 `gpt-6.2-sol`、`medium`、Responses 协议、自定义连接名「自定义端点」；合成时钟固定为 `2000-01-01 12:00:00 UTC`；保留默认自动搜索深度、默认图片频率、已启用的记忆功能和提醒工具。无个人指令、已保存记忆条目、私人搜索密钥、消息历史或工具结果。「你好」只是为了触发真实请求结构，不是唤起动作自带的文字。

## 哪些规则占常驻空间？

[富回复规则](rich-rules.txt)已经包含在系统正文中，不能在总数上再加一次。它提供基础的图表选择原则和组件语法。完整的 [图表指南](visual-guide-chart.txt) 和 [动态组件指南](visual-guide-interactive.txt) 在模型调用 `visual_guide` 后才作为工具结果进入会话；[一次请求两个模块的结果](visual-guide-chart-interactive.txt)中公共 Basics 只出现一次。

系统正文除了常驻角色和回复规则，还包括研究深度、图片策略、搜索后端、实际可用的工具分类索引、当前模型及连接名。这两份样本的搜索配置说明在两个章节都出现；保留了这个真实开销。工具索引之外，请求中的 `tools` 还要携带完整定义。传输 JSON 的格式缩进、HTTP 认证头和 APK 文件都不是提示正文。

## 生成依据

1. `HarnessAssistantSession.onShow` → `AssistantController.begin` 打开/恢复面板；`AssistantController.send` 会拒绝空白输入。有问题后才创建普通 PHONE 会话并进入 `RunManager.startRun`。
2. 用构建出的生产类执行 `AgentEngine.chatSystemPrompt`、`runtimeEnvironment` 和模型标识生成逻辑，按 `roundRequest` 顺序追加运行时搜索配置。空历史没有读图清单、计划或待注入的插件指令。普通 PHONE 不进入 Code 的项目文件、终端或仓库索引提示。
3. 按该分支的 `ChatExperience.tools`、默认开关和面板提议工具构建实际工具对象；调用生产 `ToolRegistry.schemas` 过滤并序列化能力。未执行任何工具、未提交远端请求。两份样本只切换 `SharedScreens` 的可用性以及对应的前台页说明。
4. 通过 `OpenAiResponsesProvider.buildRequestBody` 获得协议结构；归档时将时间字段替换为上述固定测试值。JSON 包括 `reasoning.effort` 和排序后的完整工具定义。无认证头或凭据写入这些文件。

开启托管手机任务、恢复有历史的会话、配置个人指令、保存记忆、改变供应商/搜索配置，都会改变首个实际请求。
