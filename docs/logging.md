# Platform / Worker 日志

两进程使用 SLF4J + Logback，日志配置统一维护在各自的 `src/main/resources/logback-spring.xml`，不在 YAML 中重复定义。

默认写入**启动工作目录**下 `log/yyyy-MM-dd/platform.0.log` 或 `log/yyyy-MM-dd/worker.0.log`；不是按 JAR 所在位置推算。
从仓库根启动会写入仓库根 `log/`，从拆分后的项目根启动则写入该项目 `log/`。
可通过 `LOG_DIR` 统一覆盖目录。默认保留控制台输出。

格式为：带时区时间、级别、应用名、PID、线程、Logger、消息和异常堆栈。
默认 INFO，不开启请求体、SQL 参数或 Redis 命令明细日志；禁止记录密码、密钥和完整 value。

日期使用 JVM 默认时区，跨天后第一条日志自动切换到当天目录，无需重启。
单文件达到 20MB 后递增序号（如 `platform.1.log`），不压缩。历史保留 14 天，每个进程历史容量上限 1GB。
启动时清理过期历史；容量上限不包含当前活动文件。多实例不得共写一个文件，应配置不同目录。

格式、默认级别、文件路径模式和容量策略直接维护在 XML，不再读取 `logging.logback.rollingpolicy.*`。
目录推荐通过环境变量 `LOG_DIR` 覆盖。Spring Boot 自身仍支持外部 `logging.level.*` 调整级别，
但本项目 YAML 不再定义日志配置。
不使用固定的 `logging.file.name`，避免活动日志始终留在同一个路径。启动用户需有目录写权限。

本地旧启动脚本仍将控制台输出追加到 `data/local/*.log`，那是启动器副本，不是滚动文件。
部署脚本的 `LOG_DIR` 默认也改为安装根 `log/`，已有显式配置优先。脚本控制台副本为
`platform-console.log` / `worker-console.log`，不受 Logback 滚动策略管理，部署时应由外部 logrotate
管理或使用 systemd 日志。不允许将控制台重定向到 Logback 正在写入的同一个文件。

配置随新 JAR 在重启后生效；不会搬迁或删除旧日志，旧固定日志及 `archive/` 不受新策略清理。
本目录已加入 Git 忽略规则。
