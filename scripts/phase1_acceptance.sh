#!/bin/bash
# Phase1 联调验收脚本（设计文档 6.2 checklist）
# 前置：gateway(8080)/agent-core(8082)/user-service(8081)/storage-service(8084)/image-engine(8085)/video-engine(8086) 已启动
# 用法：bash scripts/phase1_acceptance.sh
# 注意：请求体全部 ASCII —— Windows 下 curl 传中文会被转成 GBK 导致 400（服务端 UTF-8）
set -u
cd "$(dirname "$0")/.."
mkdir -p scripts/tmp

BASE="http://127.0.0.1:8080/api/v1"
PASS="Phase1Test@2026"

step() { echo; echo "===== $1 ====="; }
jqget() { python -c "import sys,json;d=json.load(sys.stdin);print($1)" 2>/dev/null; }

# ---------- 0. 注册/登录三个测试用户 ----------
step "0. 注册/登录测试用户"
for u in phase1_owner phase1_member phase1_stranger; do
  curl -s -X POST $BASE/auth/register -H "Content-Type: application/json" \
    -d "{\"username\":\"$u\",\"password\":\"$PASS\",\"email\":\"$u@test.local\"}" > /dev/null
done

TOKEN() { curl -s -X POST $BASE/auth/login -H "Content-Type: application/json" -d "{\"username\":\"$1\",\"password\":\"$PASS\"}" | jqget "d['data']['accessToken']"; }
UID_OF() { curl -s $BASE/users/me -H "Authorization: Bearer $1" | jqget "d['data']['id']"; }
OWNER_TOKEN=$(TOKEN phase1_owner)
MEMBER_TOKEN=$(TOKEN phase1_member)
STRANGER_TOKEN=$(TOKEN phase1_stranger)
OWNER_ID=$(UID_OF "$OWNER_TOKEN")
MEMBER_ID=$(UID_OF "$MEMBER_TOKEN")
STRANGER_ID=$(UID_OF "$STRANGER_TOKEN")
echo "owner=$OWNER_ID member=$MEMBER_ID stranger=$STRANGER_ID"

# ---------- 1. 模型能力目录 ----------
step "1. GET /model-capabilities（TEXT_GEN/TEXT2IMAGE 有 enabled 行）"
curl -s $BASE/model-capabilities -H "Authorization: Bearer $OWNER_TOKEN" | jqget "sorted(set(k for k in d['data'] if d['data'][k]))"
curl -s $BASE/model-capabilities -H "Authorization: Bearer $OWNER_TOKEN" | jqget "[ (c['provider'],c['enabled']) for c in d['data']['TEXT2IMAGE'] ]"

# ---------- 2. 建项目 ----------
step "2. 创建项目"
PROJECT_ID=$(curl -s -X POST $BASE/projects -H "Authorization: Bearer $OWNER_TOKEN" -H "Content-Type: application/json" -d '{"name":"Phase1 Acceptance Project"}' | jqget "d['data']['id']")
echo "projectId=$PROJECT_ID"

# ---------- 3. 添加成员 ----------
step "3. 租户成员 → 项目成员（VIEWER）"
OWNER_TENANT=$(curl -s $BASE/users/me -H "Authorization: Bearer $OWNER_TOKEN" | jqget "d['data']['tenantId']")
echo "ownerTenant=$OWNER_TENANT"
echo "（owner 先把 member 加进自己租户，项目成员要求同租户）"
curl -s -X POST $BASE/tenants/$OWNER_TENANT/members -H "Authorization: Bearer $OWNER_TOKEN" -H "Content-Type: application/json" -d "{\"userId\":\"$MEMBER_ID\",\"roleCode\":\"CONTENT_CREATOR\"}"; echo
echo "（再加为项目 VIEWER）"
curl -s -X POST $BASE/projects/$PROJECT_ID/members -H "Authorization: Bearer $OWNER_TOKEN" -H "Content-Type: application/json" -d "{\"userId\":\"$MEMBER_ID\",\"roleCode\":\"VIEWER\"}"; echo
curl -s $BASE/projects/$PROJECT_ID/members -H "Authorization: Bearer $OWNER_TOKEN" | head -c 400; echo

# ---------- 4. 更新简报 ----------
step "4. 更新创作简报"
curl -s -X PUT $BASE/projects/$PROJECT_ID/brief -H "Authorization: Bearer $OWNER_TOKEN" -H "Content-Type: application/json" -d '{"targetAudience":"GenZ summer shoppers","theme":"Summer new product launch","tone":"fresh"}'; echo

# ---------- 5. 权限：非成员 403 ----------
step "5. 非成员访问项目详情（期望 403）"
curl -s $BASE/projects/$PROJECT_ID -H "Authorization: Bearer $STRANGER_TOKEN"; echo
echo "（对照：成员 VIEWER 可读）"
curl -s $BASE/projects/$PROJECT_ID -H "Authorization: Bearer $MEMBER_TOKEN" | head -c 200; echo
echo "（对照：VIEWER 不可写）"
curl -s -X PUT $BASE/projects/$PROJECT_ID/brief -H "Authorization: Bearer $MEMBER_TOKEN" -H "Content-Type: application/json" -d '{"tone":"hack"}'; echo

# ---------- 6. 项目列表/详情/时间线 ----------
step "6. 项目列表 + 详情 + 时间线"
curl -s "$BASE/projects?page=1&size=10" -H "Authorization: Bearer $OWNER_TOKEN" | jqget "d['data']['records']"
curl -s $BASE/projects/$PROJECT_ID/timeline -H "Authorization: Bearer $OWNER_TOKEN" | head -c 500; echo

# ---------- 7. 上传文件（参考图） ----------
step "7. 上传参考图"
python -c "
import base64
png = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==')
open('scripts/tmp/ref.png','wb').write(png)
"
FILE_ID=$(curl -s -X POST $BASE/files/upload -H "Authorization: Bearer $OWNER_TOKEN" -F "file=@scripts/tmp/ref.png" -F "storageClass=ASSET" | jqget "d['data']['id']")
echo "fileId=$FILE_ID"

# ---------- 8. 创建 TEXT 资产（租户级）+ 搜索 ----------
step "8. 创建 TEXT 资产并搜索"
TEXT_ASSET_ID=$(curl -s -X POST $BASE/assets -H "Authorization: Bearer $OWNER_TOKEN" -H "Content-Type: application/json" -d '{"derivationType":"GENERATED","assetType":"TEXT","title":"Summer launch copy","contentText":"Fresh summer, new product launched!"}' | jqget "d['data']['id']")
echo "textAssetId=$TEXT_ASSET_ID"
curl -s "$BASE/assets?keyword=summer&page=1&size=10" -H "Authorization: Bearer $OWNER_TOKEN" | jqget "[(r['title'],r['assetType']) for r in d['data']['records']]"

# ---------- 9. 创建 IMAGE 资产（项目级，引用上传文件） ----------
step "9. 创建 IMAGE 资产（项目级）"
IMG_ASSET_ID=$(curl -s -X POST $BASE/assets -H "Authorization: Bearer $OWNER_TOKEN" -H "Content-Type: application/json" -d "{\"projectId\":\"$PROJECT_ID\",\"derivationType\":\"UPLOAD\",\"assetType\":\"IMAGE\",\"title\":\"ref-image\",\"fileId\":\"$FILE_ID\",\"tags\":[\"reference\"]}" | jqget "d['data']['id']")
echo "imgAssetId=$IMG_ASSET_ID"

# ---------- 10. 提交图片任务（带项目 + 源资产） ----------
step "10. 提交图片任务（projectId + sourceAssetId）"
TASK_RES=$(curl -s -X POST $BASE/tasks/image -H "Authorization: Bearer $OWNER_TOKEN" -H "Content-Type: application/json" -d "{\"prompt\":\"a cup of coffee on a wooden table\",\"modelProvider\":\"local-image\",\"projectId\":\"$PROJECT_ID\",\"sourceAssetId\":\"$IMG_ASSET_ID\"}")
echo "$TASK_RES" | head -c 200; echo
TASK_ID=$(echo "$TASK_RES" | jqget "d['data']['task']['id']")
echo "taskId=$TASK_ID"

# ---------- 11. 轮询任务直到终态 ----------
step "11. 轮询任务状态（最多 120s）"
for i in $(seq 1 24); do
  ST=$(curl -s $BASE/tasks/$TASK_ID/result -H "Authorization: Bearer $OWNER_TOKEN" | jqget "d['data']['status']")
  echo "  [$i] status=$ST"
  case "$ST" in DONE|FAILED|CANCELLED) break;; esac
  sleep 5
done

# ---------- 12. 归档资产 + 成本 ----------
step "12. 任务归档资产 + 成本回写（checklist 2）"
curl -s $BASE/assets/by-task/$TASK_ID -H "Authorization: Bearer $OWNER_TOKEN" | jqget "[(a['id'],a['title'],a['derivationType'],a['status'],a['projectId']) for a in d['data']]"
docker exec mysql mysql -uroot -p123456 agent_platform -e "SELECT id, project_id, source_asset_id, batch_group_id, cost, status FROM agent_task WHERE id='$TASK_ID'\G" 2>/dev/null

# ---------- 13. 提交审核 + 通过 ----------
step "13. 资产提交审核 + 通过"
ASSET_TO_REVIEW=$(curl -s $BASE/assets/by-task/$TASK_ID -H "Authorization: Bearer $OWNER_TOKEN" | jqget "d['data'][0]['id'] if d['data'] else None")
echo "reviewAsset=$ASSET_TO_REVIEW"
if [ "$ASSET_TO_REVIEW" != "None" ] && [ -n "$ASSET_TO_REVIEW" ]; then
  curl -s -X POST $BASE/assets/$ASSET_TO_REVIEW/submit-review -H "Authorization: Bearer $OWNER_TOKEN"; echo
  curl -s -X POST "$BASE/assets/$ASSET_TO_REVIEW/review?approved=true" -H "Authorization: Bearer $OWNER_TOKEN"; echo
  curl -s $BASE/assets/$ASSET_TO_REVIEW -H "Authorization: Bearer $OWNER_TOKEN" | jqget "d['data']['status']"
fi

# ---------- 14. 派生链 + 删除占位 ----------
step "14. 删除父资产后子资产 lineage 显示占位（checklist 4）"
CHILD_ID=$(curl -s -X POST $BASE/assets -H "Authorization: Bearer $OWNER_TOKEN" -H "Content-Type: application/json" -d "{\"parentAssetId\":\"$IMG_ASSET_ID\",\"derivationType\":\"VARIANT\",\"assetType\":\"IMAGE\",\"title\":\"child-variant\",\"fileId\":\"$FILE_ID\"}" | jqget "d['data']['id']")
echo "child=$CHILD_ID"
curl -s -X DELETE $BASE/assets/$IMG_ASSET_ID -H "Authorization: Bearer $OWNER_TOKEN"; echo
curl -s $BASE/assets/$CHILD_ID/lineage -H "Authorization: Bearer $OWNER_TOKEN" | jqget "[(n['title'], n.get('deleted', False)) for n in d['data']['parents']]"

# ---------- 15. 批量任务 batchGroupId ----------
step "15. 批量任务（同 batchGroupId 聚合，checklist 6）"
BGID="phase1-batch-$(date +%s)"
echo "batchGroupId=$BGID"
curl -s -X POST $BASE/tasks/image -H "Authorization: Bearer $OWNER_TOKEN" -H "Content-Type: application/json" -d "{\"prompt\":\"minimal abstract shapes\",\"projectId\":\"$PROJECT_ID\",\"batchGroupId\":\"$BGID\"}" | jqget "d['data']['task']['id']"
curl -s -X POST $BASE/tasks/image -H "Authorization: Bearer $OWNER_TOKEN" -H "Content-Type: application/json" -d "{\"prompt\":\"minimal abstract shapes v2\",\"projectId\":\"$PROJECT_ID\",\"batchGroupId\":\"$BGID\"}" | jqget "d['data']['task']['id']"
echo "（DB 校验同 batch_group_id 的任务数）"
docker exec mysql mysql -uroot -p123456 agent_platform -e "SELECT COUNT(*) AS same_group FROM agent_task WHERE batch_group_id='$BGID'" 2>/dev/null
echo "（时间线含 batchGroupId 事件）"
curl -s $BASE/projects/$PROJECT_ID/timeline -H "Authorization: Bearer $OWNER_TOKEN" | jqget "[(e.get('type'), e.get('batchGroupId')) for e in d['data']['events'] if e.get('batchGroupId')]"

# ---------- 16. 最后一名 OWNER 保护 ----------
step "16. 最后一名 OWNER 无法被移除（checklist 3）"
OWNER_MEMBER_ID=$(curl -s $BASE/projects/$PROJECT_ID/members -H "Authorization: Bearer $OWNER_TOKEN" | jqget "[m['id'] for m in d['data'] if m['userId']=='$OWNER_ID' and m['roleCode']=='OWNER'][0]")
echo "ownerMemberRow=$OWNER_MEMBER_ID"
curl -s -X DELETE $BASE/projects/$PROJECT_ID/members/$OWNER_MEMBER_ID -H "Authorization: Bearer $OWNER_TOKEN"; echo

# ---------- 17. 图生视频（checklist 1 链路一环；引擎慢则只报告状态不阻塞） ----------
step "17. 图生视频任务（sourceAssetId 参考图，最多轮询 90s）"
VIDEO_REF=$(curl -s -X POST $BASE/assets -H "Authorization: Bearer $OWNER_TOKEN" -H "Content-Type: application/json" -d "{\"projectId\":\"$PROJECT_ID\",\"derivationType\":\"UPLOAD\",\"assetType\":\"IMAGE\",\"title\":\"video-ref-image\",\"fileId\":\"$FILE_ID\"}" | jqget "d['data']['id']")
echo "videoRef=$VIDEO_REF"
VIDEO_RES=$(curl -s -X POST $BASE/tasks/video -H "Authorization: Bearer $OWNER_TOKEN" -H "Content-Type: application/json" -d "{\"prompt\":\"a cup of coffee on a wooden table, slow zoom\",\"projectId\":\"$PROJECT_ID\",\"sourceAssetId\":\"$VIDEO_REF\"}")
VIDEO_TASK=$(echo "$VIDEO_RES" | jqget "d['data']['task']['id']")
echo "videoTask=$VIDEO_TASK"
if [ -n "$VIDEO_TASK" ] && [ "$VIDEO_TASK" != "None" ]; then
  for i in $(seq 1 18); do
    ST=$(curl -s $BASE/tasks/$VIDEO_TASK/result -H "Authorization: Bearer $OWNER_TOKEN" | jqget "d['data']['status']")
    echo "  [$i] status=$ST"
    case "$ST" in DONE|FAILED|CANCELLED) break;; esac
    sleep 5
  done
  curl -s $BASE/assets/by-task/$VIDEO_TASK -H "Authorization: Bearer $OWNER_TOKEN" | jqget "[(a['id'],a['assetType'],a['projectId']) for a in d['data']]"
fi

# ---------- 18. 时间线终检 ----------
step "18. 项目时间线终检（checklist 1：整条链可见可追溯）"
curl -s $BASE/projects/$PROJECT_ID/timeline -H "Authorization: Bearer $OWNER_TOKEN" | jqget "[(e.get('type'), e.get('title','')[:40]) for e in d['data']['events']]"

echo; echo "===== 验收脚本完成 ====="
