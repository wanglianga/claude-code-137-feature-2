#!/usr/bin/env bash
# 端到端验证脚本：对运行中的服务走通 预约->登记->称重->复核->积分->暂存->交接->追溯 全链路
# 用法: ./verify.sh  （默认 BASE_URL=http://localhost:8080，可用 BASE_URL 环境变量覆盖）
set -euo pipefail

BASE="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

check() { # $1=描述 $2=实际 $3=期望
  if [ "$2" == "$3" ]; then
    PASS=$((PASS+1)); echo "  ✔ $1"
  else
    FAIL=$((FAIL+1)); echo "  ✘ $1  期望=[$3] 实际=[$2]"
  fi
}

login() {
  curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"$2\"}" | jq -r '.token'
}

echo "== 0. 健康检查 =="
HEALTH=$(curl -s "$BASE/api/health" | jq -r '.status')
check "服务健康" "$HEALTH" "UP"

echo "== 1. 四类角色登录 =="
T_RESIDENT=$(login resident1 resident123)
T_RECYCLER=$(login recycler1 recycler123)
T_RECYCLER2=$(login recycler2 recycler123)
T_REVIEWER=$(login reviewer1 reviewer123)
T_ADMIN=$(login admin1 admin123)
check "居民登录" "$([ -n "$T_RESIDENT" ] && [ "$T_RESIDENT" != null ] && echo ok)" "ok"
check "回收员登录" "$([ -n "$T_RECYCLER" ] && [ "$T_RECYCLER" != null ] && echo ok)" "ok"
check "复核员登录" "$([ -n "$T_REVIEWER" ] && [ "$T_REVIEWER" != null ] && echo ok)" "ok"
check "管理员登录" "$([ -n "$T_ADMIN" ] && [ "$T_ADMIN" != null ] && echo ok)" "ok"

AUTH_R=(-H "X-Auth-Token: $T_RESIDENT")
AUTH_W=(-H "X-Auth-Token: $T_RECYCLER")
AUTH_V=(-H "X-Auth-Token: $T_REVIEWER")
AUTH_A=(-H "X-Auth-Token: $T_ADMIN")
JSON=(-H 'Content-Type: application/json')

echo "== 2. 基础目录 =="
SITE_ID=$(curl -s "${AUTH_R[@]}" "$BASE/api/catalog/sites" | jq -r '.[0].id')
ACT_ID=$(curl -s "${AUTH_R[@]}" "$BASE/api/catalog/activity-days?siteId=$SITE_ID" | jq -r '.[0].id')
check "药品分类 6 类" "$(curl -s "${AUTH_R[@]}" "$BASE/api/catalog/categories" | jq 'length')" "6"
check "积分规则含高风险标记" "$(curl -s "${AUTH_R[@]}" "$BASE/api/catalog/points-rules" | jq '[.[] | select(.highRisk==true)] | length')" "2"
check "活动日倍率 1.5" "$(curl -s "${AUTH_R[@]}" "$BASE/api/catalog/activity-days?siteId=$SITE_ID" | jq -r '.[0].pointsMultiplier')" "1.5"

echo "== 3. 居民预约（活动日，含处方/液体/个人信息标识） =="
APPT=$(curl -s -X POST "${AUTH_R[@]}" "${JSON[@]}" "$BASE/api/appointments" -d "{
  \"siteId\": $SITE_ID, \"drugTypeDesc\": \"感冒药、安眠药、口服液\", \"estimatedQty\": 5,
  \"hasPrescription\": true, \"hasLiquid\": true, \"hasInjection\": true,
  \"hasPersonalInfo\": true, \"activityDayId\": $ACT_ID}")
APPT_NO=$(echo "$APPT" | jq -r '.appointmentNo')
check "预约单号生成" "$(echo "$APPT_NO" | grep -c '^AP')" "1"
check "积分规则快照已固化" "$(echo "$APPT" | jq -r '.pointsRuleSnapshot' | grep -c 'TABLET:10')" "1"
check "活动日名额 +1" "$(curl -s "${AUTH_R[@]}" "$BASE/api/catalog/activity-days?siteId=$SITE_ID" | jq '.[0].bookedCount')" "1"

echo "== 4. 回收员扫码登记 =="
BATCH=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/check-in" -d "{\"appointmentNo\": \"$APPT_NO\"}")
BATCH_NO=$(echo "$BATCH" | jq -r '.batchNo')
check "批次状态 CHECKED_IN" "$(echo "$BATCH" | jq -r '.status')" "CHECKED_IN"
check "批次倍率继承活动日" "$(echo "$BATCH" | jq -r '.pointsMultiplier')" "1.5"

echo "== 5. 分类称重封袋（片剂2kg + 注射器0.5kg高风险 + 液体1kg） =="
# 针剂称重必须完成封装复核四要素：防刺容器、是否混入普通药袋、锐器数量、照片
BATCH=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/$BATCH_NO/weigh" -d '{
  "items": [
    {"category": "TABLET",  "weightKg": 2.0, "sealNo": "SEAL-T-1001", "photoUrls": "photo://pkg-t,photo://bag-t"},
    {"category": "SYRINGE", "weightKg": 0.5, "sealNo": "SEAL-S-1001", "photoUrls": "photo://pkg-s,photo://bag-s",
     "punctureResistant": true, "mixedWithNormalBags": false, "sharpsCount": 10},
    {"category": "LIQUID",  "weightKg": 1.0, "sealNo": "SEAL-L-1001", "photoUrls": "photo://pkg-l,photo://bag-l"}
  ]}')
STATUS=$(curl -s -X POST "${AUTH_W[@]}" "$BASE/api/batches/$BATCH_NO/finish-weighing" | jq -r '.status')
check "含高风险袋 -> 批次进入复核" "$STATUS" "IN_REVIEW"
# 片剂 2*10*1.5=30，液体 1*12*1.5=18，注射器待复核
check "非高风险即时积分 30+18" "$(curl -s "${AUTH_R[@]}" "$BASE/api/points/balance" | jq '.balance')" "148"
# 针剂缺四要素（无锐器数量）必须拒绝登记
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/$BATCH_NO/weigh" -d '{
  "items": [{"category": "SYRINGE", "weightKg": 0.3, "photoUrls": "photo://x",
             "punctureResistant": true, "mixedWithNormalBags": false, "sharpsCount": 0}]}')
check "针剂缺锐器数量拒绝称重(400)" "$CODE" "400"

echo "== 6. 人工复核高风险注射器 =="
TASK_NO=$(curl -s "${AUTH_V[@]}" "$BASE/api/reviews?status=PENDING" | jq -r '.[0].taskNo')
curl -s -X POST "${AUTH_V[@]}" "${JSON[@]}" "$BASE/api/reviews/$TASK_NO/decide" \
  -d '{"approve": true, "note": "针剂已安全封装，核对无误"}' > /dev/null
check "复核通过发放 0.5*8*1.5=6" "$(curl -s "${AUTH_R[@]}" "$BASE/api/points/balance" | jq '.balance')" "154"
check "批次积分已发放" "$(curl -s "${AUTH_W[@]}" "$BASE/api/batches/$BATCH_NO" | jq -r '.status')" "POINTS_GRANTED"

echo "== 7. 普通袋入暂存柜，针剂转入锐器盒 =="
TRACE=$(curl -s "${AUTH_W[@]}" "$BASE/api/batches/$BATCH_NO/trace")
BAG_NOS=$(echo "$TRACE" | jq -r '[.bags[] | select(.bag.category!="SYRINGE") | .bag.bagNo] | @json')
SYRINGE_BAG=$(echo "$TRACE" | jq -r '.bags[] | select(.bag.category=="SYRINGE") | .bag.bagNo')
CABINET_ID=$(curl -s "${AUTH_W[@]}" "$BASE/api/storage/cabinets?siteId=$SITE_ID" | jq -r '.[0].cabinet.id')
PUT=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/storage/put" \
  -d "{\"cabinetId\": $CABINET_ID, \"bagNos\": $BAG_NOS}")
check "普通袋入柜 2 袋" "$(echo "$PUT" | jq '.storedBags')" "2"
# 针剂不得进入普通暂存柜
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/storage/put" \
  -d "{\"cabinetId\": $CABINET_ID, \"bagNos\": [\"$SYRINGE_BAG\"]}")
check "针剂进普通柜被拒(409)" "$CODE" "409"
# 针剂转入站点锐器盒，转入即生成 SHL 特殊交接标签
SHARPS_BOX=$(curl -s "${AUTH_W[@]}" "$BASE/api/sharps/containers?siteId=$SITE_ID&status=IN_USE" | jq -r '.[0].containerNo')
DEP=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/sharps/deposit" \
  -d "{\"containerNo\": \"$SHARPS_BOX\", \"bagNo\": \"$SYRINGE_BAG\", \"photoUrls\": \"photo://sharps-in-1\"}")
SHL=$(echo "$DEP" | jq -r '.labelNo')
check "转入生成 SHL 特殊标签" "$(echo "$SHL" | grep -c '^SHL')" "1"
check "锐器盒内 10 支锐器" "$(echo "$DEP" | jq '.totalSharps')" "10"
check "针剂袋已入锐器盒" "$(curl -s "${AUTH_W[@]}" "$BASE/api/batches/$BATCH_NO/trace" | jq -r '.bags[] | select(.bag.category=="SYRINGE") | .bag.status')" "IN_SHARPS_BOX"
# 封盒后才能随交接单清运
curl -s -X POST "${AUTH_W[@]}" "$BASE/api/sharps/containers/$SHARPS_BOX/seal" > /dev/null
check "批次全部安全暂存" "$(curl -s "${AUTH_W[@]}" "$BASE/api/batches/$BATCH_NO" | jq -r '.status')" "STORED"
check "容量预警接口可用" "$(curl -s "${AUTH_A[@]}" "$BASE/api/storage/alerts?siteId=$SITE_ID" | jq 'type')" "\"array\""

echo "== 8. 管理员看板 =="
DASH=$(curl -s "${AUTH_A[@]}" "$BASE/api/admin/dashboard?siteId=$SITE_ID")
check "看板-暂存 2 袋" "$(echo "$DASH" | jq '.pickup.storedBags')" "2"
check "看板-封盒锐器盒 1 个" "$(echo "$DASH" | jq '.pickup.sealedSharpsContainers')" "1"
check "看板-未达上门量" "$(echo "$DASH" | jq '.pickup.thresholdReached')" "false"
check "看板-含锐器盒需双人" "$(echo "$DASH" | jq '.handover.requiresDualIfHandoverNow')" "true"
check "看板-超期统计存在" "$(echo "$DASH" | jq '.overdue.count')" "0"
check "看板-锐器盒视图存在" "$(echo "$DASH" | jq '.sharps.containers | length' | grep -c '^[1-9]')" "1"

echo "== 9. 环保交接（上门量校验 -> 双人签收 -> 按袋/按锐器盒完成） =="
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${AUTH_A[@]}" "${JSON[@]}" "$BASE/api/handovers" \
  -d "{\"siteId\": $SITE_ID, \"companyName\": \"绿源环保科技\", \"destination\": \"市危废处理中心\"}")
check "未达上门量拒绝建单(409)" "$CODE" "409"
ORDER=$(curl -s -X POST "${AUTH_A[@]}" "${JSON[@]}" "$BASE/api/handovers" \
  -d "{\"siteId\": $SITE_ID, \"companyName\": \"绿源环保科技\", \"destination\": \"市危废处理中心\", \"force\": true}")
ORDER_NO=$(echo "$ORDER" | jq -r '.orderNo')
check "强制建单成功" "$(echo "$ORDER_NO" | grep -c '^HO')" "1"
check "含高风险/锐器盒需双人交接" "$(echo "$ORDER" | jq -r '.requiresDual')" "true"
check "交接单含 1 个锐器盒" "$(echo "$ORDER" | jq '.sharpsContainerCount')" "1"
check "交接锐器 10 支" "$(echo "$ORDER" | jq '.sharpsCount')" "10"
check "第一人签收后仍未出库" "$(curl -s -X POST "${AUTH_W[@]}" "$BASE/api/handovers/$ORDER_NO/sign" | jq -r '.status')" "DRAFT"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${AUTH_W[@]}" "$BASE/api/handovers/$ORDER_NO/sign")
check "同一人重复签收被拒(409)" "$CODE" "409"
check "第二人签收后出库" "$(curl -s -X POST "${AUTH_A[@]}" "$BASE/api/handovers/$ORDER_NO/sign" | jq -r '.status')" "IN_TRANSIT"
# 环保公司按锐器数量与重量逐盒确认
DONE=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/handovers/$ORDER_NO/complete" \
  -d "{\"receiverName\": \"绿源-张司机\", \"photoUrls\": \"photo://handover-1\",
       \"sharpsContainers\": [{\"containerNo\": \"$SHARPS_BOX\", \"accepted\": true,
         \"receivedSharpsCount\": 10, \"receivedWeightKg\": 0.5}]}")
check "交接完成" "$(echo "$DONE" | jq -r '.status')" "COMPLETED"
check "批次已交接" "$(curl -s "${AUTH_W[@]}" "$BASE/api/batches/$BATCH_NO" | jq -r '.status')" "HANDED_OVER"
DETAIL=$(curl -s "${AUTH_A[@]}" "$BASE/api/handovers/$ORDER_NO")
check "交接单含签收人" "$(echo "$DETAIL" | jq -r '.order.receiverName')" "绿源-张司机"
check "交接单含积分发放结果" "$(echo "$DETAIL" | jq '.pointsResult | length')" "1"
check "交接积分 54 分" "$(echo "$DETAIL" | jq '.pointsResult[0].totalPoints')" "54"
check "交接单含锐器盒明细" "$(echo "$DETAIL" | jq '.sharpsContainers | length')" "1"
check "公司按 10 支签收" "$(echo "$DETAIL" | jq '.sharpsContainers[0].receivedSharpsCount')" "10"
check "公司按 0.5kg 签收" "$(echo "$DETAIL" | jq '.sharpsContainers[0].receivedWeightKg')" "0.5"
check "公司已签收锐器盒" "$(echo "$DETAIL" | jq -r '.sharpsContainers[0].accepted')" "true"

echo "== 10. 批次全链路追溯 =="
TRACE=$(curl -s "${AUTH_R[@]}" "$BASE/api/batches/$BATCH_NO/trace")
check "追溯-称重记录 3 条" "$(echo "$TRACE" | jq '.weighRecords | length')" "3"
check "追溯-每袋有来源与分类" "$(echo "$TRACE" | jq '[.bags[].bag.category] | length')" "3"
check "追溯-高风险袋有复核记录" "$(echo "$TRACE" | jq '[.bags[] | select(.bag.category=="SYRINGE") | .reviewTasks[]] | length')" "1"
check "追溯-封袋编号在案" "$(echo "$TRACE" | jq -r '[.bags[].bag.sealNo] | join(",")' | grep -c 'SEAL-S-1001')" "1"
check "追溯-针剂有锐器盒转入记录" "$(echo "$TRACE" | jq '[.bags[] | select(.bag.category=="SYRINGE") | .sharpsDeposits[]] | length')" "1"
check "追溯-特殊标签在案" "$(echo "$TRACE" | jq -r '[.bags[] | select(.bag.category=="SYRINGE") | .bag.sharpsLabelNo] | join("")' | grep -c '^SHL')" "1"

echo "== 11. 称重争议复秤 =="
APPT2=$(curl -s -X POST "${AUTH_R[@]}" "${JSON[@]}" "$BASE/api/appointments" -d "{
  \"siteId\": $SITE_ID, \"drugTypeDesc\": \"阿莫西林\", \"estimatedQty\": 2,
  \"hasPrescription\": true, \"hasLiquid\": false, \"hasInjection\": false, \"hasPersonalInfo\": false}")
APPT2_NO=$(echo "$APPT2" | jq -r '.appointmentNo')
BATCH2_NO=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/check-in" \
  -d "{\"appointmentNo\": \"$APPT2_NO\"}" | jq -r '.batchNo')
curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/$BATCH2_NO/weigh" \
  -d '{"items": [{"category": "ANTIBIOTIC", "weightKg": 2.0, "dispute": true, "photoUrls": "photo://pkg-a"}]}' > /dev/null
check "争议自动积分 2*15=30" "$(curl -s "${AUTH_R[@]}" "$BASE/api/points/balance" | jq '.balance')" "184"
EX_NO=$(curl -s "${AUTH_W[@]}" "$BASE/api/exceptions?status=OPEN" | jq -r '[.[] | select(.type=="WEIGHT_DISPUTE")][0].exceptionNo')
check "称重争议异常自动生成" "$(echo "$EX_NO" | grep -c '^EX')" "1"
curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/exceptions/$EX_NO/reweigh" -d '{"newWeightKg": 2.6}' > /dev/null
check "复秤补差 2.6*15=39" "$(curl -s "${AUTH_R[@]}" "$BASE/api/points/balance" | jq '.balance')" "193"
check "复秤后异常已办结" "$(curl -s "${AUTH_W[@]}" "$BASE/api/exceptions/$EX_NO" | jq -r '.status')" "RESOLVED"

echo "== 12. 夹带未过期药 -> 扣回积分并复核拒绝 =="
BAG2_NO=$(curl -s "${AUTH_W[@]}" "$BASE/api/batches/$BATCH2_NO/trace" | jq -r '.bags[0].bag.bagNo')
curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/exceptions" \
  -d "{\"batchNo\": \"$BATCH2_NO\", \"bagNo\": \"$BAG2_NO\", \"type\": \"NON_EXPIRED_DRUG\", \"description\": \"发现未过期药品\"}" > /dev/null
check "积分已扣回" "$(curl -s "${AUTH_R[@]}" "$BASE/api/points/balance" | jq '.balance')" "154"
TASK2_NO=$(curl -s "${AUTH_V[@]}" "$BASE/api/reviews?status=PENDING" | jq -r '.[0].taskNo')
curl -s -X POST "${AUTH_V[@]}" "${JSON[@]}" "$BASE/api/reviews/$TASK2_NO/decide" \
  -d '{"approve": false, "note": "未过期药品退回居民"}' > /dev/null
check "批次关闭" "$(curl -s "${AUTH_W[@]}" "$BASE/api/batches/$BATCH2_NO" | jq -r '.status')" "CLOSED"

echo "== 13. 环保公司拒收 -> 回柜 + 异常 =="
APPT3_NO=$(curl -s -X POST "${AUTH_R[@]}" "${JSON[@]}" "$BASE/api/appointments" -d "{
  \"siteId\": $SITE_ID, \"drugTypeDesc\": \"软膏\", \"estimatedQty\": 1,
  \"hasPrescription\": false, \"hasLiquid\": false, \"hasInjection\": false, \"hasPersonalInfo\": false}" | jq -r '.appointmentNo')
BATCH3_NO=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/check-in" \
  -d "{\"appointmentNo\": \"$APPT3_NO\"}" | jq -r '.batchNo')
curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/$BATCH3_NO/weigh" \
  -d '{"items": [{"category": "EXTERNAL", "weightKg": 1.0}]}' > /dev/null
BAG3_NO=$(curl -s "${AUTH_W[@]}" "$BASE/api/batches/$BATCH3_NO/trace" | jq -r '.bags[0].bag.bagNo')
curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/storage/put" \
  -d "{\"cabinetId\": $CABINET_ID, \"bagNos\": [\"$BAG3_NO\"]}" > /dev/null
ORDER3_NO=$(curl -s -X POST "${AUTH_A[@]}" "${JSON[@]}" "$BASE/api/handovers" \
  -d "{\"siteId\": $SITE_ID, \"companyName\": \"绿源环保科技\", \"destination\": \"市危废处理中心\", \"force\": true}" | jq -r '.orderNo')
curl -s -X POST "${AUTH_W[@]}" "$BASE/api/handovers/$ORDER3_NO/sign" > /dev/null
REJ=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/handovers/$ORDER3_NO/complete" \
  -d "{\"receiverName\": \"绿源-张司机\", \"items\": [{\"bagNo\": \"$BAG3_NO\", \"accepted\": false, \"rejectReason\": \"管状包装不在接收范围\"}]}")
check "部分拒收状态" "$(echo "$REJ" | jq -r '.status')" "PARTIAL_REJECTED"
check "拒收异常已记录" "$(curl -s "${AUTH_W[@]}" "$BASE/api/exceptions" | jq '[.[] | select(.type=="COMPANY_REJECT")] | length' | grep -c '^[1-9]')" "1"
check "拒收袋退回暂存" "$(curl -s "${AUTH_W[@]}" "$BASE/api/batches/$BATCH3_NO/trace" | jq -r '.bags[0].bag.status')" "STORED"

echo "== 14. 积分兑换（成功 + 余额不足失败） =="
GIFT_OK=$(curl -s "${AUTH_R[@]}" "$BASE/api/catalog/gifts" | jq -r '[.[] | select(.costPoints<=154)][0].id')
EX1=$(curl -s -X POST "${AUTH_R[@]}" "${JSON[@]}" "$BASE/api/points/exchange" -d "{\"giftId\": $GIFT_OK}")
check "兑换成功" "$(echo "$EX1" | jq -r '.status')" "SUCCESS"
# 余额 154-120=34，兑换 500 分体检券应失败
GIFT_EXPENSIVE=$(curl -s "${AUTH_R[@]}" "$BASE/api/catalog/gifts" | jq -r '[.[] | select(.costPoints==500)][0].id')
CODE=$(curl -s -o /tmp/ex_fail.json -w '%{http_code}' -X POST "${AUTH_R[@]}" "${JSON[@]}" "$BASE/api/points/exchange" -d "{\"giftId\": $GIFT_EXPENSIVE}")
check "余额不足兑换失败(409)" "$CODE" "409"
check "失败单已记录" "$(jq -r '.status' /tmp/ex_fail.json)" "FAILED"

echo "== 15. 针剂安全封装专项：未封装补封 -> 容量通知/满盒停收 -> 破损事故+重新封装 =="
# 15.1 居民提交未用防刺容器的胰岛素针头，回收员如实登记
APPT4_NO=$(curl -s -X POST "${AUTH_R[@]}" "${JSON[@]}" "$BASE/api/appointments" -d "{
  \"siteId\": $SITE_ID, \"drugTypeDesc\": \"胰岛素针头和注射器\", \"estimatedQty\": 12,
  \"hasPrescription\": false, \"hasLiquid\": false, \"hasInjection\": true, \"hasPersonalInfo\": false}" | jq -r '.appointmentNo')
BATCH4_NO=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/check-in" \
  -d "{\"appointmentNo\": \"$APPT4_NO\"}" | jq -r '.batchNo')
curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/$BATCH4_NO/weigh" -d '{
  "items": [{"category": "SYRINGE", "weightKg": 0.3, "sealNo": "SEAL-U-1001", "photoUrls": "photo://loose-needles",
             "punctureResistant": false, "mixedWithNormalBags": false, "sharpsCount": 12}]}' > /dev/null
BAG4_NO=$(curl -s "${AUTH_W[@]}" "$BASE/api/batches/$BATCH4_NO/trace" | jq -r '.bags[0].bag.bagNo')
check "未封装针剂待复核" "$(curl -s "${AUTH_W[@]}" "$BASE/api/batches/$BATCH4_NO/trace" | jq -r '.bags[0].bag.status')" "PENDING_REVIEW"
check "未封装针剂自动生成异常" "$(curl -s "${AUTH_W[@]}" "$BASE/api/exceptions" | jq "[.[] | select(.bagNo==\"$BAG4_NO\" and .type==\"UNSAFE_INJECTION\")] | length")" "1"
TASK4_NO=$(curl -s "${AUTH_W[@]}" "$BASE/api/batches/$BATCH4_NO/trace" | jq -r '.bags[0].reviewTasks[] | select(.status=="PENDING") | .taskNo')
curl -s -X POST "${AUTH_V[@]}" "${JSON[@]}" "$BASE/api/reviews/$TASK4_NO/decide" \
  -d '{"approve": true, "note": "现场监督居民将散针转入防刺锐器盒"}' > /dev/null
check "复核处置后验收" "$(curl -s "${AUTH_W[@]}" "$BASE/api/batches/$BATCH4_NO/trace" | jq -r '.bags[0].bag.status')" "ACCEPTED"

# 15.2 容量 10 支的小盒：12 支转入必然超限 -> 拒绝接收并生成 FULL 停收通知
BOX_TINY=$(curl -s -X POST "${AUTH_A[@]}" "${JSON[@]}" "$BASE/api/sharps/containers" \
  -d "{\"siteId\": $SITE_ID, \"name\": \"E2E-小容量锐器盒\", \"capacitySharps\": 10, \"capacityWeightKg\": 5, \"warnRatio\": 0.8}" | jq -r '.containerNo')
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/sharps/deposit" \
  -d "{\"containerNo\": \"$BOX_TINY\", \"bagNo\": \"$BAG4_NO\", \"photoUrls\": \"photo://try-full\"}")
check "满盒拒绝现场接收(409)" "$CODE" "409"
check "FULL 停收通知已生成" "$(curl -s "${AUTH_W[@]}" "$BASE/api/sharps/notifications?siteId=$SITE_ID" | jq "[.[] | select(.containerNo==\"$BOX_TINY\" and .type==\"FULL\")] | length")" "1"

# 15.3 转入容量 15 支的新盒：12/15=80%，触发 NEAR_FULL 提前通知
BOX=$(curl -s -X POST "${AUTH_A[@]}" "${JSON[@]}" "$BASE/api/sharps/containers" \
  -d "{\"siteId\": $SITE_ID, \"name\": \"E2E-针剂盒\", \"capacitySharps\": 15, \"capacityWeightKg\": 5, \"warnRatio\": 0.8}" | jq -r '.containerNo')
DEP4=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/sharps/deposit" \
  -d "{\"containerNo\": \"$BOX\", \"bagNo\": \"$BAG4_NO\", \"photoUrls\": \"photo://sharps-in-4\", \"note\": \"散针现场补封\"}")
check "转入预警 WARN" "$(echo "$DEP4" | jq -r '.warning')" "WARN"
check "NEAR_FULL 提前通知已生成" "$(curl -s "${AUTH_W[@]}" "$BASE/api/sharps/notifications?siteId=$SITE_ID" | jq "[.[] | select(.containerNo==\"$BOX\" and .type==\"NEAR_FULL\")] | length")" "1"
SHL4=$(echo "$DEP4" | jq -r '.labelNo')

# 15.4 封盒交接，公司签收发现包装破损 -> 整盒退回冻结 + 事故记录
curl -s -X POST "${AUTH_W[@]}" "$BASE/api/sharps/containers/$BOX/seal" > /dev/null
ORDER4_NO=$(curl -s -X POST "${AUTH_A[@]}" "${JSON[@]}" "$BASE/api/handovers" \
  -d "{\"siteId\": $SITE_ID, \"companyName\": \"绿源环保科技\", \"destination\": \"市危废处理中心\",
       \"bagNos\": [], \"sharpsContainerNos\": [\"$BOX\"], \"force\": true}" | jq -r '.orderNo')
curl -s -X POST "${AUTH_W[@]}" "$BASE/api/handovers/$ORDER4_NO/sign" > /dev/null
curl -s -X POST "${AUTH_A[@]}" "$BASE/api/handovers/$ORDER4_NO/sign" > /dev/null
REJ4=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/handovers/$ORDER4_NO/complete" \
  -d "{\"receiverName\": \"绿源-周签收\", \"photoUrls\": \"photo://damaged\",
       \"sharpsContainers\": [{\"containerNo\": \"$BOX\", \"accepted\": false, \"packagingDamaged\": true, \"rejectReason\": \"盒盖破裂有刺穿风险\"}]}")
check "破损盒部分拒收" "$(echo "$REJ4" | jq -r '.status')" "PARTIAL_REJECTED"
check "破损事故记录已补充" "$(curl -s "${AUTH_W[@]}" "$BASE/api/exceptions" | jq "[.[] | select(.bagNo==\"$BAG4_NO\" and .type==\"SHARPS_PACKAGING_DAMAGED\")] | length" | grep -c '^[1-9]')" "1"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${AUTH_A[@]}" "${JSON[@]}" "$BASE/api/handovers" \
  -d "{\"siteId\": $SITE_ID, \"companyName\": \"绿源环保科技\", \"destination\": \"市危废处理中心\",
       \"bagNos\": [], \"sharpsContainerNos\": [\"$BOX\"], \"force\": true}")
check "破损盒禁止再次交接(409)" "$CODE" "409"

# 15.5 重新封装到全新空盒：生成新标签，旧盒报废，新盒可再次交接
BOX_NEW=$(curl -s -X POST "${AUTH_A[@]}" "${JSON[@]}" "$BASE/api/sharps/containers" \
  -d "{\"siteId\": $SITE_ID, \"name\": \"E2E-重新封装盒\", \"capacitySharps\": 50, \"capacityWeightKg\": 10}" | jq -r '.containerNo')
REPACK=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/sharps/repackage" \
  -d "{\"damagedContainerNo\": \"$BOX\", \"newContainerNo\": \"$BOX_NEW\", \"photoUrls\": \"photo://repacked\", \"note\": \"更换破损锐器盒\"}")
SHL4_NEW=$(echo "$REPACK" | jq -r '.newLabelNo')
check "重新封装生成新标签" "$([ "$SHL4_NEW" != "$SHL4" ] && echo "$SHL4_NEW" | grep -c '^SHL')" "1"
check "新盒锐器数量 12 支" "$(echo "$REPACK" | jq '.movedSharps')" "12"
check "旧盒已报废" "$(curl -s "${AUTH_W[@]}" "$BASE/api/sharps/containers/$BOX" | jq -r '.container.status')" "DISPOSED"
check "新盒已封盒待清运" "$(curl -s "${AUTH_W[@]}" "$BASE/api/sharps/containers/$BOX_NEW" | jq -r '.container.status')" "SEALED"
ORDER5_NO=$(curl -s -X POST "${AUTH_A[@]}" "${JSON[@]}" "$BASE/api/handovers" \
  -d "{\"siteId\": $SITE_ID, \"companyName\": \"绿源环保科技\", \"destination\": \"市危废处理中心\",
       \"bagNos\": [], \"sharpsContainerNos\": [\"$BOX_NEW\"], \"force\": true}" | jq -r '.orderNo')
curl -s -X POST "${AUTH_W[@]}" "$BASE/api/handovers/$ORDER5_NO/sign" > /dev/null
curl -s -X POST "${AUTH_A[@]}" "$BASE/api/handovers/$ORDER5_NO/sign" > /dev/null
DONE5=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/handovers/$ORDER5_NO/complete" \
  -d "{\"receiverName\": \"绿源-周签收\", \"sharpsContainers\": [{\"containerNo\": \"$BOX_NEW\", \"accepted\": true, \"receivedSharpsCount\": 12, \"receivedWeightKg\": 0.3}]}")
check "重新封装后交接完成" "$(echo "$DONE5" | jq -r '.status')" "COMPLETED"
check "新盒已交接" "$(curl -s "${AUTH_W[@]}" "$BASE/api/sharps/containers/$BOX_NEW" | jq -r '.container.status')" "HANDED_OVER"

echo ""
echo "==================================="
echo "验证完成: 通过 $PASS 项, 失败 $FAIL 项"
echo "==================================="
[ "$FAIL" -eq 0 ]
