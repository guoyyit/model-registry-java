# 本地模型注册与版本生命周期管理服务

基于 Spring Boot 3 / Java 17 的本地模型注册中心。模型以**不可变版本**持久化到本地文件系统，
经历从注册到上线再到下线的受控生命周期；发布、回滚、并发上传在崩溃与并发下都保持
**状态可解释、可恢复**。

## 1. 核心语义

### 1.1 不可变版本

* 一次上传经校验通过后被**原子发布**为一个版本。版本号在同一个模型下唯一。
* 发布后，版本的内容字节、校验值、大小、创建时间等元数据**永不再变**。
  唯一会变化的是生命周期阶段（保存在同一份 `meta.json` 中，原子替换）。
* 读取只能看到**已完整发布**的版本：正在上传、校验失败或中断的版本不出现在任何读取结果中。
* 已被占用的版本号（已发布、上传中、已删除墓碑）不能被覆盖或重新注册。

### 1.2 生命周期阶段

每个版本严格按以下顺序推进，**禁止跳阶段**：

```
REGISTERED ──► CANDIDATE ──► PRODUCTION ──► RETIRED
```

* `REGISTERED`：已完整发布，但尚未进入发布流程。
* `CANDIDATE`：候选版本。`REGISTERED → CANDIDATE` 是进入正式阶段的唯一入口。
* `PRODUCTION`：当前对外服务的正式版本。每个模型**任意时刻最多一个**。
* `RETIRED`：已下线/失效。元数据与审计历史永久保留。

规则：

* `REGISTERED → PRODUCTION`（跳过 CANDIDATE）被拒绝（`INVALID_STAGE_TRANSITION`，HTTP 422）。
* 任何阶段都不能原地回退。**阶段回退只能通过显式回滚发生**。
* 把新版本提升为 `PRODUCTION` 时，原 `PRODUCTION` 版本会被确定性地置为 `RETIRED`。

### 1.3 回滚与恢复

`rollback(model, targetVersion)` 可以指向**任意两个历史版本之间**的目标版本
（包括已经 `RETIRED` 的版本）：

1. 当前 `PRODUCTION`（若存在）被置为 `RETIRED`；
2. 目标版本被置为 `PRODUCTION`；
3. 先写 `ROLLBACK` 审计事件，再提交状态。

因此回滚结果**一定是一个确定的历史版本**，且全程可在审计日志中追溯。
当目标已经是 `PRODUCTION` 时，回滚是确定性的空操作。

回滚与新的发布请求在同一模型上通过**每模型单锁**串行化：最终生效的就是
获取锁顺序里最后一个“生产决策”，读取方观测到的生产版本与审计日志的最后一条
`PROMOTE_PRODUCTION` / `ROLLBACK` 决策完全一致。

### 1.4 下线与删除

* `PRODUCTION` 版本**不得被直接删除**（`ILLEGAL_DELETE`，HTTP 422），只能先 `retire`。
* `RETIRED` 版本同样不能删除——它作为历史与审计依据保留。
* 只有**从未进入过 PRODUCTION** 的 `REGISTERED` / `CANDIDATE` 版本可以被删除；
  删除会先写 `DELETE` 审计事件，再移除目录并写入 `deleted.flag` 墓碑
  （版本号永久占用，不可复用）。
* 下线是范围受限的：仅影响被下线版本本身（内容读取返回 HTTP 410 Gone），
  其他版本的读取与生产服务完全不受影响。下线后审计信息保留。

## 2. 并发上传与重复提交

* 上传分两阶段：先 `begin upload` 获得服务端生成的 `uploadId`，再分块追加内容，
  最后 `complete` 校验并原子发布。追加字节时请使用
  `Content-Type: application/octet-stream`，服务端对该类型按原始字节逐位透传
  （见 `RawBytesWebConfig`），不做任何字符集编解码。
* 同一模型的发布完成、阶段推进、回滚、下线、删除都在**每模型锁**下执行；
  原始字节追加使用每上传锁；不同模型互不阻塞。
* **重复提交幂等**：携带相同 `clientToken` 的重复提交不会产生第二个有效版本。
  已发布后重复提交返回 `409 Conflict` 并在响应体给出 `existingVersion`。
* 对同一 `(model, version)` 的并发上传，只有一个胜出，其余收到冲突错误
  （`VERSION_ALREADY_EXISTS` / `VERSION_ALREADY_TAKEN`）。
* 部分写入对读取方不可见：版本只有在其 `meta.json` 原子出现时才可见。

## 3. 内容校验策略（可配置）

* 注册时必须提供期望校验值；服务端在 `complete` 时对**全部字节**重新计算并比对，
  同时校验声明长度。
* 校验算法可配置：`registry.checksum-algorithm`，默认 **`SHA-256`**（小写十六进制）。
  任何 JDK `MessageDigest` 支持的算法都可配置（例如 `SHA-256`、`SHA-512`、`MD5`）；
  配置为不支持的算法会在启动时快速失败。
* 校验值不匹配（`CHECKSUM_MISMATCH`）或内容长度不完整（`INCOMPLETE_UPLOAD`）时，
  版本**不会进入生命周期**，上传标记与临时字节立即被清理。

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `registry.storage-path` | `data/registry` | 所有持久化数据的根目录 |
| `registry.checksum-algorithm` | `SHA-256` | 注册要求的内容校验算法 |
| `registry.reclaim-incomplete-uploads` | `true` | 启动恢复时是否自动回收中断上传 |
| `registry.retain-retired-content` | `true` | 下线版本是否保留内容字节（元数据/审计始终保留） |

## 4. 崩溃恢复与存储回收

### 4.1 磁盘布局

```
<storage-path>/
  journal.log                                      # 只追加审计日志（每行一个 JSON 事件）
  models/<model>/versions/<version>/meta.json     # 版本元数据（原子替换；其出现即“已发布”）
  models/<model>/versions/<version>/content.bin   # 不可变内容
  models/<model>/versions/<version>/deleted.flag  # 删除墓碑（版本号不可复用）
  uploads/<uploadId>.json                          # 上传标记（UPLOADING/FINALIZING/PUBLISHED）
  uploads/<uploadId>.bin                           # 上传中的临时字节
```

所有持久化写入都走“同目录临时文件 → fsync → 原子 rename → fsync 父目录”，
因此崩溃后任何文件要么是旧版本，要么是新版本，绝不会出现半写文档。

### 4.2 启动恢复

应用启动时（`ApplicationReadyEvent`）执行一次全局恢复扫描，结果只可能是三类：

1. **完整发布**：中断上传的字节完整（长度与校验都通过）→ 确定性地补完为 `REGISTERED`
   版本，并补记 `RECOVER` 审计事件。
2. **完整回滚 / 决策对齐**：依据只追加日志中每个模型最后一条
   `PROMOTE_PRODUCTION` / `ROLLBACK` 决策，校正可能被撕裂的生产状态，
   保证恰好一个 `PRODUCTION`，其余置 `RETIRED`。
3. **明确标记的未完成操作**：字节缺失/校验不过/长度不符的中断上传——
   * `registry.reclaim-incomplete-uploads=true`（默认）：删除标记与临时字节，
     写 `REJECT_UPLOAD` 事件，**安全回收存储**；
   * 为 `false` 时：保留磁盘文件并在恢复报告中标记为 `suspicious`，供人工排查，
     仍然不会作为版本暴露。
4. 没有 `meta.json` 的孤立版本目录会被清扫。

任何情况下，恢复都**不会暴露部分写入**。

### 4.3 审计（journal）

`journal.log` 只追加、每行 fsync、序列号无空洞，是永久审计轨迹。
发布、拒绝、晋升、回滚、下线、删除、恢复都会先落日志再改状态。
即使版本内容被回收，其审计事件仍然保留。

## 5. HTTP API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/models/{model}/uploads` | 开启上传，返回 `uploadId`（201） |
| POST | `/api/models/{model}/uploads/{id}/content` | 追加原始字节块 |
| POST | `/api/models/{model}/uploads/{id}/complete` | 校验并原子发布 |
| GET | `/api/models` | 列出模型 |
| GET | `/api/models/{model}/versions` | 列出已发布版本 |
| GET | `/api/models/{model}/versions/{v}` | 读取版本元数据 |
| GET | `/api/models/{model}/versions/{v}/content` | 下载内容（含 `X-Model-Checksum`） |
| GET | `/api/models/{model}/production` | 当前生产版本 |
| POST | `/api/models/{model}/versions/{v}/candidate` | REGISTERED → CANDIDATE |
| POST | `/api/models/{model}/versions/{v}/production` | CANDIDATE → PRODUCTION |
| POST | `/api/models/{model}/rollback` | 回滚到任意历史版本 `{targetVersion, reason}` |
| POST | `/api/models/{model}/versions/{v}/retire` | 下线 `{reason}` |
| DELETE | `/api/models/{model}/versions/{v}` | 删除从未上线的版本 |
| GET | `/api/models/{model}/audit` | 该模型审计轨迹 |
| GET | `/api/models/audit` | 全量审计轨迹 |

### 典型流程

```bash
# 1) 开启上传
SUM=$(shasum -a 256 model.bin | awk '{print $1}')
UID=$(curl -s -XPOST localhost:8080/api/models/iris/uploads \
  -H 'Content-Type: application/json' \
  -d "{\"model\":\"iris\",\"version\":\"1.0.0\",\"clientToken\":\"t-1\",
       \"expectedChecksum\":\"$SUM\",\"contentType\":\"application/octet-stream\",
       \"expectedSize\":$(stat -f%z model.bin)}" | jq -r .uploadId)

# 2) 上传字节
curl -s -XPOST "localhost:8080/api/models/iris/uploads/$UID/content" \
  --data-binary @model.bin

# 3) 校验并发布
curl -s -XPOST "localhost:8080/api/models/iris/uploads/$UID/complete"

# 4) 受控推进
curl -s -XPOST localhost:8080/api/models/iris/versions/1.0.0/candidate
curl -s -XPOST localhost:8080/api/models/iris/versions/1.0.0/production

# 5) 回滚到任意历史版本
curl -s -XPOST localhost:8080/api/models/iris/rollback \
  -H 'Content-Type: application/json' -d '{"targetVersion":"1.0.0","reason":"incident"}'

# 6) 下线（审计保留）
curl -s -XPOST localhost:8080/api/models/iris/versions/1.0.0/retire \
  -H 'Content-Type: application/json' -d '{"reason":"eol"}'
```

## 6. 构建与测试

```bash
mvn test        # 34 个测试，全部通过
mvn spring-boot:run
```

自动化测试覆盖：

* **并发上传**：多版本并发可见、同版本并发唯一胜出、同 clientToken 幂等；
* **阶段推进**：顺序推进、跳阶段拒绝、新上线替换旧生产；
* **回滚**：任意历史版本回滚确定性、可追溯、可恢复已下线版本；
* **下线/删除**：生产版本禁止直删、审计保留、范围外读取不受影响、内容按配置回收（410）；
* **崩溃恢复**：完整字节补发布、部分字节回收/保留、撕裂的生产决策按日志对齐；
* **内容校验**：SHA-256 默认、算法可配置（MD5 用例）、校验失败不进入生命周期；
  服务层与 HTTP 层均有二进制逐字节精确往返用例（覆盖 0x00 与全部高字节）；
* **HTTP 层**：完整生命周期、409 重复提交、422 状态机错误、审计与删除规则。
