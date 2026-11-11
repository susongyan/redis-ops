# ADR-039：同步跳过日志与任务事件携带 Redis Key 名

状态：Accepted，已实现。

大 Key 拆分跳过（SPLIT_KEY_SKIPPED）最初在 WARN 日志与任务事件中只携带 key 的 SHA-256 指纹，理由是避免 Key 内容扩散。实际排障中指纹无法反查原始 key，定位一个被跳过的 key 需要回到源实例逐个比对；而治理扫描、Key 分布分析等排障页面本就展示完整 key 名，指纹通道没有带来额外的暴露面收敛。

决策：企业内部排障场景允许携带 Redis Key 名——排障页面受控展示之外，同步跳过类运维 WARN 日志与任务事件也直接携带 key 名（事件格式 v2：`key=` 放行尾以容忍含空格的 key 名；存量 v1 指纹事件按指纹兼容渲染）。Key 仍不得进入指标、审计、异步 payload 或对外通知；密码、密文、主密钥与完整 Redis value 的绝对禁令不变。

后果：跳过事件新增 `elements` 字段（集合类声明总元素数，quicklist 无整表总数时按节点头精确求和，四类不可知情形报 0，见 `docs/big-key-split-design.md` 跳过三件套）。AGENTS.md §1.4 同步修订。
