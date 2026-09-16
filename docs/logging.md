# Platform / Worker 日志

两进程使用 Spring Boot 默认 SLF4J + Logback，通过各自 `application.yml` 配置。

默认写入**启动工作目录**下 `log/platform.log` 或 `log/worker.log`；不是按 JAR 所在位置推算。
从仓库根启动会写入仓库根 `log/`，从拆分后的项目根启动则写入该项目 `log/`。
可通过 `LOG_DIR` 统一覆盖目录。默认保留控制台输出。

格式为：带时区时间、级别、应用名、PID、线程、Logger、消息和异常堆栈。
默认 INFO，不开启请求体、SQL 参数或 Redis 命令明细日志；禁止记录密码、密钥和完整 value。

按日期和单文件 20MB 滚动，压缩文件放在 `log/archive/`，历史保留 14 天，每个进程归档容量上限 1GB。
启动时清理过期历史；容量上限不包含当前活动文件。多实例不得共写一个文件，应配置不同目录。

这些属性均可用 Spring 外部配置覆盖：`logging.level.*`、`logging.pattern.*`、
`logging.file.name`、`logging.logback.rollingpolicy.*`。单独覆盖 `logging.file.name` 时也应
同步设置归档路径；推荐使用 `LOG_DIR` 同时调整两个路径。启动用户需有目录写权限。

本地旧启动脚本仍将控制台输出追加到 `data/local/*.log`，那是启动器副本，不是滚动文件。
部署脚本的 `LOG_DIR` 默认也改为安装根 `log/`，已有显式配置优先。脚本控制台副本为
`platform-console.log` / `worker-console.log`，不受 Logback 滚动策略管理，部署时应由外部 logrotate
管理或使用 systemd 日志。不允许将控制台重定向到 Logback 正在写入的同一个文件。

配置随新 JAR 在重启后生效；不会搬迁或删除旧日志。本目录已加入 Git 忽略规则。
