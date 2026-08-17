import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Button,
  Descriptions,
  Divider,
  Drawer,
  Flex,
  Modal,
  Select,
  Space,
  Spin,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd';
import {
  DeleteOutlined,
  DownloadOutlined,
  FolderAddOutlined,
  SendOutlined,
  StarFilled,
  StarOutlined,
} from '@ant-design/icons';
import { assetApi } from '@/api/asset';
import { projectApi } from '@/api/project';
import { AssetLineage, AssetRow, ProjectRow } from '@/types/api';
import { useAuthStore } from '@/stores/authStore';
import { AuthImage, AuthVideo, downloadAuthFile } from '@/components/common/AuthMedia';
import AssetStatusTag from './AssetStatusTag';
import { ASSET_TYPE_LABEL, DERIVATION_LABEL } from './AssetGrid';

const { Text, Title } = Typography;

interface AssetDetailDrawerProps {
  /** 当前打开的资产 ID；null 表示关闭 */
  assetId: string | null;
  onClose: () => void;
  /** 资产变化（删除/移动/审核）后刷新外部列表 */
  onChanged?: () => void;
}

/**
 * 资产详情抽屉 — 预览 + 派生链 + 生成参数 + 关联任务 + 操作（收藏/审核/保存到项目/下载/删除）。
 * 项目工作台与资产中心共用。
 */
const AssetDetailDrawer: React.FC<AssetDetailDrawerProps> = ({ assetId, onClose, onChanged }) => {
  const navigate = useNavigate();
  const { user } = useAuthStore();
  const isAdmin = (user?.roles || []).some((r) => r === 'TENANT_ADMIN' || r === 'SUPER_ADMIN');
  const [lineage, setLineage] = useState<AssetLineage | null>(null);
  const [loading, setLoading] = useState(false);
  const [moveOpen, setMoveOpen] = useState(false);
  const [projects, setProjects] = useState<ProjectRow[]>([]);
  const [targetProject, setTargetProject] = useState<string | undefined>();
  const [moving, setMoving] = useState(false);

  useEffect(() => {
    if (!assetId) {
      setLineage(null);
      return;
    }
    setLoading(true);
    assetApi
      .lineage(assetId)
      .then((res) => setLineage(res.data.data))
      .catch(() => setLineage(null))
      .finally(() => setLoading(false));
  }, [assetId]);

  const asset = lineage?.asset;

  const handleStar = async () => {
    if (!asset) return;
    try {
      if (asset.starred) await assetApi.unstar(asset.id);
      else await assetApi.star(asset.id);
      message.success(asset.starred ? '已取消收藏' : '已收藏');
      onChanged?.();
      const res = await assetApi.lineage(asset.id);
      setLineage(res.data.data);
    } catch {
      /* 错误已提示 */
    }
  };

  const handleSubmitReview = async () => {
    if (!asset) return;
    await assetApi.submitReview(asset.id);
    message.success('已提交审核');
    onChanged?.();
    const res = await assetApi.lineage(asset.id);
    setLineage(res.data.data);
  };

  const handleReview = async (approved: boolean) => {
    if (!asset) return;
    await assetApi.review(asset.id, approved);
    message.success(approved ? '已通过' : '已驳回');
    onChanged?.();
    const res = await assetApi.lineage(asset.id);
    setLineage(res.data.data);
  };

  const handleMove = async () => {
    if (!asset || !targetProject) return;
    setMoving(true);
    try {
      await assetApi.update(asset.id, { projectId: targetProject });
      message.success('已保存到项目');
      setMoveOpen(false);
      setTargetProject(undefined);
      onChanged?.();
      onClose();
    } finally {
      setMoving(false);
    }
  };

  const handleDelete = () => {
    if (!asset) return;
    Modal.confirm({
      title: '删除资产',
      content: '删除后可在派生链中保留占位，确定删除？',
      okButtonProps: { danger: true },
      onOk: async () => {
        await assetApi.delete(asset.id);
        message.success('已删除');
        onChanged?.();
        onClose();
      },
    });
  };

  const openMove = async () => {
    setMoveOpen(true);
    if (projects.length === 0) {
      try {
        const res = await projectApi.list({ page: 1, size: 100, scope: 'all' });
        setProjects(res.data.data.records);
      } catch {
        /* 无项目 */
      }
    }
  };

  const canReview =
    asset?.status === 'UNDER_REVIEW' &&
    (isAdmin || !asset.projectId || asset.projectId != null); // 项目级由后端校验角色，前端仅展示入口

  const reviewBtn = asset?.status === 'UNDER_REVIEW' ? (
    <Space>
      <Button size="small" type="primary" onClick={() => handleReview(true)}>通过</Button>
      <Button size="small" danger onClick={() => handleReview(false)}>驳回</Button>
    </Space>
  ) : null;

  return (
    <Drawer
      title="资产详情"
      width={560}
      open={assetId !== null}
      onClose={onClose}
      destroyOnClose={false}
    >
      {loading || !asset ? (
        <div style={{ textAlign: 'center', padding: 80 }}>
          <Spin />
        </div>
      ) : (
        <>
          {/* 预览 */}
          {asset.assetType === 'IMAGE' && asset.file?.url && (
            <AuthImage src={asset.file.url} alt={asset.title} style={{ width: '100%', borderRadius: 8 }} />
          )}
          {asset.assetType === 'VIDEO' && asset.file?.url && (
            <AuthVideo src={asset.file.url} controls style={{ width: '100%', borderRadius: 8 }} />
          )}
          {asset.assetType === 'TEXT' && (
            <div style={{ padding: 12, background: '#f6ffed', borderRadius: 8, whiteSpace: 'pre-wrap' }}>
              <Text>{asset.contentText || asset.title}</Text>
            </div>
          )}

          <Flex justify="space-between" align="center" style={{ marginTop: 12 }}>
            <Title level={5} style={{ margin: 0 }}>{asset.title}</Title>
            <Space>
              {asset.status !== 'APPROVED' && reviewBtn}
              <Tooltip title={asset.starred ? '取消收藏' : '收藏'}>
                <Button
                  size="small"
                  icon={asset.starred ? <StarFilled style={{ color: '#faad14' }} /> : <StarOutlined />}
                  onClick={handleStar}
                />
              </Tooltip>
            </Space>
          </Flex>

          <div style={{ marginTop: 8, marginBottom: 12 }}>
            <AssetStatusTag status={asset.status} />
            <Tag color={ASSET_TYPE_LABEL[asset.assetType]?.color ?? 'default'} style={{ marginLeft: 6 }}>
              {ASSET_TYPE_LABEL[asset.assetType]?.text ?? asset.assetType}
            </Tag>
            {asset.derivationType && (
              <Tag>{DERIVATION_LABEL[asset.derivationType] ?? asset.derivationType}</Tag>
            )}
          </div>

          {/* 操作行 */}
          <Space wrap style={{ marginBottom: 12 }}>
            {(asset.assetType === 'IMAGE' || asset.assetType === 'VIDEO') && (
              <Button
                size="small"
                type="primary"
                ghost
                onClick={() =>
                  navigate(
                    asset.assetType === 'VIDEO'
                      ? `/workspace/video?sourceAssetId=${asset.id}`
                      : `/workspace/image?sourceAssetId=${asset.id}`
                  )
                }
              >
                复用创作
              </Button>
            )}
            {asset.file?.url && (
              <Button
                size="small"
                icon={<DownloadOutlined />}
                onClick={async () => {
                  // 本地文件直链需要 JWT，浏览器新标签页带不了 → 走鉴权 blob 下载
                  const ext = asset.assetType === 'VIDEO' ? 'mp4' : 'png';
                  const ok = await downloadAuthFile(asset.file!.url!, `${asset.title || 'asset'}.${ext}`);
                  if (ok) message.success('下载完成');
                  else message.error('下载失败，请重试');
                }}
              >
                下载
              </Button>
            )}
            <Button size="small" icon={<FolderAddOutlined />} onClick={openMove}>
              保存到项目
            </Button>
            {(asset.status === 'DRAFT' || asset.status === 'REJECTED') && (
              <Button size="small" type="primary" icon={<SendOutlined />} onClick={handleSubmitReview}>
                提交审核
              </Button>
            )}
            <Button size="small" danger icon={<DeleteOutlined />} onClick={handleDelete}>
              删除
            </Button>
          </Space>

          <Divider style={{ margin: '12px 0' }} />

          {/* 派生链 */}
          <Title level={5}>派生链</Title>
          {lineage.parents.length > 0 && (
            <div style={{ marginBottom: 4 }}>
              {[...lineage.parents].reverse().map((p, i) => (
                <div key={p.id} style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
                  <Text type="secondary" style={{ fontSize: 12 }}>{i === 0 ? '↑ 源头' : '↑'}</Text>
                  <Tag>{p.deleted ? '原素材已删除' : p.title}</Tag>
                </div>
              ))}
            </div>
          )}
          <div style={{ marginBottom: 4 }}>
            <Tag color="blue">{asset.title}</Tag>
          </div>
          {lineage.children.map((c) => (
            <div key={c.id} style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
              <Text type="secondary" style={{ fontSize: 12 }}>↓</Text>
              <Tag color="cyan">{c.title}</Tag>
              {c.derivationType && <Text type="secondary" style={{ fontSize: 12 }}>{DERIVATION_LABEL[c.derivationType]}</Text>}
            </div>
          ))}
          {lineage.parents.length === 0 && lineage.children.length === 0 && (
            <Text type="secondary">暂无派生关系</Text>
          )}

          <Divider style={{ margin: '12px 0' }} />

          {/* 详情 */}
          <Descriptions column={1} size="small" labelStyle={{ width: 96 }}>
            {asset.prompt && <Descriptions.Item label="提示词">{asset.prompt}</Descriptions.Item>}
            {asset.modelProvider && (
              <Descriptions.Item label="模型">
                {asset.modelProvider}
                {asset.modelCode ? ` / ${asset.modelCode}` : ''}
              </Descriptions.Item>
            )}
            {asset.description && <Descriptions.Item label="描述">{asset.description}</Descriptions.Item>}
            {asset.tags && asset.tags.length > 0 && (
              <Descriptions.Item label="标签">
                {asset.tags.map((t) => (
                  <Tag key={t}>{t}</Tag>
                ))}
              </Descriptions.Item>
            )}
            {asset.createdAt && (
              <Descriptions.Item label="创建时间">
                {new Date(asset.createdAt).toLocaleString('zh-CN')}
              </Descriptions.Item>
            )}
          </Descriptions>

          {lineage.tasks.length > 0 && (
            <>
              <Divider style={{ margin: '12px 0' }} />
              <Title level={5}>关联任务</Title>
              {lineage.tasks.map((t) => (
                <div key={`${t.taskId}-${t.relationType}`} style={{ marginBottom: 4 }}>
                  <Typography.Link onClick={() => navigate(`/tasks/${t.taskId}`)}>
                    {t.taskId.slice(0, 8)}…
                  </Typography.Link>
                  <Tag style={{ marginLeft: 8 }}>{t.relationType === 'INPUT' ? '输入' : '产出'}</Tag>
                  <Text type="secondary" style={{ fontSize: 12 }}>{t.workflowType} / {t.status}</Text>
                </div>
              ))}
            </>
          )}
        </>
      )}

      <Modal
        title="保存到项目"
        open={moveOpen}
        onOk={handleMove}
        confirmLoading={moving}
        onCancel={() => setMoveOpen(false)}
        okButtonProps={{ disabled: !targetProject }}
      >
        <Select
          style={{ width: '100%', marginTop: 8 }}
          placeholder="选择目标项目"
          value={targetProject}
          onChange={setTargetProject}
          options={projects.map((p) => ({ label: p.name, value: p.id }))}
        />
      </Modal>
    </Drawer>
  );
};

export default AssetDetailDrawer;
