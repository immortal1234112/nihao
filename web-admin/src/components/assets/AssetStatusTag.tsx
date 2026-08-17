import { Tag } from 'antd';
import type { AssetStatus } from '@/types/api';

const STATUS_META: Record<AssetStatus, { text: string; color: string }> = {
  DRAFT: { text: '草稿', color: 'default' },
  UNDER_REVIEW: { text: '审核中', color: 'processing' },
  APPROVED: { text: '已通过', color: 'success' },
  REJECTED: { text: '已驳回', color: 'error' },
};

/** 资产审核状态标签（4 态：草稿/审核中/已通过/已驳回）。 */
const AssetStatusTag: React.FC<{ status: AssetStatus | string }> = ({ status }) => {
  const meta = STATUS_META[status as AssetStatus] ?? { text: status, color: 'default' };
  return <Tag color={meta.color}>{meta.text}</Tag>;
};

export default AssetStatusTag;
