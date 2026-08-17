import { useEffect, useMemo, useState } from 'react';
import { Empty, Flex, Modal, Select, Space, Tag, Typography, message } from 'antd';
import { assetApi } from '@/api/asset';
import { projectApi } from '@/api/project';
import { AssetRow, ProjectRow } from '@/types/api';
import { AuthImage } from '@/components/common/AuthMedia';
import { ASSET_TYPE_LABEL } from '@/components/assets/AssetGrid';
import AssetStatusTag from '@/components/assets/AssetStatusTag';

const { Text } = Typography;

interface ArchiveToProjectModalProps {
  /** 任务 ID；为 null 时弹窗关闭 */
  taskId: string | null;
  /** 外部控制关闭（弹「稍后再说」或完成后） */
  onClose: () => void;
  /** 保存完成后回调（刷新外部列表） */
  onDone?: () => void;
}

/**
 * 归档弹窗 — 任务完成后展示自动归档的资产，支持「保存到项目 + 打标签」。
 * 全局入口生成（无 projectId）的任务完成后弹出；跳过也可稍后在资产中心处理。
 */
const ArchiveToProjectModal: React.FC<ArchiveToProjectModalProps> = ({ taskId, onClose, onDone }) => {
  const [assets, setAssets] = useState<AssetRow[]>([]);
  const [projects, setProjects] = useState<ProjectRow[]>([]);
  const [targetProject, setTargetProject] = useState<string | undefined>();
  const [tags, setTags] = useState<string[]>([]);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!taskId) return;
    setAssets([]);
    setTags([]);
    setTargetProject(undefined);
    // 拉取归档资产；同时预载项目列表（无项目时不弹可选框）
    assetApi
      .listByTask(taskId)
      .then((res) => setAssets(res.data.data))
      .catch(() => {});
    projectApi
      .list({ page: 1, size: 100, scope: 'mine' })
      .then((res) => setProjects(res.data.data.records))
      .catch(() => {});
  }, [taskId]);

  const open = !!taskId;

  const handleSave = async () => {
    if (!targetProject) return;
    setSaving(true);
    try {
      for (const a of assets) {
        await assetApi.update(a.id, { projectId: targetProject, tags: tags.length ? tags : undefined });
      }
      message.success(`已保存 ${assets.length} 个资产到项目`);
      onClose();
      onDone?.();
    } catch {
      /* 已提示 */
    } finally {
      setSaving(false);
    }
  };

  const projectOptions = useMemo(
    () => projects.map((p) => ({ value: p.id, label: p.name })),
    [projects]
  );

  return (
    <Modal
      title="作品已归档 — 保存到项目？"
      open={open}
      onOk={handleSave}
      okText="保存到项目"
      cancelText="稍后再说"
      onCancel={onClose}
      confirmLoading={saving}
      okButtonProps={{ disabled: !targetProject }}
      width={520}
    >
      {assets.length === 0 ? (
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description="任务完成，归档处理中，可稍后在资产中心查看"
          style={{ padding: 16 }}
        />
      ) : (
        <>
          <Flex gap={12} wrap style={{ marginBottom: 16 }}>
            {assets.map((a) => (
              <div key={a.id} style={{ width: 96 }}>
                {a.file?.url && a.assetType === 'IMAGE' ? (
                  <AuthImage src={a.file.url} width={96} height={96} style={{ objectFit: 'cover', borderRadius: 8 }} preview={false} />
                ) : (
                  <div
                    style={{
                      width: 96,
                      height: 96,
                      borderRadius: 8,
                      background: '#fafafa',
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                    }}
                  >
                    <Tag color={ASSET_TYPE_LABEL[a.assetType]?.color ?? 'default'}>
                      {ASSET_TYPE_LABEL[a.assetType]?.text ?? a.assetType}
                    </Tag>
                  </div>
                )}
                <div style={{ marginTop: 4 }}>
                  <AssetStatusTag status={a.status} />
                </div>
              </div>
            ))}
          </Flex>
          <Space direction="vertical" style={{ width: '100%' }}>
            <Select
              style={{ width: '100%' }}
              placeholder="选择保存到的项目"
              value={targetProject}
              onChange={setTargetProject}
              options={projectOptions}
            />
            <Select
              mode="tags"
              style={{ width: '100%' }}
              placeholder="添加标签（可选，回车确认）"
              value={tags}
              onChange={setTags}
              maxTagCount={5}
              notFoundContent={null}
            />
            <Text type="secondary" style={{ fontSize: 12 }}>
              保存后资产归属该项目，项目成员均可查看
            </Text>
          </Space>
        </>
      )}
    </Modal>
  );
};

export default ArchiveToProjectModal;
