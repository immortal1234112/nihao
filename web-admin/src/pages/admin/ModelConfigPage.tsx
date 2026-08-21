import { useEffect, useState } from 'react';
import { Table, Button, Modal, Form, Input, Switch, Tag, Space, Typography } from 'antd';
import { SettingOutlined, ReloadOutlined, CheckCircleOutlined, CloseCircleOutlined } from '@ant-design/icons';
import http from '@/api/client';
import { ApiResult } from '@/types/api';
import { useAppFeedback } from '@/utils/feedback';
import styles from './ModelConfigPage.module.css';

const { Title, Text } = Typography;

interface ModelConfigItem {
  id: string;
  provider: string;
  apiKey: string;
  apiSecret: string;
  baseUrl: string;
  model: string;
  enabled: boolean;
  hasApiKey: boolean;
  hasSecret: boolean;
}

const providerLabels: Record<string, { name: string; category: string; defaultModel: string; defaultBaseUrl: string; needsSecret: boolean }> = {
  openai:    { name: 'OpenAI',          category: 'LLM', defaultModel: 'gpt-4o',                 defaultBaseUrl: 'https://api.openai.com',                         needsSecret: false },
  anthropic: { name: 'Anthropic Claude', category: 'LLM', defaultModel: 'claude-sonnet-4-20250514', defaultBaseUrl: 'https://api.anthropic.com',                      needsSecret: false },
  deepseek:  { name: 'DeepSeek',        category: 'LLM', defaultModel: 'deepseek-chat',            defaultBaseUrl: 'https://api.deepseek.com',                       needsSecret: false },
  qwen:      { name: '通义千问',          category: 'LLM', defaultModel: 'qwen-turbo',               defaultBaseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', needsSecret: false },
  baidu:     { name: '文心一言',          category: 'LLM', defaultModel: 'ernie-4.0-8k',             defaultBaseUrl: '',                                                needsSecret: true },
  spark:     { name: '讯飞星火',          category: 'LLM', defaultModel: '4.0Ultra',                 defaultBaseUrl: 'https://spark-api-open.xf-yun.com/v1',           needsSecret: true },
  zhipu:     { name: '智谱 GLM',         category: 'LLM', defaultModel: 'glm-4-plus',               defaultBaseUrl: 'https://open.bigmodel.cn/api/paas/v4',           needsSecret: false },
  moonshot:  { name: '月之暗面 Kimi',     category: 'LLM', defaultModel: 'moonshot-v1-8k',           defaultBaseUrl: 'https://api.moonshot.cn',                        needsSecret: false },
  google:    { name: 'Google Gemini',    category: 'LLM', defaultModel: 'gemini-2.0-flash',         defaultBaseUrl: 'https://generativelanguage.googleapis.com',       needsSecret: false },
  agnes:     { name: 'Agnes 2.5 Flash',   category: 'LLM', defaultModel: 'agnes-2.5-flash',           defaultBaseUrl: 'https://apihub.agnes-ai.com',                     needsSecret: false },
  // 图片生成
  'local-image':  { name: '本地 SD (免费)',    category: '图片', defaultModel: 'sdxl-turbo',             defaultBaseUrl: 'http://localhost:7860',                          needsSecret: false },
  'qwen-image':  { name: '通义万相',       category: '图片', defaultModel: 'wanx-v1',                 defaultBaseUrl: 'https://dashscope.aliyuncs.com',                needsSecret: false },
  dalle:         { name: 'DALL·E 3',       category: '图片', defaultModel: 'dall-e-3',                defaultBaseUrl: 'https://api.openai.com',                         needsSecret: false },
  stability:     { name: 'Stable Diffusion', category: '图片', defaultModel: 'stable-diffusion-xl',     defaultBaseUrl: 'https://api.stability.ai',                       needsSecret: false },
  midjourney:    { name: 'Midjourney',     category: '图片', defaultModel: 'midjourney-v6',            defaultBaseUrl: 'https://api.midjourney.com',                     needsSecret: false },
  'ernie-image': { name: '文心一格',       category: '图片', defaultModel: 'ernie-vilg-v2',            defaultBaseUrl: '',                                                needsSecret: true },
  'agnes-image': { name: 'Agnes Image 2.1', category: '图片', defaultModel: 'agnes-image-2.1-flash',   defaultBaseUrl: 'https://apihub.agnes-ai.com',                     needsSecret: false },
  // 视频生成
  'local-video':  { name: '本地视频 (免费)',  category: '视频', defaultModel: 'cogvideox-2b',            defaultBaseUrl: 'http://localhost:7860',                          needsSecret: false },
  runway:        { name: 'Runway Gen-3',   category: '视频', defaultModel: 'gen3-alpha',               defaultBaseUrl: 'https://api.runwayml.com',                       needsSecret: false },
  kling:         { name: '快手可灵',       category: '视频', defaultModel: 'kling-v1',                 defaultBaseUrl: 'https://api.klingai.com',                        needsSecret: true },
  pika:          { name: 'Pika 2.0',       category: '视频', defaultModel: 'pika-2.0',                 defaultBaseUrl: 'https://api.pika.art',                           needsSecret: false },
  luma:          { name: 'Luma Dream Machine', category: '视频', defaultModel: 'dream-machine-v1',     defaultBaseUrl: 'https://api.lumalabs.ai',                        needsSecret: false },
  'agnes-video': { name: 'Agnes Video V2.0',  category: '视频', defaultModel: 'agnes-video-v2.0',       defaultBaseUrl: 'https://apihub.agnes-ai.com',                     needsSecret: false },
  // 向量（RAG 知识库 embedding）
  'qwen-embedding':   { name: '通义 Embedding',   category: '向量', defaultModel: 'text-embedding-v3',      defaultBaseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', needsSecret: false },
  'openai-embedding': { name: 'OpenAI Embedding', category: '向量', defaultModel: 'text-embedding-3-small', defaultBaseUrl: 'https://api.openai.com',                         needsSecret: false },
  'siliconflow-embedding': { name: '硅基流动 BGE-M3', category: '向量', defaultModel: 'BAAI/bge-m3',      defaultBaseUrl: 'https://api.siliconflow.cn/v1',                  needsSecret: false },
};

const ModelConfigPage: React.FC = () => {
  const [configs, setConfigs] = useState<ModelConfigItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [editModal, setEditModal] = useState(false);
  const [currentProvider, setCurrentProvider] = useState<string>('');
  const [form] = Form.useForm();
  const { message } = useAppFeedback();

  const loadConfigs = async () => {
    setLoading(true);
    try {
      const res = await http.get<ApiResult<ModelConfigItem[]>>('/admin/model-configs');
      setConfigs(res.data.data);
    } catch { /* handled */ }
    finally { setLoading(false); }
  };

  useEffect(() => { loadConfigs(); }, []);

  const handleEdit = (provider: string) => {
    setCurrentProvider(provider);
    const config = configs.find(c => c.provider === provider);
    const label = providerLabels[provider];
    form.setFieldsValue({
      apiKey: '',
      apiSecret: '',
      baseUrl: label?.defaultBaseUrl || '',
      model: label?.defaultModel || '',
      enabled: config?.enabled || false,
    });
    setEditModal(true);
  };

  const handleSave = async (values: { apiKey: string; apiSecret: string; baseUrl: string; model: string; enabled: boolean }) => {
    try {
      await http.put(`/admin/model-configs/${currentProvider}`, {
        apiKey: values.apiKey || undefined,
        apiSecret: values.apiSecret || undefined,
        baseUrl: values.baseUrl || undefined,
        model: values.model || undefined,
        enabled: values.enabled ? 1 : 0,
      });
      message.success(`${providerLabels[currentProvider]?.name} 配置已保存`);
      setEditModal(false);
      loadConfigs();
    } catch { /* handled */ }
  };

  const handleRefresh = async () => {
    try {
      await http.post('/admin/model-configs/refresh');
      message.success('配置缓存已刷新');
      loadConfigs();
    } catch { /* handled */ }
  };

  const columns = [
    {
      title: '模型',
      dataIndex: 'provider',
      key: 'provider',
      width: 180,
      render: (p: string) => {
        const label = providerLabels[p];
        return (
          <Space>
            <span style={{ fontWeight: 500 }}>{label?.name || p}</span>
            <Tag>{label?.category || 'LLM'}</Tag>
          </Space>
        );
      },
    },
    {
      title: 'API Key',
      dataIndex: 'apiKey',
      key: 'apiKey',
      width: 200,
      render: (v: string, record: ModelConfigItem) => (
        <Space>
          {record.hasApiKey ? (
            <Tag color="success" icon={<CheckCircleOutlined />}>已配置</Tag>
          ) : (
            <Tag color="error" icon={<CloseCircleOutlined />}>未配置</Tag>
          )}
          {v && <Text code>{v}</Text>}
        </Space>
      ),
    },
    {
      title: 'Secret',
      dataIndex: 'apiSecret',
      key: 'apiSecret',
      width: 100,
      render: (_v: string, record: ModelConfigItem) => (
        record.hasSecret ? <Tag color="success">已配置</Tag> : <Tag>不需要</Tag>
      ),
    },
    {
      title: '模型名',
      dataIndex: 'model',
      key: 'model',
      width: 170,
      render: (v: string) => v ? <Text code>{v}</Text> : <Text type="secondary">使用默认</Text>,
    },
    {
      title: '状态',
      dataIndex: 'enabled',
      key: 'enabled',
      width: 80,
      render: (v: boolean, record: ModelConfigItem) => (
        v && record.hasApiKey
          ? <Tag color="success">可用</Tag>
          : <Tag color="default">未启用</Tag>
      ),
    },
    {
      title: '操作',
      key: 'actions',
      width: 80,
      render: (_: unknown, record: ModelConfigItem) => (
        <Button type="link" icon={<SettingOutlined />} onClick={() => handleEdit(record.provider)}>
          配置
        </Button>
      ),
    },
  ];

  return (
    <div className={styles.page}>
      <div className={styles.pageHeader}>
        <div>
          <Title level={3} className={styles.pageTitle}>模型配置</Title>
          <Text type="secondary" className={styles.pageHint}>配置各模型的 API Key，配置后立即生效无需重启</Text>
        </div>
        <Space className={styles.headerActions}>
          <Button icon={<ReloadOutlined />} onClick={loadConfigs}>刷新</Button>
          <Button icon={<ReloadOutlined />} onClick={handleRefresh}>刷新缓存</Button>
        </Space>
      </div>

      <div className={styles.tableShell}>
        <Table className={styles.configTable} dataSource={configs} columns={columns} rowKey="provider" loading={loading} pagination={false} />
      </div>

      <Modal
        title={`配置 ${providerLabels[currentProvider]?.name || currentProvider}`}
        open={editModal}
        onCancel={() => setEditModal(false)}
        onOk={() => form.submit()}
        okText="保存"
        width={520}
      >
        <Form form={form} layout="vertical" onFinish={handleSave}>
          <Form.Item name="enabled" label="启用状态" valuePropName="checked">
            <Switch checkedChildren="启用" unCheckedChildren="禁用" />
          </Form.Item>
          <Form.Item name="apiKey" label="API Key"
            extra={configs.find(c => c.provider === currentProvider)?.hasApiKey ? '已配置过 Key，留空则不修改，输入新 Key 将覆盖' : '输入 API Key'}
          >
            <Input.Password placeholder="sk-..." />
          </Form.Item>
          {providerLabels[currentProvider]?.needsSecret && (
            <Form.Item name="apiSecret" label="Secret Key"
              extra={configs.find(c => c.provider === currentProvider)?.hasSecret ? '已配置过 Secret，留空则不修改' : '输入 Secret Key'}
            >
              <Input.Password placeholder="secret..." />
            </Form.Item>
          )}
          <Form.Item name="model" label="模型名称" extra="留空使用默认模型">
            <Input placeholder={providerLabels[currentProvider]?.defaultModel} />
          </Form.Item>
          <Form.Item name="baseUrl" label="API 地址" extra="留空使用默认地址">
            <Input placeholder={providerLabels[currentProvider]?.defaultBaseUrl || '使用默认'} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default ModelConfigPage;
