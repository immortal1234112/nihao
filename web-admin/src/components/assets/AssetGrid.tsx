import { Tag, Tooltip, Typography } from 'antd';
import { FileOutlined, FileTextOutlined, SoundOutlined } from '@ant-design/icons';
import { AssetRow } from '@/types/api';
import { AuthImage, AuthVideo } from '@/components/common/AuthMedia';
import AssetStatusTag from './AssetStatusTag';
import styles from './AssetGrid.module.css';

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
    return <AuthImage src={url} alt={asset.title} preview={false} className={styles.mediaThumb} />;
  }
  if (asset.assetType === 'VIDEO' && url) {
    return (
      <AuthVideo
        src={url}
        muted
        autoPlay
        loop
        playsInline
        preload="auto"
        className={styles.mediaThumb}
      />
    );
  }
  if (asset.assetType === 'TEXT') {
    return (
      <div
        className={styles.textThumb}
      >
        <Paragraph
          className={styles.textPreview}
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
      className={styles.fileThumb}
    >
      {icon}
    </div>
  );
};

/**
 * 资产卡片网格 — 项目工作台资产 Tab 与资产中心共用。
 */
const AssetGrid: React.FC<AssetGridProps> = ({ assets, onOpen, renderActions, emptyText }) => (
  <div className={styles.grid}>
    {assets.map((a) => (
      <div
        key={a.id}
        onClick={() => onOpen(a)}
        className={styles.card}
      >
        <AssetThumb asset={a} />
        <div className={styles.cardBody}>
          <div className={styles.cardTop}>
            <AssetStatusTag status={a.status} />
            <span onClick={(e) => e.stopPropagation()}>{renderActions?.(a)}</span>
          </div>
          <Tooltip title={a.title}>
            <Text strong className={styles.cardTitle} ellipsis>
              {a.title}
            </Text>
          </Tooltip>
          <div className={styles.cardTags}>
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
      <div className={styles.gridEmpty}>
        {emptyText ?? '暂无资产'}
      </div>
    )}
  </div>
);

export default AssetGrid;
