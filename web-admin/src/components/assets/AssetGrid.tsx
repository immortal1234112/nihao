import { Tag, Tooltip, Typography } from 'antd';
import { FileOutlined, FileTextOutlined, SoundOutlined } from '@ant-design/icons';
import { AssetRow } from '@/types/api';
import { AuthImage, AuthVideo } from '@/components/common/AuthMedia';
import AssetStatusTag from './AssetStatusTag';

const { Paragraph, Text } = Typography;

/** 资产类型 → 中文标签 + 颜色 */
export const ASSET_TYPE_LABEL: Record<string, { text: string; color: string }> = {
  IMAGE: { text: '图片', color: 'blue' },
  VIDEO: { text: '视频', color: 'purple' },
  AUDIO: { text: '音频', color: 'orange' },
  TEXT: { text: '文案', color: 'green' },
  FILE: { text: '文件', color: 'default' },
};

/** 派生类型 → 中文标签 */
export const DERIVATION_LABEL: Record<string, string> = {
  UPLOAD: '上传',
  IMPORT: '导入',
  GENERATED: '生成',
  VARIANT: '变体',
  REGEN: '重生成',
  EDIT: '编辑',
  CONVERT: '转换',
  EXPORT: '导出',
};

interface AssetGridProps {
  assets: AssetRow[];
  /** 点击卡片（如打开详情抽屉） */
  onOpen: (asset: AssetRow) => void;
  /** 卡片右上角操作（收藏/删除等），按资产渲染 */
  renderActions?: (asset: AssetRow) => React.ReactNode;
  emptyText?: string;
}

/** 资产缩略区：图片/视频用 file.url 预览，文案显示文本摘要，其余显示图标占位。 */
const AssetThumb: React.FC<{ asset: AssetRow }> = ({ asset }) => {
  const url = asset.file?.url;
  if (asset.assetType === 'IMAGE' && url) {
    return <AuthImage src={url} alt={asset.title} preview={false} style={{ width: '100%', height: 160, objectFit: 'cover' }} />;
  }
  if (asset.assetType === 'VIDEO' && url) {
    return <AuthVideo src={url} muted preload="metadata" style={{ width: '100%', height: 160, objectFit: 'cover' }} />;
  }
  if (asset.assetType === 'TEXT') {
    return (
      <div
        style={{
          height: 160,
          padding: 12,
          background: '#f6ffed',
          borderBottom: '1px solid #e8f5e9',
          overflow: 'hidden',
        }}
      >
        <Paragraph
          style={{ fontSize: 12, whiteSpace: 'pre-wrap', marginBottom: 0 }}
          ellipsis={{ rows: 7, tooltip: asset.contentText || asset.title }}
        >
          {asset.contentText || asset.title}
        </Paragraph>
      </div>
    );
  }
  const icon = asset.assetType === 'AUDIO' ? <SoundOutlined /> : asset.assetType === 'FILE' ? <FileOutlined /> : <FileTextOutlined />;
  return (
    <div
      style={{
        height: 160,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: '#fafafa',
        borderBottom: '1px solid #f0f0f0',
        fontSize: 40,
        color: '#bbb',
      }}
    >
      {icon}
    </div>
  );
};

/**
 * 资产卡片网格 — 项目工作台资产 Tab 与资产中心共用。
 */
const AssetGrid: React.FC<AssetGridProps> = ({ assets, onOpen, renderActions, emptyText }) => (
  <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))', gap: 16 }}>
    {assets.map((a) => (
      <div
        key={a.id}
        onClick={() => onOpen(a)}
        style={{
          borderRadius: 12,
          overflow: 'hidden',
          background: '#fff',
          boxShadow: '0 2px 8px rgba(0,0,0,0.06)',
          cursor: 'pointer',
          transition: 'transform .2s, box-shadow .2s',
        }}
        onMouseEnter={(e) => {
          e.currentTarget.style.transform = 'translateY(-2px)';
          e.currentTarget.style.boxShadow = '0 6px 20px rgba(0,0,0,0.12)';
        }}
        onMouseLeave={(e) => {
          e.currentTarget.style.transform = 'none';
          e.currentTarget.style.boxShadow = '0 2px 8px rgba(0,0,0,0.06)';
        }}
      >
        <AssetThumb asset={a} />
        <div style={{ padding: '10px 12px' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 6 }}>
            <AssetStatusTag status={a.status} />
            <span onClick={(e) => e.stopPropagation()}>{renderActions?.(a)}</span>
          </div>
          <Tooltip title={a.title}>
            <Text strong style={{ fontSize: 13, display: 'block' }} ellipsis>
              {a.title}
            </Text>
          </Tooltip>
          <div style={{ marginTop: 4 }}>
            <Tag color={ASSET_TYPE_LABEL[a.assetType]?.color ?? 'default'}>
              {ASSET_TYPE_LABEL[a.assetType]?.text ?? a.assetType}
            </Tag>
            {a.derivationType && (
              <Tag style={{ marginInlineEnd: 0 }}>{DERIVATION_LABEL[a.derivationType] ?? a.derivationType}</Tag>
            )}
          </div>
        </div>
      </div>
    ))}
    {assets.length === 0 && (
      <div style={{ gridColumn: '1 / -1', textAlign: 'center', color: '#999', padding: 40 }}>
        {emptyText ?? '暂无资产'}
      </div>
    )}
  </div>
);

export default AssetGrid;
