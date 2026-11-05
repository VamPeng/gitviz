# GitViz 开发约定

- 调试只用蓝色 DEV 图标的 `/Applications/GitViz Dev.app`，不要启动或覆盖正式版。
- 启动前检查进程、优先复用已有 Dev；最多保留一个正式版和一个 Dev，不打开构建目录中的副本。
- 编译用 `./gradlew classes`，测试用 `./gradlew test`；测试保持无窗口模式，不出现 Java Dock 图标。
- 做功能前看 `docs/ROADMAP.md`，改界面前参考 `docs/prototype/`。
- Git 写操作的调试使用练习仓库；保留工作区已有改动。
- 需求完成后只更新已有文档，不新增完成说明或验证报告；清理本次验证产生的截图、录屏和临时报告。
