# ADR-016：任务级 Worker 机器信息观测

状态：Accepted。

同步领域增加任务到 Worker 实例及机器 IP 的只读展示，不引入独立 Worker 注册中心或机器在线探测。
Worker 在原子租约领取时同时写入 IP 和对应 runtime ID，数据所有权仍归当前租约持有者。
Platform 批量读取共享控制库并提供只读投影，前端每 5 秒刷新。混合版本接管后 runtime ID 不匹配则隐藏旧 IP。
新增 V29 可空列，先升级 Platform schema 再升级 Worker。租约、generation、checkpoint 和 fence 语义不变。
机器 IP 仅作内部运维观测，不携带凭据，不授权任何生产写操作；租约状态不能替代机器健康判断。
详情及升级步骤见 [Worker 可观测性](../sync-worker-visibility.md)。
