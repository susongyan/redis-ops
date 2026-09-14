# ADR-018：Key 分布分析按批次限速

状态：Accepted。日期：2026-09-14。补充 ADR-017。

正式任务使用 `scanCount`（默认 200，1–5000）和 `scanIntervalMillis`（默认 200 ms，10–60000 ms），
替代 Key/s 以及固定每秒 5 次 SCAN 限制。间隔按整个任务相邻 SCAN 开始时间计算，跨主分片共用。
部署可降低 COUNT 上限或提高间隔下限，任务不得绕过部署限制。慢响应时将间隔加倍，持续变慢仍暂停。
原有单页 1 MiB / 5000 元素、Key 4 KiB、候选 1000、checkpoint 2 MiB、并发上限不扩大。

任务配置仍保存在既有 JSON 列，无表结构变化。旧 `keysPerSecond` 快照仅用于展示历史事实，
不转换为新的执行语义；旧任务若被领取，结束为 INCOMPLETE / LEGACY_RATE_CONFIGURATION，需要用户新建任务。
新版拒绝创建旧参数任务。旧 checkpoint 不按新间隔解释。部署升级应先停止旧 Platform，禁止混合版本领取任务。
本地旧分析结果仅按用户授权清理，并先备份；发布升级不自动删除历史数据。
