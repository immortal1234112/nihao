import { useEffect, useMemo, useState } from 'react';
import { Alert, Button, Flex, Form, Input, InputNumber, Select, Segmented, Space, Typography, message } from 'antd';
import { ThunderboltOutlined } from '@ant-design/icons';
import { assetApi, modelCapabilityApi } from '@/api/asset';
import { taskApi } from '@/api/task';
import { AssetRow, ModelCapability } from '@/types/api';

const { Text } = Typography;

const TIER_LABEL: Record<string, string> = {
  FAST: '快速',
  STANDARD: '标准',
  HIGH_QUALITY: '高质量',
};

interface ProjectCreatePanelProps {
  /** 项目归属（从工作台进入时自动带） */
  projectId?: string;
  /** 预设源资产（复用创作时带） */
  presetSourceAssetId?: string;
  /** 简报上下文提示（自动带入简报目标/风格） */
  briefHint?: string;
  /** 提交成功后回调（刷新时间线/资产） */
  onSubmitted?: () => void;
}

/**
 * 项目创作面板 — 引擎 + 提示词 + 参考资产 + 质量档位（映射 model_capability）。
 * 提交时自动带 projectId / sourceAssetId / batchGroupId（数量>1 时按批次归档）。
 */
const ProjectCreatePanel: React.FC<ProjectCreatePanelProps> = ({
  projectId,
  presetSourceAssetId,
  briefHint,
  onSubmitted,
}) => {
  const [engine, setEngine] = useState<'IMAGE' | 'VIDEO'>('IMAGE');
  const [capabilities, setCapabilities] = useState<Record<string, ModelCapability[]>>({});
  const [refAssets, setRefAssets] = useState<AssetRow[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm();

  useEffect(() => {
    modelCapabilityApi
      .list()
      .then((res) => setCapabilities(res.data.data))
      .catch(() => {});
    if (projectId) {
      assetApi
        .list({ projectId, assetType: 'IMAGE', size: 100 })
        .then((res) => setRefAssets(res.data.data.records))
        .catch(() => {});
    }
  }, [projectId]);

  const qualityOptions = useMemo(() => {
    const rows = engine === 'IMAGE' ? capabilities.TEXT2IMAGE : capabilities.TEXT2VIDEO;
    return (rows || []).map((mc) => ({
      value: mc.provider,
      label: `${mc.label || mc.modelCode} · ${TIER_LABEL[mc.tier] ?? mc.tier}`,
    }));
  }, [capabilities, engine]);

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      setSubmitting(true);
      const base = {
        prompt: values.prompt,
        modelProvider: values.quality,
        count: values.count || 1,
        projectId,
        sourceAssetId: values.sourceAssetId || undefined,
      };
      // 数量>1 视为同批次：生成 batchGroupId，任务完成后资产自动归档并按批次聚合
      const payload = {
        ...base,
        batchGroupId: (values.count || 1) > 1 ? crypto.randomUUID() : undefined,
      };
      if (engine === 'IMAGE') {
        await taskApi.submitImage(payload);
      } else {
        await taskApi.submitVideo(payload);
      }
      message.success('任务已提交，完成后自动归档到项目');
      form.resetFields(['prompt', 'count']);
      onSubmitted?.();
    } catch {
      // 校验/请求错误已提示
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div style={{ maxWidth: 720 }}>
      {briefHint && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message="创作简报"
          description={<Text style={{ fontSize: 12 }}>{briefHint}</Text>}
        />
      )}
      <Form form={form} layout="vertical">
        <Form.Item label="生成类型">
          <Segmented
            value={engine}
            onChange={(v) => setEngine(v as 'IMAGE' | 'VIDEO')}
            options={[
              { label: '图片', value: 'IMAGE' },
              { label: '视频', value: 'VIDEO' },
            ]}
          />
        </Form.Item>
        <Form.Item
          name="prompt"
          label="提示词"
          rules={[{ required: true, message: '请输入提示词' }]}
        >
          <Input.TextArea rows={4} placeholder="描述你想生成的内容…" maxLength={2000} showCount />
        </Form.Item>
        <Flex gap={16} wrap>
          <Form.Item name="sourceAssetId" label="参考图（可选，图生图/图生视频）" style={{ flex: 1, minWidth: 240 }}>
            <Select
              allowClear
              placeholder={projectId ? '选择项目中的图片资产' : '从资产中心复用后自动带入'}
              options={refAssets.map((a) => ({ value: a.id, label: a.title }))}
            />
          </Form.Item>
          <Form.Item name="quality" label="质量档位" style={{ minWidth: 220 }}>
            <Select placeholder="默认引擎自带档位" options={qualityOptions} allowClear />
          </Form.Item>
          <Form.Item name="count" label="数量" initialValue={1}>
            <InputNumber min={1} max={4} />
          </Form.Item>
        </Flex>
        <Space>
          <Button type="primary" icon={<ThunderboltOutlined />} loading={submitting} onClick={handleSubmit}>
            生成
          </Button>
          <Text type="secondary" style={{ fontSize: 12 }}>
            {projectId ? '结果将自动归档到本项目' : '生成后可在资产中心找到结果'}
          </Text>
        </Space>
      </Form>
    </div>
  );
};

export default ProjectCreatePanel;
