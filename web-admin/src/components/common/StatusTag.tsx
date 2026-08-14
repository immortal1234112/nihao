import { Tag } from 'antd';

/** 任务/步骤状态 → antd Tag 颜色 */
export const statusColorMap: Record<string, string> = {
  CREATED: 'default',
  VALIDATING: 'processing',
  PENDING: 'warning',
  PROCESSING: 'processing',
  DONE: 'success',
  FAILED: 'error',
  CANCELLED: 'default',
  REVIEWING: 'warning',
  SKIPPED: 'warning',
};

/** 任务/步骤状态 → 中文文案 */
export const statusLabelMap: Record<string, string> = {
  CREATED: '已创建',
  VALIDATING: '校验中',
  PENDING: '等待中',
  PROCESSING: '处理中',
  DONE: '已完成',
  FAILED: '失败',
  CANCELLED: '已取消',
  REVIEWING: '审核中',
  SKIPPED: '已跳过',
};

interface StatusTagProps {
  status: string;
}

/** 任务/步骤状态标签（颜色 + 中文文案统一映射，多页复用） */
const StatusTag: React.FC<StatusTagProps> = ({ status }) => (
  <Tag color={statusColorMap[status] || 'default'}>{statusLabelMap[status] || status}</Tag>
);

export default StatusTag;
