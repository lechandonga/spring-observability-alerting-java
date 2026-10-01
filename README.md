# Spring 请求级可观测与告警（Java 21 / Spring Boot 3.4）

本服务在普通 Spring MVC 之上补了一层**请求级指标 + 请求标识 + 滑动窗口告警**，
目标是线上出问题时，可以用同一套维度把“发生了什么、是哪次请求、是否该报警”还原出来，
并且在并发写入、异常流量下结论稳定、原因可解释。

## 1. 指标模型

实现位于 `metrics` 包，由 `MetricsFilter` 在每个请求结束时统一记录（异常路径也记录）。

### 1.1 维度与序列

- 序列键为 `(endpoint, status)`：
  - `endpoint` 优先取 Spring MVC 的路径模板（`/users/{id}`），避免路径参数打爆基数；
    解析不到模板时回退为原始 URI。
  - `status` 为 HTTP 状态码（如 `200`、`500`）；未到达响应、过滤器链抛异常时记为 `ERROR`。
- 每个序列包含：`calls`（调用量）、`errorCalls`（异常量）、固定桶 `LatencyHistogram`（时延分布）。

### 1.2 维度基数有硬上限（不会静默丢弃、不会无限增长）

| 输入情况 | 判定（`DimensionOutcome`） | 计入位置 | 独立计数器 |
| --- | --- | --- | --- |
| 合法且未超上限 | `ACCEPTED` | 原值独立序列 | — |
| 缺失/空白/含非法字符/超长 | `INVALID` | 常量桶 `__invalid__` | `invalidDimensionRejections` |
| 合法但不同取值数已达上限 | `CAPPED` | 常量桶 `__other__` | `cappedDimensionMerges` |

- endpoint 合法字符：字母、数字、`/ _ - { } * .`，长度受配置限制；
  status 合法字符：大写字母、数字、`_`，长度 ≤ 32。
- 基数裁决在同一张写锁内完成：并发冲击上限时，**被接受的不同取值数量恰好等于上限**，
  其余全部归并 `__other__`，每一次归并都计数并打印 WARN 日志。

### 1.3 并发一致性

- 计数使用 `AtomicLong`，直方图使用固定上界桶 + `StampedLock`：
  每个样本落入唯一一个桶（或 underflow/overflow），不存在桶错位。
- `MetricsSnapshot` 在读锁内采集，因此快照内
  `calls == 直方图样本数 == 各桶计数之和`，count/sum/min/max 覆盖同一批样本。

### 1.4 查询

- `GET /observability/metrics`：返回全量一致性快照（序列、调用量、异常量、直方图桶计数）。

## 2. 请求标识（生成 / 透传 / 日志关联）

实现位于 `requestid` 包，规则集中在无状态的 `RequestIdPolicy`（可独立单测、结论可复现）。

| 上游输入 | 处理（`RequestIdOrigin`） | 响应头 |
| --- | --- | --- |
| 未携带 | `GENERATED`，生成 `req-<32位十六进制>` | 回写新标识 |
| 恰好 1 个，匹配 `[A-Za-z0-9._-]{8,128}` | `INBOUND_ACCEPTED`，原样透传 | 原样回写 |
| 唯一取值非法（空、空格、非法字符、长度越界） | `INVALID_REJECTED`，保留原值到日志，重新生成 | 回写新标识 |
| 多个取值（重复同名头，或逗号多值，即使内容相同） | `DUPLICATE_REJECTED`，保留全部原值，重新生成 | 回写新标识 |

- `RequestIdFilter`（最高优先级）在进入过滤链前写入响应头，因此**异常响应也一定带标识**。
- 标识写入 SLF4J MDC（键 `requestId`），日志 pattern 中通过 `%X{requestId}` 关联整条链路。
- 异常路径只记录日志（异常类型 + 原始消息 + 堆栈），随后原样抛出；
  `PassthroughExceptionHandler` 兜底为 500 JSON，body 中保留原始异常类名与消息，不吞不改写。
- MDC 在 `finally` 中恢复进入前的值，线程池复用不会串请求。

## 3. 告警规则语义

实现位于 `alerting` 包。

### 3.1 规则（`AlertRule`，不可变）

字段：`id, endpoint, status, metric, windowSeconds, threshold, cooldownSeconds`。

- `metric`：`CALLS`（窗口内调用量）或 `ERRORS`（窗口内异常量）。
- `status = "*"` 表示匹配该 endpoint 下所有状态序列；否则精确匹配。
- 窗口为**按秒对齐的环形桶滑动窗口**，窗口内累计值 `>= threshold` 即满足触发条件。

### 3.2 状态机（冷却与重武装）

每次评估产生五类互斥结论（`AlertDecision`），写入有界流水 `AlertEvent`：

| 条件 | 结论 |
| --- | --- |
| 越限且处于冷却期（`tick - lastFire < cooldownSeconds`） | `SUPPRESSED_IN_COOLDOWN`（冷却期绝不重复触发） |
| 冷却已过但自上次触发后从未观测到回落 | `NOT_REARMED` |
| 越限且已武装 | `FIRED`（触发，置 lastFire、解除武装） |
| 未越限且当前未武装 | `RECOVERED`（回落，重新武装，之后允许再次 FIRED） |
| 未越限且已武装 | `BELOW_THRESHOLD` |

因此：**冷却期内不重复；冷却结束也必须先看到一次回落，才会再次触发**。

### 3.3 原子更新、回收与可复现

- `AlertEngine.replaceRules(...)` 以不可变快照整体替换规则集，评估线程看到的
  要么是旧全集要么是新全集；被删除或定义变化（窗口/阈值/冷却/匹配维度）的规则，
  其窗口与状态立即作废，避免用旧结论解释新规则。
- `evictIdle(tick, grace)` 回收“最后数据时间 < tick − window − grace”的序列，
  长期无数据的序列不会无限堆积（调度器每秒执行，grace 默认 60s）。
- 评估结论只依赖 `(规则, tick, 样本序列)`，测试中用手动 tick 驱动，
  同样的输入必然产生同样的流水，历史结论可复现。
- `GET /observability/alerts/events`：查看当前规则与评估流水。

## 4. 配置项（`application.properties`）

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `observability.metrics.max-endpoint-values` | `100` | endpoint 不同取值上限（不含常量桶） |
| `observability.metrics.max-status-values` | `16` | status 不同取值上限 |
| `observability.metrics.max-endpoint-length` | `128` | 单个 endpoint 最大长度，超长按非法拒绝 |
| `observability.metrics.latency-buckets-millis` | `5,10,25,50,100,250,500,1000,2500` | 直方图桶上界（毫秒） |
| `observability.request-id.header-name` | `X-Request-Id` | 请求标识头名 |
| `observability.alert.enabled` | `true` | 是否启用定时评估 |
| `observability.alert.tick-ms` | `1000` | 评估/回收周期 |
| `observability.alert.idle-grace-seconds` | `60` | 无数据序列在窗口外的额外保留时间 |
| `observability.alert.rules[n].*` | 示例 `boom-errors` | 启动静态规则；运行期可用 `replaceRules` 热更新 |

## 5. 本地验证

```bash
# 全部单测（并发写入 / 维度超限 / 标识异常 / 告警冷却 / 端到端）
mvn test

# 启动
mvn spring-boot:run

# 正常、路径模板、异常接口
curl -i http://localhost:8080/hello
curl -i http://localhost:8080/users/42
curl -i http://localhost:8080/boom

# 透传 / 触发非法与重复标识的判定（观察响应头与服务日志）
curl -i -H 'X-Request-Id: trace-0001-abcd' http://localhost:8080/hello
curl -i -H 'X-Request-Id: bad id!'        http://localhost:8080/hello
curl -i -H 'X-Request-Id: a' -H 'X-Request-Id: b' http://localhost:8080/hello

# 指标快照与告警流水
curl -s http://localhost:8080/observability/metrics | jq .
curl -s http://localhost:8080/observability/alerts/events | jq .

# 连续打 3 次以上 /boom（默认规则：60s 窗口 ERRORS>=3，冷却 30s），
# 观察仅一次 FIRED，之后为 SUPPRESSED_IN_COOLDOWN；停止流量 60s+ 后可再次触发。
for i in 1 2 3 4 5; do curl -s http://localhost:8080/boom >/dev/null; done
curl -s http://localhost:8080/observability/alerts/events | jq '.events[]|.decision'
```

### 测试与失败原因区分

| 测试类 | 覆盖场景 |
| --- | --- |
| `MetricsConcurrencyTest` | 16 线程 × 5000 次并发写：调用量零丢失、直方图桶不错位、快照自洽 |
| `DimensionBoundTest` | `INVALID` 与 `CAPPED` 判定可区分；100 并发抢 10 个名额时接受数恰好为 10 |
| `RequestIdPolicyTest` | 缺失/透传/非法/重复四类输入的确定规则 |
| `RequestIdFilterIntegrationTest` | 响应头回写、重复头拒绝、异常路径保留原始类型与消息 |
| `AlertEngineTest` | 冷却抑制、回落重武装、规则原子更新、空闲回收、历史重放一致 |
| `ObservabilityEndToEndTest` | 真实请求进入指标快照（含路径模板与异常计数） |

所有关键测试均通过 SLF4J 打印**输入值与判定依据**（如 `input status=503 decision=CAPPED`、
`tick=2 window=4 => SUPPRESSED_IN_COOLDOWN`），失败时可直接从 surefire 输出区分原因。
