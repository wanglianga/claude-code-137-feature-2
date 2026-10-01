# 社区药箱过期药回收称重兑换与环保交接服务

基于 **Java 17 + Spring Boot 3 + PostgreSQL** 的社区过期药品回收服务，覆盖完整链路：

```
居民预约 -> 回收员扫码登记 -> 分类称重封袋（针剂安全封装复核）-> 高风险人工复核 -> 积分发放
        -> 普通袋暂存柜暂存 / 针剂转入锐器盒（容量/超期/满盒预警）
        -> 环保交接(上门量/双人签收/公司按袋与锐器数量重量确认/拒收/破损重封) -> 批次追溯
```

预约、称重、复核、积分、暂存、锐器盒、交接与异常全部挂在**同一批次（RecycleBatch）**上，交接前每袋药品都有来源（预约/批次）与分类。

## 功能清单

| 模块 | 说明 |
| --- | --- |
| 预约 | 记录药品类型、数量、是否含处方药/液体/针剂/个人信息包装、社区点、活动日；预约时固化**积分规则快照** |
| 登记称重 | 回收员扫码登记生成批次；按 普通片剂/抗生素/精神类/注射器/外用药/液体药 六类称重，记录包装照片与封袋编号 |
| 针剂安全封装复核 | 居民提交**胰岛素针头/注射器**时，回收员必须确认四要素：**是否使用防刺容器、是否混入普通药袋、锐器数量、重量与照片**；未封装/混入自动生成 `UNSAFE_INJECTION` 异常并强制人工复核 |
| 锐器盒 | 针剂**不得进入普通暂存柜**；复核通过后转入**锐器盒（防刺容器）**，首次转入即生成 **SHL 特殊交接标签**；转入记录可追溯 |
| 人工复核 | 高风险品类（精神类、注射器）及异常触发的袋先复核，通过才发积分、才准入柜/入锐器盒 |
| 积分 | 按品类每公斤积分 × 活动日倍率发放；流水可查；兑换走悲观锁应对峰值；失败落 FAILED 兑换单 |
| 暂存柜 | 普通袋入柜容量强校验（悲观锁防超卖）、使用率预警（WARN/FULL）、超期暂存检查；物理拒绝针剂袋 |
| 锐器盒容量通知 | 使用率达预警比例（默认 80%）自动**提前通知环保公司/上级回收点**；达容量上限生成 FULL 停收通知并**拒绝现场继续接收**，避免无法安全暂存 |
| 环保交接 | 上门量阈值校验、含高风险袋/**封盒锐器盒**或超重自动要求**双人签收**；环保公司逐袋、**逐盒按锐器数量与重量**确认 |
| 破损重新封装 | 公司签收发现**包装破损**：整盒退回冻结、按批次补充 `SHARPS_PACKAGING_DAMAGED` 事故记录，回收点将针剂**重新封装到全新空锐器盒并生成新标签**后才能再次交接 |
| 异常 | 夹带未过期药(扣回积分)、处方信息泄露、针剂未封装、锐器包装破损、液体泄漏、称重争议(复秤调账)、兑换失败、公司拒收 |
| 看板 | 管理员查看超期药品、是否需要双人交接、是否达上门量、容量预警、封盒/破损锐器盒、待复核/未结异常数 |
| 追溯 | 批次时间线：预约→称重→封袋→复核→暂存/锐器盒转入→交接→积分流水→异常，交接单含去向/重量/锐器数量/照片/签收人/积分结果 |

## 本地启动

### 方式 A：Docker 一键启动（推荐）

```bash
docker compose up --build
# 启动后：
#   服务      http://localhost:8080
#   Swagger UI http://localhost:8080/swagger-ui.html
#   OpenAPI   http://localhost:8080/v3/api-docs
#   健康检查   curl http://localhost:8080/api/health
```

Compose 同时启动 PostgreSQL 15（库名/账号/口令均为 medrecycle）与应用，首次启动自动建表并写入演示数据。

### 方式 B：本地已有 PostgreSQL

```bash
# 1. 准备 PostgreSQL
docker compose up -d db          # 或使用已有实例，手工建库：
# psql -U postgres -c "CREATE DATABASE medrecycle"
# psql -U postgres -c "CREATE USER medrecycle WITH PASSWORD 'medrecycle'"
# psql -U postgres -c "GRANT ALL PRIVILEGES ON DATABASE medrecycle TO medrecycle"
# psql -U postgres -d medrecycle -c "GRANT ALL ON SCHEMA public TO medrecycle"

# 2. 启动服务（可用环境变量覆盖连接）
DB_URL=jdbc:postgresql://localhost:5432/medrecycle \
DB_USER=medrecycle DB_PASSWORD=medrecycle \
mvn spring-boot:run

# 或打包后运行
mvn package -DskipTests
java -jar target/med-recycle-1.0.0.jar
```

启动后表结构自动创建（`ddl-auto: update`），首次启动写入演示数据（站点/暂存柜/锐器盒/积分规则/礼品/测试账号/活动日，可用 `SEED_DEMO_DATA=false` 关闭）。

健康检查：`curl http://localhost:8080/api/health`
接口文档（纯后端项目）：Swagger UI `http://localhost:8080/swagger-ui.html`，OpenAPI 3.x 文档 `http://localhost:8080/v3/api-docs`。

## 测试账号

登录：`POST /api/auth/login` `{"username":"...","password":"..."}`，返回 `token`，后续请求带请求头 `X-Auth-Token: <token>`。

| 账号 | 密码 | 角色 |
| --- | --- | --- |
| resident1 / resident2 | resident123 | 居民（预约、查积分、兑换、查追溯） |
| recycler1 / recycler2 | recycler123 | 回收员（登记、称重、入柜、针剂转锐器盒、交接签收） |
| reviewer1 | reviewer123 | 复核员（高风险/针剂封装复核） |
| admin1 / admin2 | admin123 | 社区管理员（看板、登记锐器盒、发起交接） |

演示数据：2 个社区点、3 个暂存柜、**2 个锐器盒（阳光 200 支/20kg、河畔 100 支/10kg，预警线 80%）**、6 条品类积分规则（精神类/注射器为高风险）、3 种礼品、1 个积分 1.5 倍的活动日；resident1 预置 100 积分。

## 针剂安全封装复核怎么用

```
1. 称重（回收员）  POST /api/batches/{batchNo}/weigh
   SYRINGE 记录必须带 sharpsCount、punctureResistant、mixedWithNormalBags、photoUrls
   - punctureResistant=false 或 mixedWithNormalBags=true
       -> 自动生成 UNSAFE_INJECTION 异常 + 复核任务，复核通过前不发积分
2. 复核（复核员）  POST /api/reviews/{taskNo}/decide {"approve": true, "note": "现场已转防刺盒"}
3. 转入锐器盒（回收员） POST /api/sharps/deposit
   {"containerNo":"SC...","bagNo":"BAG...","photoUrls":"..."}  -> 返回 SHL 特殊标签
   普通暂存柜 /api/storage/put 对针剂袋一律 409 拒绝
4. 容量管理：达 80% -> GET /api/sharps/notifications 见 NEAR_FULL 提前通知；
   达上限 -> 转入 409 拒绝并生成 FULL 停收通知；封盒 POST /api/sharps/containers/{no}/seal
5. 专项交接（管理员） POST /api/handovers（默认带上全部暂存袋+已封盒锐器盒，自动双人）
6. 公司签收（回收员） POST /api/handovers/{no}/complete
   sharpsContainers 逐项给 receivedSharpsCount/receivedWeightKg；
   packagingDamaged=true 拒收 -> 事故记录 + 冻结旧盒
7. 重新封装（回收员） POST /api/sharps/repackage
   {"damagedContainerNo":"SC...","newContainerNo":"SC...","photoUrls":"..."} -> 新 SHL 标签、旧盒 DISPOSED
```

## 验证方式（均已实际执行通过）

### 1. 集成测试（PostgreSQL 实测，11 个用例）

```bash
# 测试库：默认 jdbc:postgresql://localhost:5432/medrecycle_test，可用 TEST_DB_URL 覆盖
psql -U postgres -c "CREATE DATABASE medrecycle_test OWNER medrecycle"
mvn test
```

- `FullFlowIntegrationTest`：预约→登记→称重(含高风险)→复核→积分→暂存→双人交接→完成→追溯全链路断言
- `ExceptionFlowIntegrationTest`：夹带未过期药扣回积分并复核拒绝、称重争议复秤调账、环保公司拒收回柜并记录异常
- `ConcurrencyAndCapacityTest`：20 线程并发兑换 5 件库存不超卖（成功恰为 5、余额精确）、暂存柜容量强制与 FULL 预警
- `SharpsSafetyIntegrationTest`（本次新增 5 例）：
  - 安全针剂四要素称重→复核→转锐器盒(SHL 标签)→普通柜拒收→封盒→双人专项交接→公司按 20 支/0.5kg 签收
  - 未使用防刺容器→`UNSAFE_INJECTION` 异常+复核任务→复核处置后补封入盒
  - 缺锐器数量/缺照片称重 400；混入普通药袋触发未封装异常
  - 容量 80% 生成 NEAR_FULL 提前通知；超限转入 409 且生成 FULL 停收通知，针剂可转备用盒
  - 签收破损→整盒冻结+按批次事故记录+禁止再交接；重新封装生成新标签、旧盒报废、新盒再次交接完成

本次验证执行结果：`Tests run: 11, Failures: 0, Errors: 0`（PostgreSQL 15 实测）。

### 2. 端到端脚本（HTTP 全流程，82 项断言）

```bash
# 先启动服务；断言基于演示数据初始状态，重复执行前请重建数据库 medrecycle
./verify.sh
```

脚本用 curl 走通：登录→预约(活动日)→登记→称重(含针剂四要素)→针剂缺要素拦截→高风险复核→普通袋入柜/针剂转锐器盒(针剂进普通柜被拒、SHL 标签)→看板→双人签收→公司按锐器数量重量签收→追溯→称重争议复秤→夹带未过期药扣回→公司拒收→积分兑换成功/失败，以及**针剂专项**：未封装补封、容量 NEAR_FULL/FULL 通知与满盒停收、包装破损事故、重新封装换新标签后再次交接，共 82 项断言。本次验证执行结果：`通过 82 项, 失败 0 项`。

## 主要接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | /api/auth/login | 登录取 token |
| POST | /api/appointments | 居民预约（含四类标识、活动日） |
| POST | /api/batches/check-in | 扫码登记生成批次 |
| POST | /api/batches/{no}/weigh | 分类称重封袋（SYRINGE 须带封装四要素） |
| POST | /api/batches/{no}/finish-weighing | 称重完成 |
| GET | /api/batches/{no}/trace | 批次全链路追溯（含锐器盒转入记录与标签） |
| GET/POST | /api/reviews, /api/reviews/{no}/decide | 复核列表与审批 |
| GET/POST | /api/points/balance, /api/points/exchange | 积分查询与兑换 |
| POST | /api/storage/put | 普通袋入暂存柜（容量强校验；针剂 409 拒绝） |
| GET | /api/storage/cabinets, /overdue, /alerts | 柜状态/超期/容量预警 |
| POST | /api/sharps/containers | 管理员登记锐器盒（容量、预警比例） |
| GET | /api/sharps/containers, /containers/{no} | 锐器盒列表/详情（转入记录、标签、通知） |
| POST | /api/sharps/deposit | 针剂复核通过后转入锐器盒（生成 SHL 标签、容量校验） |
| POST | /api/sharps/containers/{no}/seal | 锐器盒封盒待清运 |
| POST | /api/sharps/repackage | 包装破损后重新封装到全新空盒（新标签、旧盒报废、事故记录） |
| GET | /api/sharps/notifications | NEAR_FULL 提前通知 / FULL 停收通知 |
| POST | /api/handovers, /{no}/sign, /{no}/complete | 交接建单(普通袋+封盒锐器盒)/双人签收/按袋按盒确认 |
| GET | /api/handovers/{no} | 交接单详情（含锐器盒数量/重量核对结果、积分结果） |
| POST | /api/exceptions, /{no}/reweigh, /{no}/resolve | 异常上报/复秤/办结 |
| GET | /api/admin/dashboard?siteId= | 管理员看板（含锐器盒容量/封盒/破损） |

完整请求/响应结构见 Swagger UI：`http://localhost:8080/swagger-ui.html`。

## 关键设计

- **批次状态机**：`CHECKED_IN → WEIGHING → IN_REVIEW → POINTS_GRANTED → STORED → (PARTIALLY_)HANDED_OVER → CLOSED`，由袋状态自动推导（`BatchRefresher`），针剂入锐器盒（`IN_SHARPS_BOX`）与普通袋入柜同属"已安全暂存"；预约随批次终态完结。
- **针剂安全封装闭环**：称重四要素确认 → 未封装即异常+复核（不发积分）→ 普通柜物理拒收、只能转锐器盒 → 转入即生成 SHL 特殊交接标签 → 封盒专项交接 → 公司按**锐器数量+重量**逐盒确认 → 破损补事故记录并重新封装（新标签/旧盒报废），每个环节在批次追溯中留痕。
- **容量双轨强校验**：普通暂存柜与锐器盒各自独立容量（袋数/重量、锐器数/重量），写入均走悲观锁防超卖；锐器盒 80% 预警通知（独立事务提交，拒绝转入时通知不回滚）、100% 停收并通知清运，杜绝"继续接收却无法安全暂存"。
- **积分规则快照**：预约时固化 `TABLET:10,ANTIBIOTIC:15,...`，后续称重/复秤均按快照计价，规则调整不影响在途批次。
- **并发安全**：积分兑换（账户+礼品悲观锁，固定加锁顺序）、暂存柜/锐器盒入盒（容器悲观锁）、活动日名额（乐观锁重试），保证兑换峰值不超卖、容量不超卖。
- **异常联动**：异常不是孤立记录——夹带未过期药自动扣回积分并退回复核；液体泄漏/针剂未封装/处方信息泄露强制人工复核；称重争议复秤后按快照多退少补；公司拒收普通袋自动回柜、锐器盒按破损与否分流到"冻结待重封"或"可再次交接"。
