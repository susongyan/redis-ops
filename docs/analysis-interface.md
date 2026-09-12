# 分析接口交互与交付边界

状态：当前交付为无启用开关、手动触发、同步返回的辅助分析。不是后台自动巡检或自主执行 Agent。
规则分析、HTTP/JSON、项目自定义 ACP 风格适配器、结果保存与注册资料管理已有实现。
动态启停、注册资料驱动运行时、A2A、外部认证与特定 ACP 标准兼容性未实现/未验收，不作为生产承诺。

## 页面到 Platform

`POST /api/v1/analysis` 必须带 `Idempotency-Key`，请求格式如下：

```json
{
  "type": "SYNC",
  "resourceType": "SYNC_TASK",
  "resourceId": "5",
  "facts": {"status": "FAILED", "rpoSeconds": "30"},
  "evidence": [{"reference": "sync-task:5", "kind": "SYNC_TASK", "summary": "同步任务失败"}],
  "incidentRefs": []
}
```

type 为 ALERT/SYNC/VALIDATION/RISK_SCAN/INCIDENT。facts 是字符串键值，最多 100 项，每值最多 1000 字符；
顺序规范化后参与幂等摘要。禁止秘密字段，禁止将 Redis 完整 Key/value、连接串、密码或密钥当作上下文。
证据最多 100 项，reference 最多 512 字符、summary 最多 1000 字符；incidentRefs 最多 20 项，每项 256 字符。
输入仅是受控页面提交的观测快照，不能作为执行授权或可信事实源；服务端尚未实现按资源 ID 重建全部证据。

同一操作者、同一幂等键、相同请求重放已保存结果；变更请求使用同一键返回冲突。
成功前的数据库事务回滚不能保证远端从未计算过，外部调用只能承担无副作用分析，不能执行生产操作。
结果创建后不可变，无更新接口，因此该 POST 不要求 If-Match；注册资料更新仍要求版本和幂等键。
分析 API 始终注册，外部 endpoint 配置后按请求调用，没有启用开关。

## Platform 到 HTTP 分析服务

POST 到配置的 endpoint，Content-Type=application/json，body 与上述一致；跟踪 ID 位于
`X-Analysis-Request-Id`。这是项目自定义请求，不能直接填通用模型聊天地址。
超时由 http.timeout-ms 配置（100–120000 ms）；响应上限 1 MiB。只接受成功 HTTP 状态及合法 JSON 对象。

```json
{
  "summary": "建议检查连接状态",
  "confidence": 0.8,
  "evidenceRefs": ["sync-task:5"],
  "recommendations": [{"action": "CHECK_SYNC_HEALTH", "reason": "先定位再恢复", "requiresApproval": true}]
}
```

summary 与 confidence 必须提供；summary 最多 2000 字符，置信度归一到 [0,1]，非有限值拒绝。
最多 20 条建议，action 为 1–64 字符的大写字母/数字/下划线标识，reason 最多 2000 字符。
所有建议强制 requiresApproval=true，仅展示，不自动映射执行接口；没有自动执行白名单能力。
外部失败/格式不合法时直接报错，不继续尝试其他 Provider；保存失败直接报错，不能伪装成 Provider 不可用。
结果由 Platform 补齐 requestId、protocol、provider、status=COMPLETED 和 createdAt，保存后返回标准 data 包装。
暂不支持异步 RUNNING/轮询回调、流式响应或多轮对话。

## 项目 ACP 风格适配器

先 GET manifest-path（默认 /manifest），必须有 capabilities 或 metadata.capabilities 数组，包含场景名
或 ANALYSIS；缺失能力声明视为不支持。再 POST run-path（默认 /runs），body 为
`{"requestId":"...","request":{上述请求}}`，跟踪头 X-Request-Id。
响应为上面的结果对象，或 `{"result":{结果对象}}`，status 若存在必须为 COMPLETED。
路径和 timeout-ms 可配置。该约定不代表通用 ACP 协议合规，外部接入必须用选定服务联调。

注册资料（/analysis-agents）只是库存信息，修改 enabled/endpoint 不改变实际出站请求。
当前外部调用仍以部署配置为信任边界；对外出站地址应由公司网关和网络策略限制，认证对接另行实现。

## 数据库与测试

V27 新增注册资料表，V28 新增分析结果表，均由 Platform Flyway 执行。schema migration 与是否发起分析无关。
V26 完整初始化包保持不可变；部署本版本时继续执行 V27/V28。`scripts/verify-analysis-migrations.mjs`
在隔离 MySQL 验证 V26 -> V28 和重复迁移不执行。运行开关见 [配置说明](runtime-switches.md)。
