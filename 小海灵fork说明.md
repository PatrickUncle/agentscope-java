# 小海灵平台 fork 说明

本仓库是 [agentscope-java](https://github.com/agentscope-ai/agentscope-java) 的平台 fork，
为「小海灵」agent-server（宿主仓库 `agentscope-agent`，模块 `agent-java`）提供框架层修复。
上游不动的部分与官方完全一致，本文只讲 fork 的差异与协作方式。

## 分支与版本约定

| 项 | 值 | 说明 |
|---|---|---|
| 上游基线 | tag `v2.0.3` | 与 GitHub 上游 `v2.0.3` 逐字节相同 |
| 主线分支 | `main` | 基线之上叠加平台修复，所有改动走这里 |
| 发布版本 | `2.0.3-xhl.N` | Maven 版本号，`N` 每次发布递增；每个发布打 tag `v2.0.3-xhl.N` |

**为什么用独立版本号**：fork 早期沿用上游 `2.0.3`，依赖方 Maven 从坐标上分不出 fork 与上游，
环境里缺本地预填时会静默解析到上游包、功能悄悄回退。`-xhl.N` 后缀让任何环境都不可能拿错。

版本号由根 pom 的 `<revision>` 属性统一控制（flatten 插件在 install 时展开），
升版只需改三处：`pom.xml`、`agentscope-distribution/agentscope-bom/pom.xml`、`agentscope-dependencies-bom/pom.xml`。

## 相对上游改了什么

`git log v2.0.3..main` 可见全部提交，按主题六组（每组一个 commit，可独立回看）：

| 主题 | 模块 | 一句话 |
|---|---|---|
| SkillManageTool 上限放宽 | harness | 正文 1M 字符 / 文件 50MiB / 描述 8192，上限只拦失控写入 |
| 流式响应截断检测与安全重试 | core + extensions-model-openai | 缺 `[DONE]`/`finish_reason` 判定截断抛错，推理层重置累加器后重试一次 |
| 技能 head-only 懒加载 | core + harness | 注册只驻留元数据，正文/资源磁盘现读，512KB 内联护栏 |
| 子 agent 强制同步模式 | harness | 同步专用提示词；并发 spawn 事件源路径加唯一后缀 |
| WebTools 共享 HttpClient | harness | 消除 agent 重建导致的 SelectorManager 线程泄漏 |
| 子 agent 继承权限上下文 | harness | 修复 BYPASS 父 agent 的子 agent 碰 MCP 工具弹确认死锁 |

宿主侧只消费三个构件：`agentscope-core`、`agentscope-harness`、`agentscope-extensions-model-openai`。
**三个缺一不可**——流截断修复横跨 core 与 openai 扩展，只换其中之一会静默失效。

## 构建与安装到本地仓库

```bash
mvn -q -T 1C install -DskipTests -Dspotless.check.skip=true -Dgpg.skip=true \
  -pl agentscope-core,agentscope-harness,agentscope-extensions/agentscope-extensions-model/agentscope-extensions-model-openai -am
```

约 20 秒，产物落在 `~/.m2/repository/io/agentscope/<构件>/2.0.3-xhl.N/`。

## 改动后同步到宿主仓库

宿主 `agentscope-agent` 仓库的 `agent-java/docker-m2/` 是镜像构建用的 Maven 预填目录，
**已随宿主仓库提交**，同事无需手工准备。改了本 fork 后按下面流程更新：

1. 本仓库：改 `<revision>` 升 `N`，提交并打 tag `v2.0.3-xhl.N`，推送 `main` 与 tag。
2. 本机按上文命令 `mvn install`。
3. 宿主仓库：`agent-java/pom.xml` 的 `<agentscope.version>` 改为新版本；把三个构件的 jar + pom
   从 `~/.m2/repository/io/agentscope/` 拷到 `agent-java/docker-m2/io/agentscope/` 对应新版本目录，
   删掉旧版本目录；逐构件 `md5` 对比确认一致。
4. 宿主侧跑 `mvn -q test -Dtest=SkillManageToolLimitsTest` 校验，与依赖这次改动的宿主代码**同一个 commit** 提交。

宿主本地直接跑 `mvn` 时，`~/.m2` 里必须有对应 `-xhl.N` 版本（按上文 install 一次，
或 `cp -r agent-java/docker-m2/io ~/.m2/repository/`），否则依赖解析直接失败——这正是独立版本号的作用。

## 升级上游版本

`origin` 指向 GitHub 上游，`upstream-main` 本地分支跟踪其 `main`。升级时：

```bash
git fetch origin --tags
git rebase <新上游 tag> main      # 逐组解决冲突，六组提交边界清晰
# 改三处 <revision> 为 <新版本>-xhl.1，重新 install 并按上文同步宿主
```
