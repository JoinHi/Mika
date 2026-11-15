# 个人助手实时语音（Mika Live）

在[个人助手模式](personal-agent.md)之上提供全双工实时语音与实时虚拟屏舞台，由 Gemini Live WebSocket 原生音频驱动，沿用个人主会话的聊天记录、记忆、用量记账与安全审批边界。

## 分层与会话生命周期

- `core/live/GeminiLiveProtocol.kt` 与 `core/live/GeminiLiveClient.kt` 负责 BidiGenerateContent 协议编解码、模型列表分页拉取与 WebSocket 收发。客户端复用宿主连接池，入站控制事件与音频分片分离计数（拥塞时仅丢弃排队音频，绝不丢弃控制、转写、工具调用或用量帧），并对出站媒体载荷设上限。
- `core/live/LiveTurnCoordinator.kt` 纯函数协调插话窗口、后台任务完成/审批通知注入与静默挂断策略（`LiveSessionPolicy`）。
- `app/personal/live/PersonalLiveRuntime.kt` 为每次通话创建独立的会话协程作用域，集中管理连接重连、电话占线暂挂、唤醒词让位与恢复、`UsageLedger` 用量落盘及每日预算门禁。
- `LiveSessionToolTracker` 按调用 ID 追踪在途工具协程：收到取消帧时立即中断对应协程、跳过尚未启动的排队调用并抑制已取消调用的回包，已完成动作的记录予以保留。
- `LiveUserTextDeliverer` 统一处理通话期间的文字输入：连接或重连窗口内单次落盘并排队冲刷；发送失败或电话暂挂时直接抛错，不退回普通文字模型重复落盘。
- `app/personal/live/LiveDuplexAudioEngine.kt` 与 `app/personal/live/PersonalLiveService.kt` 管理全双工采集播放、硬件回声消除与前台常驻，原生音频句柄由各自采集/播放读写协程在退出时释放。

## 执行路径与极速对比开关

设置中的 `turboControl` 开关支持在真机上直接对比两种执行路径：

- **标准对比模式（关闭）**：仅暴露个人助手标准委派能力（delegate_task、revise_task、cancel_task、answer_task 等），所有手机操作经 `PersonalActions` 与 `TaskService` 进入完整 `TaskRun`，使用默认 `PhoneWaitProfile`。
- **极速操控模式（开启）**：在保留后台长任务委派的同时开放低延迟单步与短批手机操作（quick_phone_act、quick_phone_read），由 `app/personal/live/LiveDirectPhoneController.kt` 在独立虚拟屏上执行，并显式传入 `app/cap/phone/a11y/PhoneWaitProfile.kt` 的极速等待参数。等待配置只管理界面稳定与动画等待时长，不影响无关后台任务，也不耦合意图判断或工具开关。

## 安全边界、资源调度与人工审批

`LiveDirectPhoneController` 复用 Mika 现有的单入口与设备治理约束，不绕过安全关卡：

- **统一资源互斥**：通过 `app/task/ResourceScheduler.kt` 登记直控占用的目标包名，并在虚拟屏上开启跨任务占用检查；一旦 `TaskRun` 启动或进入示范录制，直控立即让出虚拟屏与目标应用。
- **逐动作实时门禁**：直控向 `ActionExecutor` 注入逐动作门禁，每次写动作前核对 `DeviceStateWatcher` 的锁屏、Shell 连接、应用切走状态以及用户在实时舞台上的触控接管；话轮切换时重置 `OperationGuards`，外发动作保持 `OutwardPacing` 节流，短信等私密界面继续受 `SmsPrivateScreen` 遮罩保护。
- **仅限界面人工审批**：语音转写与模型参数均不能放行高风险或不可逆操作。直控以批处理来源运行模型步骤，遇到敏感动作立即拦截并经 `core/live/LiveDirectConfirmation.kt` 生成单次审批请求（`LiveConfirmationPrompt`），在界面确认卡片上交由用户本人点击批准或拒绝；后台任务的待批请求同样汇总到该卡片，只在用户点击按钮后放行对应单次操作。

## 联网检索与深度推理

- `app/personal/live/LiveWebSearchRunner.kt` 严格遵守 `WebSearchSettings` 的总开关与用户选定的搜索服务商，关闭或失败时如实返回原因，不暗自回退到未配置的服务商；检索结果的标题、链接与摘要均经 `Untrusted` 包裹后再交给模型。
- `app/personal/live/LiveDeepThinkRunner.kt` 复用用户已配置的推理模型处理复杂分析问题，仅在联网搜索已开启时挂载搜索能力，不直接抓取任意网页地址，避免服务端重定向探测内网风险。

## 验证

核心与宿主单测覆盖：协议帧编解码、音频拥塞保控制帧、工具调用取消中断、跨重连文字单次落盘与防双路回退、极速等待参数隔离、直控逐动作锁屏/断连/触控门禁、后台任务抢占让位、高风险动作拦截与单次界面审批、搜索开关遵从与不可信包裹。真机重点验收双工回声消除、锁屏拦截、舞台触控接管与开关切换体验。
