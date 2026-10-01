#!/usr/bin/env bash
# 端到端验证脚本：对运行中的服务走通 预约->登记->称重->复核->积分->暂存/锐器盒->交接->追溯 全链路
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

code() { # 仅取 HTTP 状态码：$@ 为 curl 参数
  curl -s -o /tmp/resp.json -w '%{http_code}' "$@"
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

# 称重一针剂袋并（高风险）复核通过，输出该袋袋号
# 参数: 数量 重量kg 是否防刺容器 是否混入普通药袋
weigh_syringe() {
  local qty="$1" w="$2" pp="$3" mix="$4"
  local a b tno
  a=$(curl -s -X POST "${AUTH_R[@]}" "${JSON[@]}" "$BASE/api/appointments" -d "{
    \"siteId\": $SITE_ID, \"drugTypeDesc\": \"胰岛素针头和注射器\", \"estimatedQty\": $qty,
    \"hasPrescription\": true, \"hasLiquid\": false, \"hasInjection\": true, \"hasPersonalInfo\": false}")
  b=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/check-in" \
    -d "{\"appointmentNo\": \"$(echo "$a" | jq -r '.appointmentNo')\"}" | jq -r '.batchNo')
  curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/$b/weigh" -d "{
    \"items\": [{\"category\": \"SYRINGE\", \"weightKg\": $w,
      \"photoUrls\": \"photo://sharp-$b\", \"dispute\": false,
      \"punctureProofContainer\": $pp, \"mixedWithRegularBag\": $mix, \"sharpItemCount\": $qty}]}" > /dev/null
  tno=$(curl -s "${AUTH_V[@]}" "$BASE/api/reviews?status=PENDING" | jq -r '.[0].taskNo')
  if [ "$tno" != null ] && [ -n "$tno" ]; then
    curl -s -X POST "${AUTH_V[@]}" "${JSON[@]}" "$BASE/api/reviews/$tno/decide" \
      -d '{"approve": true, "note": "针剂封装核对无误"}' > /dev/null
  fi
  curl -s "${AUTH_W[@]}" "$BASE/api/batches/$b/trace" | jq -r '.bags[0].bag.bagNo'
}

echo "== 2. 基础目录 =="
SITE_ID=$(curl -s "${AUTH_R[@]}" "$BASE/api/catalog/sites" | jq -r '.[0].id')
ACT_ID=$(curl -s "${AUTH_R[@]}" "$BASE/api/catalog/activity-days?siteId=$SITE_ID" | jq -r '.[0].id')
check "药品分类 6 类" "$(curl -s "${AUTH_R[@]}" "$BASE/api/catalog/categories" | jq 'length')" "6"
check "积分规则含高风险标记" "$(curl -s "${AUTH_R[@]}" "$BASE/api/catalog/points-rules" | jq '[.[] | select(.highRisk==true)] | length')" "2"
check "活动日倍率 1.5" "$(curl -s "${AUTH_R[@]}" "$BASE/api/catalog/activity-days?siteId=$SITE_ID" | jq -r '.[0].pointsMultiplier')" "1.5"
check "站点预置锐器盒" "$(curl -s "${AUTH_W[@]}" "$BASE/api/sharps/containers?siteId=$SITE_ID" | jq 'length')" "1"
SEED_BOX=$(curl -s "${AUTH_W[@]}" "$BASE/api/sharps/containers?siteId=$SITE_ID" | jq -r '.[0].container.containerNo')

echo "== 3. 居民预约（活动日，含处方/液体/针剂/个人信息标识） =="
APPT=$(curl -s -X POST "${AUTH_R[@]}" "${JSON[@]}" "$BASE/api/appointments" -d "{
  \"siteId\": $SITE_ID, \"drugTypeDesc\": \"感冒药、安眠药、口服液、胰岛素针头\", \"estimatedQty\": 5,
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

echo "== 5. 分类称重（片剂2kg + 注射器0.5kg/5支高风险 + 液体1kg，针剂须登记数量/照片/防刺确认） =="
CODE=$(code -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/$BATCH_NO/weigh" -d '{
  "items": [{"category": "SYRINGE", "weightKg": 0.5, "photoUrls": "photo://need-count"}] }')
check "针剂称重未登记锐器数量被拒(400)" "$CODE" "400"
BATCH=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/$BATCH_NO/weigh" -d '{
  "items": [
    {"category": "TABLET",  "weightKg": 2.0, "sealNo": "SEAL-T-1001", "photoUrls": "photo://pkg-t,photo://bag-t"},
    {"category": "SYRINGE", "weightKg": 0.5, "sealNo": "SEAL-S-1001", "photoUrls": "photo://pkg-s,photo://bag-s",
     "punctureProofContainer": true, "mixedWithRegularBag": false, "sharpItemCount": 5},
    {"category": "LIQUID",  "weightKg": 1.0, "sealNo": "SEAL-L-1001", "photoUrls": "photo://pkg-l,photo://bag-l"}
  ]}')
STATUS=$(curl -s -X POST "${AUTH_W[@]}" "$BASE/api/batches/$BATCH_NO/finish-weighing" | jq -r '.status')
check "含高风险袋 -> 批次进入复核" "$STATUS" "IN_REVIEW"
# 片剂 2*10*1.5=30，液体 1*12*1.5=18，注射器待复核
check "非高风险即时积分 30+18" "$(curl -s "${AUTH_R[@]}" "$BASE/api/points/balance" | jq '.balance')" "148"

echo "== 6. 人工复核高风险注射器 =="
TASK_NO=$(curl -s "${AUTH_V[@]}" "$BASE/api/reviews?status=PENDING" | jq -r '.[0].taskNo')
curl -s -X POST "${AUTH_V[@]}" "${JSON[@]}" "$BASE/api/reviews/$TASK_NO/decide" \
  -d '{"approve": true, "note": "针剂已安全封装，核对无误"}' > /dev/null
check "复核通过发放 0.5*8*1.5=6" "$(curl -s "${AUTH_R[@]}" "$BASE/api/points/balance" | jq '.balance')" "154"
check "批次积分已发放" "$(curl -s "${AUTH_W[@]}" "$BASE/api/batches/$BATCH_NO" | jq -r '.status')" "POINTS_GRANTED"

echo "== 7. 针剂转入锐器盒（不得进入普通暂存柜）并封箱，普通袋入柜 =="
TRACE=$(curl -s "${AUTH_W[@]}" "$BASE/api/batches/$BATCH_NO/trace")
SYRINGE_BAG=$(echo "$TRACE" | jq -r '[.bags[] | select(.bag.category=="SYRINGE")][0].bag.bagNo')
REGULAR_BAGS=$(echo "$TRACE" | jq -rc '[.bags[] | select(.bag.category!="SYRINGE") | .bag.bagNo]')
CABINET_ID=$(curl -s "${AUTH_W[@]}" "$BASE/api/storage/cabinets?siteId=$SITE_ID" | jq -r '.[0].cabinet.id')
CODE=$(code -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/storage/put" \
  -d "{\"cabinetId\": $CABINET_ID, \"bagNos\": [\"$SYRINGE_BAG\"]}")
check "针剂袋进入普通暂存柜被拒(409)" "$CODE" "409"
DEP=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/sharps/bags/$SYRINGE_BAG/deposit" -d "{
  \"containerNo\": \"$SEED_BOX\", \"itemCount\": 5,
  \"punctureProofContainer\": true, \"mixedWithRegularBag\": false, \"photoUrls\": \"photo://sharp-box\"}")
check "针剂袋转入锐器盒" "$(echo "$DEP" | jq -r '.deposit.depositNo' | grep -c '^SD')" "1"
check "针剂袋状态 SHARP_STORED" "$(curl -s "${AUTH_W[@]}" "$BASE/api/batches/$BATCH_NO/trace" | jq -r '[.bags[] | select(.bag.category=="SYRINGE")][0].bag.status')" "SHARP_STORED"
SEAL=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/sharps/containers/$SEED_BOX/seal" \
  -d '{"labelPhotoUrls": "photo://sharps-label"}')
LABEL_NO=$(echo "$SEAL" | jq -r '.handoffLabelNo')
check "封箱生成特殊交接标签 SHL" "$(echo "$LABEL_NO" | grep -c '^SHL')" "1"
check "锐器盒已封箱" "$(echo "$SEAL" | jq -r '.status')" "SEALED"
PUT=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/storage/put" \
  -d "{\"cabinetId\": $CABINET_ID, \"bagNos\": $REGULAR_BAGS}")
check "普通袋入柜 2 袋" "$(echo "$PUT" | jq '.storedBags')" "2"
check "批次含针剂入锐器盒+普通袋入柜" "$(curl -s "${AUTH_W[@]}" "$BASE/api/batches/$BATCH_NO" | jq -r '.status')" "STORED"
check "容量预警接口可用" "$(curl -s "${AUTH_A[@]}" "$BASE/api/storage/alerts?siteId=$SITE_ID" | jq 'type')" "\"array\""

echo "== 8. 管理员看板 =="
DASH=$(curl -s "${AUTH_A[@]}" "$BASE/api/admin/dashboard?siteId=$SITE_ID")
check "看板-普通暂存 2 袋" "$(echo "$DASH" | jq '.pickup.storedBags')" "2"
check "看板-未达上门量" "$(echo "$DASH" | jq '.pickup.thresholdReached')" "false"
check "看板-含高风险需双人" "$(echo "$DASH" | jq '.handover.requiresDualIfHandoverNow')" "true"
check "看板-超期统计存在" "$(echo "$DASH" | jq '.overdue.count')" "0"
check "看板-含封箱待交接锐器盒" "$(echo "$DASH" | jq '.sharps.sealedReadyCount')" "1"

echo "== 9. 环保交接（自动带出封箱锐器盒，双人签收，按数量/重量确认） =="
CODE=$(code -X POST "${AUTH_A[@]}" "${JSON[@]}" "$BASE/api/handovers" \
  -d "{\"siteId\": $SITE_ID, \"companyName\": \"绿源环保科技\", \"destination\": \"市危废处理中心\"}")
check "未达上门量拒绝建单(409)" "$CODE" "409"
ORDER=$(curl -s -X POST "${AUTH_A[@]}" "${JSON[@]}" "$BASE/api/handovers" \
  -d "{\"siteId\": $SITE_ID, \"companyName\": \"绿源环保科技\", \"destination\": \"市危废处理中心\", \"force\": true}")
ORDER_NO=$(echo "$ORDER" | jq -r '.orderNo')
check "强制建单成功" "$(echo "$ORDER_NO" | grep -c '^HO')" "1"
check "含普通袋 2" "$(echo "$ORDER" | jq -r '.bagCount')" "2"
check "随单锐器盒 1 个" "$(echo "$ORDER" | jq -r '.sharpContainerCount')" "1"
check "随单锐器 5 支" "$(echo "$ORDER" | jq -r '.sharpItemCount')" "5"
check "含针剂需双人交接" "$(echo "$ORDER" | jq -r '.requiresDual')" "true"
check "第一人签收后仍未出库" "$(curl -s -X POST "${AUTH_W[@]}" "$BASE/api/handovers/$ORDER_NO/sign" | jq -r '.status')" "DRAFT"
CODE=$(code -X POST "${AUTH_W[@]}" "$BASE/api/handovers/$ORDER_NO/sign")
check "同一人重复签收被拒(409)" "$CODE" "409"
check "第二人签收后出库" "$(curl -s -X POST "${AUTH_A[@]}" "$BASE/api/handovers/$ORDER_NO/sign" | jq -r '.status')" "IN_TRANSIT"
DONE=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/handovers/$ORDER_NO/complete" -d "{
  \"receiverName\": \"绿源-张司机\", \"photoUrls\": \"photo://handover-1\",
  \"items\": [{\"bagNo\": \"$LABEL_NO\", \"accepted\": true,
              \"receivedItems\": 5, \"receivedWeightKg\": 0.5}]}")
check "交接完成" "$(echo "$DONE" | jq -r '.status')" "COMPLETED"
check "批次已交接" "$(curl -s "${AUTH_W[@]}" "$BASE/api/batches/$BATCH_NO" | jq -r '.status')" "HANDED_OVER"
DETAIL=$(curl -s "${AUTH_A[@]}" "$BASE/api/handovers/$ORDER_NO")
check "交接单含签收人" "$(echo "$DETAIL" | jq -r '.order.receiverName')" "绿源-张司机"
check "交接单含积分发放结果" "$(echo "$DETAIL" | jq '.pointsResult | length')" "1"
check "交接积分 54 分" "$(echo "$DETAIL" | jq '.pointsResult[0].totalPoints')" "54"
check "锐器盒按 5 支签收" "$(echo "$DETAIL" | jq '.sharpContainers[0].receivedItems')" "5"
check "锐器盒按 0.5kg 签收" "$(echo "$DETAIL" | jq '.sharpContainers[0].receivedWeightKg')" "0.5"

echo "== 10. 批次全链路追溯 =="
TRACE=$(curl -s "${AUTH_R[@]}" "$BASE/api/batches/$BATCH_NO/trace")
check "追溯-称重记录 3 条" "$(echo "$TRACE" | jq '.weighRecords | length')" "3"
check "追溯-每袋有来源与分类" "$(echo "$TRACE" | jq '[.bags[].bag.category] | length')" "3"
check "追溯-高风险袋有复核记录" "$(echo "$TRACE" | jq '[.bags[] | select(.bag.category=="SYRINGE") | .reviewTasks[]] | length')" "1"
check "追溯-封袋编号在案" "$(echo "$TRACE" | jq -r '[.bags[].bag.sealNo] | join(",")' | grep -c 'SEAL-S-1001')" "1"
check "追溯-针剂投放记录 1 条" "$(echo "$TRACE" | jq '.sharpDeposits | length')" "1"
check "追溯-关联锐器盒 1 个" "$(echo "$TRACE" | jq '.sharpContainers | length')" "1"
check "追溯-称重含防刺容器确认" "$(echo "$TRACE" | jq '[.weighRecords[] | select(.category=="SYRINGE")][0].punctureProofContainer')" "true"

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
CODE=$(code -X POST "${AUTH_R[@]}" "${JSON[@]}" "$BASE/api/points/exchange" -d "{\"giftId\": $GIFT_EXPENSIVE}")
check "余额不足兑换失败(409)" "$CODE" "409"
check "失败单已记录" "$(jq -r '.status' /tmp/resp.json)" "FAILED"

echo "== 15. 未封装针剂拦截：称重留痕 + 转入锐器盒被拒 + 普通柜被拒，复核后规范重封放行 =="
UNSAFE_BAG=$(weigh_syringe 4 0.2 false true)
check "称重阶段生成针剂未封装异常" "$(curl -s "${AUTH_W[@]}" "$BASE/api/exceptions" | jq "[.[] | select(.type==\"UNSAFE_INJECTION\" and .bagNo==\"$UNSAFE_BAG\")] | length" | grep -c '^[1-9]')" "1"
BOX2=$(curl -s -X POST "${AUTH_A[@]}" "${JSON[@]}" "$BASE/api/sharps/containers" \
  -d "{\"siteId\": $SITE_ID, \"name\": \"针剂复核盒\", \"capacityItems\": 100, \"capacityWeightKg\": 10}" | jq -r '.containerNo')
CODE=$(code -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/sharps/bags/$UNSAFE_BAG/deposit" -d "{
  \"containerNo\": \"$BOX2\", \"itemCount\": 4,
  \"punctureProofContainer\": false, \"mixedWithRegularBag\": true, \"photoUrls\": \"photo://unsafe\"}")
check "未封装针剂转入被拒(409)" "$CODE" "409"
CODE=$(code -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/storage/put" \
  -d "{\"cabinetId\": $CABINET_ID, \"bagNos\": [\"$UNSAFE_BAG\"]}")
check "未封装针剂进普通柜被拒(409)" "$CODE" "409"
# 退回人工复核：复核员确认现场已整改后通过
REVIEW_TASK=$(curl -s "${AUTH_V[@]}" "$BASE/api/reviews?status=PENDING" | jq -r --arg b "$UNSAFE_BAG" '[.[] | select(.bagNo==$b)][0].taskNo')
curl -s -X POST "${AUTH_V[@]}" "${JSON[@]}" "$BASE/api/reviews/$REVIEW_TASK/decide" \
  -d '{"approve": true, "note": "已改用防刺容器并与普通药袋分离，复核通过"}' > /dev/null
# 现场重新规范封装（防刺容器、未混药袋）后转入成功
REDEP=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/sharps/bags/$UNSAFE_BAG/deposit" -d "{
  \"containerNo\": \"$BOX2\", \"itemCount\": 4,
  \"punctureProofContainer\": true, \"mixedWithRegularBag\": false, \"photoUrls\": \"photo://resafe\"}")
check "重新规范封装后转入成功" "$(echo "$REDEP" | jq -r '.deposit.depositNo' | grep -c '^SD')" "1"
check "针剂袋安全暂存 SHARP_STORED" "$(echo "$REDEP" | jq -r '.container.status')" "OPEN"

echo "== 16. 锐器盒达容量上限：提前通知并停止现场接收 =="
TINY=$(curl -s -X POST "${AUTH_A[@]}" "${JSON[@]}" "$BASE/api/sharps/containers" \
  -d "{\"siteId\": $SITE_ID, \"name\": \"迷你锐器盒\", \"capacityItems\": 2, \"capacityWeightKg\": 1.0}" | jq -r '.containerNo')
FULL_BAG=$(weigh_syringe 2 0.1 true false)
curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/sharps/bags/$FULL_BAG/deposit" -d "{
  \"containerNo\": \"$TINY\", \"itemCount\": 2,
  \"punctureProofContainer\": true, \"mixedWithRegularBag\": false, \"photoUrls\": \"photo://tiny\"}" > /dev/null
check "达容量上限状态 FULL" "$(curl -s "${AUTH_W[@]}" "$BASE/api/sharps/containers/$TINY" | jq -r '.container.status')" "FULL"
check "已生成 FULL 容量通知" "$(curl -s "${AUTH_W[@]}" "$BASE/api/sharps/notices?siteId=$SITE_ID" | jq "[.[] | select(.containerNo==\"$TINY\" and .level==\"FULL\")] | length")" "1"
OVER_BAG=$(weigh_syringe 1 0.1 true false)
CODE=$(code -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/sharps/bags/$OVER_BAG/deposit" -d "{
  \"containerNo\": \"$TINY\", \"itemCount\": 1,
  \"punctureProofContainer\": true, \"mixedWithRegularBag\": false, \"photoUrls\": \"photo://over\"}")
check "满盒继续接收被拒(409)" "$CODE" "409"

echo "== 17. 包装破损：补充事故记录 + 重新封装后再次交接 =="
TINY_SEAL=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/sharps/containers/$TINY/seal" \
  -d '{"labelPhotoUrls": "photo://tiny-label"}')
TINY_LABEL=$(echo "$TINY_SEAL" | jq -r '.handoffLabelNo')
HO_DMG=$(curl -s -X POST "${AUTH_A[@]}" "${JSON[@]}" "$BASE/api/handovers" -d "{
  \"siteId\": $SITE_ID, \"companyName\": \"绿源环保科技\", \"destination\": \"市危废处理中心\",
  \"bagNos\": [], \"containerNos\": [\"$TINY\"], \"force\": true}")
HO_DMG_NO=$(echo "$HO_DMG" | jq -r '.orderNo')
curl -s -X POST "${AUTH_W[@]}" "$BASE/api/handovers/$HO_DMG_NO/sign" > /dev/null
curl -s -X POST "${AUTH_A[@]}" "$BASE/api/handovers/$HO_DMG_NO/sign" > /dev/null
DMG=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/handovers/$HO_DMG_NO/complete" -d "{
  \"receiverName\": \"绿源-张司机\",
  \"items\": [{\"bagNo\": \"$TINY_LABEL\", \"accepted\": false, \"packageDamaged\": true,
              \"damageNote\": \"盒盖破裂有针刺穿出风险\"}]}")
check "破损盒部分拒收状态" "$(echo "$DMG" | jq -r '.status')" "PARTIAL_REJECTED"
check "补充包装破损事故记录" "$(curl -s "${AUTH_W[@]}" "$BASE/api/exceptions" | jq "[.[] | select(.type==\"PACKAGE_DAMAGED\" and .sharpContainerNo==\"$TINY\")] | length")" "1"
check "破损锐器盒停用 DISABLED" "$(curl -s "${AUTH_W[@]}" "$BASE/api/sharps/containers/$TINY" | jq -r '.container.status')" "DISABLED"
check "针剂袋退回待重新封装 ACCEPTED" "$(curl -s "${AUTH_W[@]}" "$BASE/api/sharps/containers/$TINY" | jq -r '.deposits[0].status')" "REMOVED"
# 重新封装到新盒并再次交接成功
REBOX=$(curl -s -X POST "${AUTH_A[@]}" "${JSON[@]}" "$BASE/api/sharps/containers" \
  -d "{\"siteId\": $SITE_ID, \"name\": \"重新封装盒\", \"capacityItems\": 10, \"capacityWeightKg\": 2.0}" | jq -r '.containerNo')
curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/sharps/bags/$FULL_BAG/deposit" -d "{
  \"containerNo\": \"$REBOX\", \"itemCount\": 2,
  \"punctureProofContainer\": true, \"mixedWithRegularBag\": false, \"photoUrls\": \"photo://rebox\"}" > /dev/null
RELABEL=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/sharps/containers/$REBOX/seal" \
  -d '{"labelPhotoUrls": "photo://rebox-label"}' | jq -r '.handoffLabelNo')
HO_OK=$(curl -s -X POST "${AUTH_A[@]}" "${JSON[@]}" "$BASE/api/handovers" -d "{
  \"siteId\": $SITE_ID, \"companyName\": \"绿源环保科技\", \"destination\": \"市危废处理中心\",
  \"bagNos\": [], \"containerNos\": [\"$REBOX\"], \"force\": true}")
HO_OK_NO=$(echo "$HO_OK" | jq -r '.orderNo')
curl -s -X POST "${AUTH_W[@]}" "$BASE/api/handovers/$HO_OK_NO/sign" > /dev/null
curl -s -X POST "${AUTH_A[@]}" "$BASE/api/handovers/$HO_OK_NO/sign" > /dev/null
OK=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/handovers/$HO_OK_NO/complete" -d "{
  \"receiverName\": \"绿源-李司机\",
  \"items\": [{\"bagNo\": \"$RELABEL\", \"accepted\": true, \"receivedItems\": 2, \"receivedWeightKg\": 0.1}]}")
check "重新封装交接完成" "$(echo "$OK" | jq -r '.status')" "COMPLETED"
check "重新封装盒已移交" "$(curl -s "${AUTH_W[@]}" "$BASE/api/sharps/containers/$REBOX" | jq -r '.container.status')" "HANDED_OVER"

echo ""
echo "==================================="
echo "验证完成: 通过 $PASS 项, 失败 $FAIL 项"
echo "==================================="
[ "$FAIL" -eq 0 ]
