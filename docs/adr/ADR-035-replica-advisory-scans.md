# ADR-035：分析扫描自动优先副本

状态：Accepted，已实现。

范围为 Platform 风险扫描、Key 分布分析/预览及 TTL/清理治理 Dry Run。新增领域端口 ReadOnlyScanPort，共享节点选择、分页扫描和 TYPE/TTL/MEMORY 元数据读取；DistributionScanPort 继承它，Infrastructure 有界 RESP adapter 实现。同步验证、Worker、Console 和治理 APPLY 使用原有主节点路径。

不新增用户配置。开始扫描时每个主分片确定性选择一个健康、连接正常且未同步中的 Replica；无可用副本自动选择该主分片 Master。Standalone 通过 ROLE 发现副本，Sentinel 先解析当前 Master。逐 Key 查询固定在所选节点；Cluster 副本连接使用 READONLY，不将 Key 查询重定向回 Master。页面提示实际扫描节点、角色和复制延迟语义。

游标绑定端点、角色、Redis run_id 和主分片。每批连接核对身份和角色，运行中节点晋升、重启、替换或拓扑变化则停止，不能把旧游标用于新节点。分布分析复用指纹/checkpoint；风险/Dry Run 以现有 shard_id 保存指纹绑定标识和完整目标清单，恢复前核对清单。旧运行需重新开始预检/扫描，不自动迁移旧游标。无 schema 变更。

治理 APPLY 在主节点重新扫描校验，副本结果仅供估算；风险和 Dry Run 只读取元数据，不读取 value。保留限速、接收预算、凭据解密边界，不新增 Key 明细、日志或指标标签。副本扫描是观测结果，不保证一致性快照，内存和 TTL 不代表 Master 当前精确值。
