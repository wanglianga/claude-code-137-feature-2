# 社区药箱过期药回收称重兑换与环保交接服务

基于 **Java 17 + Spring Boot 3 + PostgreSQL** 的社区过期药品回收服务，覆盖完整链路：

```
居民预约 -> 回收员扫码登记 -> 分类称重封袋 -> 高风险人工复核 -> 积分发放
        -> 普通药入暂存柜 / 针剂安全封装复核后转入锐器盒(容量预警/特殊交接标签)
        -> 环保交接(上门量/双人签收/逐袋及按锐器数量签收/拒收/破损重封) -> 批次追溯
```

预约、称重、复核、积分、暂存、锐器盒、交接与异常全部挂在**同一批次（RecycleBatch）**上，交接前每袋药品都有来源（预约/批次）与分类。

## 功能清单

| 模块 | 说明 |
| --- | --- |
| 预约 | 记录药品类型、数量、是否含处方药/液体/针剂/个人信息包装、社区点、活动日；预约时固化**积分规则快照** |
| 登记称重 | 回收员扫码登记生成批次；按 普通片剂/抗生素/精神类/注射器/外用药/液体药 六类称重，记录包装照片与封袋编号；**针剂必须登记锐器数量、照片并确认是否防刺容器/混入普通药袋** |
| 人工复核 | 高风险品类（精神类、注射器）及异常触发的袋先复核，通过才发积分、才准继续流转 |
| 针剂安全封装 | 居民提交胰岛素针头/注射器时回收员现场确认四要素（防刺容器、是否混入普通药袋、数量重量、照片）；**未封装针剂不能进入普通暂存柜**，须转入**锐器盒**；未封装自动生成异常并退回人工复核 |
| 锐器盒 | 针剂专用防刺容器，按支数/重量容量强校验（悲观锁不超装）；达预警比例/容量上限**提前通知环保公司或上级回收点**，满盒停止现场接收；封箱生成**特殊交接标签(SHL)**整盒移交 |
| 积分 | 按品类每公斤积分 × 活动日倍率发放；流水可查；兑换走悲观锁应对峰值；失败落 FAILED 兑换单 |
| 暂存柜 | 普通药入柜容量强校验（悲观锁防超卖）、使用率预警（WARN/FULL）、超期暂存检查；**针剂袋硬拒绝入柜** |
| 环保交接 | 上门量阈值校验、含高风险/锐器盒/超重自动要求**双人签收**、环保公司逐袋签收/拒收，**锐器盒按锐器数量与重量逐盒确认**；包装破损则回收点补充事故记录、停用锐器盒并重新封装 |
| 异常 | 夹带未过期药(扣回积分)、处方信息泄露、针剂未封装、液体泄漏、称重争议(复秤调账)、兑换失败、公司拒收、**锐器盒包装破损** |
| 看板 | 管理员查看超期药品、是否需要双人交接、是否达上门量、容量预警、待复核/未结异常数、**锐器盒预警/满盒/封箱待交接数量** |
| 追溯 | 批次时间线：预约→称重(含针剂封装确认)→封袋→复核→暂存/锐器盒投放→交接→积分流水→异常，交接单含去向/重量/照片/签收人/锐器盒按数量重量签收结果/积分发放结果 |
| 接口文档 | 纯后端服务，内置 OpenAPI 3.x 文档与 Swagger UI |

## 本地启动

### 方式 A（推荐，一键）：Docker Compose

```bash
docker compose up --build      # 启动 PostgreSQL + 回收服务
# 服务：http://localhost:8080 ，Swagger UI：http://localhost:8080/swagger-ui.html
```

### 方式 B：本地已有 PostgreSQL

```bash
# 1) 准备 PostgreSQL
# docker compose up -d db      # 或只起数据库
psql -U postgres -c "CREATE DATABASE medrecycle"
psql -U postgres -c "CREATE USER medrecycle WITH PASSWORD 'medrecycle'"
psql -U postgres -c "GRANT ALL PRIVILEGES ON DATABASE medrecycle TO medrecycle"
psql -U postgres -d medrecycle -c "GRANT ALL ON SCHEMA public TO medrecycle"

# 2) 启动服务（默认连接 jdbc:postgresql://localhost:5432/medrecycle，可用环境变量覆盖）
DB_URL=jdbc:postgresql://localhost:5432/medrecycle \
DB_USER=medrecycle \
DB_PASSWORD=medrecycle \
mvn spring-boot:run

# 或打包后运行
mvn package -DskipTests
java -jar target/med-recycle-1.0.0.jar
```

启动后表结构自动创建（`ddl-auto: update`），首次启动写入演示数据（站点/暂存柜/锐器盒/积分规则/礼品/测试账号/活动日，可用 `SEED_DEMO_DATA=false` 关闭）。

健康检查：`curl http://localhost:8080/api/health`
接口文档：OpenAPI 3.x JSON `http://localhost:8080/v3/api-docs`，Swagger UI `http://localhost:8080/swagger-ui.html`

## 测试账号

登录：`POST /api/auth/login` `{"username":"...","password":"..."}`，返回 `token`，后续请求带请求头 `X-Auth-Token: <token>`。

| 账号 | 密码 | 角色 |
| --- | --- | --- |
| resident1 / resident2 | resident123 | 居民（预约、查积分、兑换、查追溯） |
| recycler1 / recycler2 | recycler123 | 回收员（登记、称重、针剂封装复核/转锐器盒、入柜、封箱、交接签收） |
| reviewer1 | reviewer123 | 复核员（高风险与针剂未封装复核） |
| admin1 / admin2 | admin123 | 社区管理员（看板、发起交接、登记锐器盒） |

演示数据：2 个社区点、3 个暂存柜、每站点 1 个锐器盒、6 条品类积分规则（精神类/注射器为高风险）、3 种礼品、1 个积分 1.5 倍的活动日；resident1 预置 100 积分。

## 验证方式（均已实际执行通过）

### 1. 集成测试（PostgreSQL 15 实测，10 个用例）

```bash
# 测试库：默认 jdbc:postgresql://localhost:5432/medrecycle_test，可用 TEST_DB_URL 覆盖
psql -U postgres -c "CREATE DATABASE medrecycle_test OWNER medrecycle"
mvn test
```

- `FullFlowIntegrationTest`：预约→登记→称重(含高风险)→复核→积分→暂存→双人交接→完成→追溯全链路断言
- `ExceptionFlowIntegrationTest`：夹带未过期药扣回积分并复核拒绝、称重争议复秤调账、环保公司拒收回柜并记录异常
- `ConcurrencyAndCapacityTest`：20 线程并发兑换 5 件库存不超卖（成功恰为 5、余额精确）、暂存柜容量强制与 FULL 预警
- `SharpsSafetyIntegrationTest`（本次新增 4 例）：针剂称重强制数量/照片；未封装针剂被普通柜与锐器盒双拦截、事故留痕退回复核；安全转盒→容量预警/满盒通知并停止接收→封箱 SHL→双人交接按数量重量签收；包装破损补充事故、停用旧盒、重新封装后再次交接成功；批次追溯含投放与锐器盒

本次验证执行结果：`Tests run: 10, Failures: 0, Errors: 0`（PostgreSQL 15 实测）。

### 2. 端到端脚本（HTTP 全流程，81 项断言）

```bash
# 服务运行中执行（断言基于演示数据初始状态，重复执行前请重建数据库 medrecycle）
./verify.sh
```

脚本用 curl 走通：登录→预约(活动日)→登记→称重(片剂/针剂/液体，针剂含四要素确认)→未登记数量被拒→高风险复核→积分→**针剂进普通柜被拒、转锐器盒、封箱 SHL**→普通袋入柜→看板(含封箱锐器盒/双人判定)→上门量拦截→**自动带出锐器盒的双人签收**→**按锐器数量与重量签收**→追溯(含投放/封装确认)→称重争议复秤→夹带未过期药扣回→公司拒收→积分兑换成功/失败→**未封装针剂拦截并复核后规范重封**→**锐器盒满盒通知并停止接收**→**包装破损事故、停用旧盒、重新封装再次交接**，共 81 项断言。本次验证执行结果：`通过 81 项, 失败 0 项`。

## 主要接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | /api/auth/login | 登录取 token |
| POST | /api/appointments | 居民预约（含四类标识、活动日） |
| POST | /api/batches/check-in | 扫码登记生成批次 |
| POST | /api/batches/{no}/weigh | 分类称重封袋（照片、封袋编号、争议标记；针剂须传数量/防刺容器/混入药袋确认） |
| POST | /api/batches/{no}/finish-weighing | 称重完成 |
| GET | /api/batches/{no}/trace | 批次全链路追溯（含针剂投放与锐器盒） |
| GET/POST | /api/reviews, /api/reviews/{no}/decide | 复核列表与审批 |
| GET/POST | /api/points/balance, /api/points/exchange | 积分查询与兑换 |
| POST | /api/storage/put | 普通袋入暂存柜（容量强校验；针剂袋硬拒绝） |
| GET | /api/storage/cabinets, /api/storage/overdue, /api/storage/alerts | 柜状态/超期/容量预警 |
| POST/GET | /api/sharps/containers, /api/sharps/containers/{no} | 登记/查询锐器盒（使用率、状态、特殊标签） |
| POST | /api/sharps/bags/{bagNo}/deposit | 针剂安全封装复核后转入锐器盒（防刺容器+未混药袋+数量+照片） |
| POST | /api/sharps/containers/{no}/seal | 封箱生成特殊交接标签(SHL) |
| POST/GET | /api/sharps/containers/{no}/notify, /api/sharps/notices | 容量预警/上限通知（环保公司/上级回收点）与通知记录 |
| POST | /api/handovers, /{no}/sign, /{no}/complete | 交接建单(自动带出封箱锐器盒)/签收(双人)/完成(逐袋与按锐器数量重量、拒收、破损) |
| GET | /api/handovers/{no} | 交接单详情（去向/重量/照片/签收人/锐器盒签收/积分结果） |
| POST | /api/exceptions, /{no}/reweigh, /{no}/resolve | 异常上报/复秤/办结 |
| GET | /v3/api-docs, /swagger-ui.html | OpenAPI 3.x 文档与 Swagger UI |
| GET | /api/admin/dashboard?siteId= | 管理员看板（含锐器盒预警/满盒/封箱待交接） |

## 关键设计

- **批次状态机**：`CHECKED_IN → WEIGHING → IN_REVIEW → POINTS_GRANTED → STORED → (PARTIALLY_)HANDED_OVER → CLOSED`，由袋状态自动推导（`BatchRefresher`），预约随批次终态完结；针剂袋新增 `SHARP_STORED` 态，与普通入柜一并视为"已安全暂存"。
- **针剂安全封装硬关口**：针剂（`SYRINGE`）称重即强制登记锐器数量/照片/防刺容器/混入药袋；`StorageService` 对针剂袋入普通柜直接拒绝；`SharpService.deposit` 复核四要素，未封装走 `REQUIRES_NEW` 独立事务留存 `UNSAFE_INJECTION` 事故并退回人工复核（外层投放回滚但事故保留），规范重封经复核通过后才准入锐器盒。
- **锐器盒容量与通知**：锐器盒按支数与重量双上限，投放松悲观锁保证不超装；达站点预警比例自动 `WARN` 通知、达上限置 `FULL` 并通知环保公司、现场停止接收，避免"继续接收却无法安全暂存"；通知有去重标记，也可手动通知上级回收点。
- **针剂特殊交接**：封箱生成唯一特殊交接标签 `SHL`；交接建单自动带出站点全部封箱锐器盒（可指定），含锐器盒即强制双人签收；`handover_item` 以标签为键，环保公司按锐器数量与重量逐盒确认；包装破损置 `PACKAGE_DAMAGED` 事故、停用旧盒（`DISABLED`）、针剂袋退回 `ACCEPTED` 重新封装到新盒后再次交接。
- **积分规则快照**：预约时固化 `TABLET:10,ANTIBIOTIC:15,...`，后续称重/复秤均按快照计价，规则调整不影响在途批次。
- **并发安全**：积分兑换（账户+礼品悲观锁，固定加锁顺序）、暂存柜入柜（柜悲观锁）、锐器盒投放（盒悲观锁）、活动日名额（乐观锁重试），保证兑换峰值不超卖、容量不超装。
- **异常联动**：异常不是孤立记录——夹带未过期药自动扣回积分并退回复核；液体泄漏/针剂未封装/处方信息泄露强制人工复核；称重争议复秤后按快照多退少补；公司拒收普通袋自动回柜、拒收锐器盒整盒退回待交接；包装破损自动停用并要求重新封装。
