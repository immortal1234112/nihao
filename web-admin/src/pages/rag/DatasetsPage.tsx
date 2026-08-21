import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Card, Table, Button, Modal, Form, Input, Select, Typography,
  Popconfirm, Space, Tag, Tooltip,
} from 'antd';
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { ragApi } from '@/api/rag';
import { RagDataset, RagEmbeddingProvider } from '@/types/api';
import { usePagination } from '@/hooks/usePagination';
import { useAppFeedback } from '@/utils/feedback';
import styles from './DatasetsPage.module.css';

const { Title } = Typography;

const DatasetsPage: React.FC = () => {
  const [createOpen, setCreateOpen] = useState(false);
  const [creating, setCreating] = useState(false);
  const [providers, setProviders] = useState<RagEmbeddingProvider[]>([]);
  const [form] = Form.useForm();
  const navigate = useNavigate();
  const { message } = useAppFeedback();

  // 原逻辑一次拉取最多 100 条且表格不分页，故固定 size=100、Table 传 pagination={false} 保持视觉不变
  const table = usePagination<RagDataset>(
    async (page, size) => {
      const res = await ragApi.listDatasets({ page, size });
      return res.data.data;
    },
    [],
    { size: 100 }
  );

  const load = table.refresh;

  useEffect(() => {
    ragApi.listEmbeddingProviders()
      .then((res) => setProviders(res.data.data))
      .catch(() => {});
  }, []);

  const handleCreate = async (values: { name: string; description?: string; embeddingProvider?: string }) => {
    setCreating(true);
    try {
      await ragApi.createDataset(values);
      message.success('知识库创建成功');
      setCreateOpen(false);
      form.resetFields();
      load();
    } catch {
      /* 错误信息已由拦截器提示 */
    } finally {
      setCreating(false);
    }
  };

  const handleDelete = async (id: string) => {
    try {
      await ragApi.deleteDataset(id);
      message.success('知识库已删除');
      load();
    } catch { /* 拦截器已提示 */ }
  };

  const columns = [
    {
      title: '名称',
      dataIndex: 'name',
      render: (name: string, record: RagDataset) => (
        <Button type="link" style={{ padding: 0 }} onClick={() => navigate(`/rag/datasets/${record.id}`)}>
          {name}
        </Button>
      ),
    },
    { title: '描述', dataIndex: 'description', ellipsis: true },
    {
      title: 'Embedding 模型',
      render: (_: unknown, record: RagDataset) => (
        <Tooltip title={`提供商: ${record.embeddingProvider} · 维度: ${record.dimension}（创建后不可换）`}>
          <span>{record.embeddingModel}</span>
        </Tooltip>
      ),
    },
    { title: '文档数', dataIndex: 'docCount', width: 90 },
    { title: '分块数', dataIndex: 'chunkCount', width: 90 },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (status: string) => (
        <Tag color={status === 'ACTIVE' ? 'green' : 'default'}>{status === 'ACTIVE' ? '启用' : '停用'}</Tag>
      ),
    },
    {
      title: '操作',
      width: 120,
      render: (_: unknown, record: RagDataset) => (
        <Popconfirm title="删除知识库及其全部文档？" onConfirm={() => handleDelete(record.id)}>
          <Button type="link" danger size="small">删除</Button>
        </Popconfirm>
      ),
    },
  ];

  return (
    <div className={styles.page}>
      <div className={styles.pageHeader}>
        <div>
          <Title level={3} className={styles.pageTitle}>知识库管理</Title>
          <Typography.Text type="secondary" className={styles.pageHint}>管理文档集合，并用于检索增强生成</Typography.Text>
        </div>
        <Space className={styles.headerActions}>
          <Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>新建知识库</Button>
        </Space>
      </div>
      <div className={styles.tableShell}>
        <div className={styles.sectionLabel}>我的知识库</div>
        <Table
          className={styles.datasetTable}
          rowKey="id"
          columns={columns}
          dataSource={table.records}
          loading={table.loading}
          pagination={false}
        />
      </div>

      <Modal
        title="新建知识库"
        open={createOpen}
        onCancel={() => setCreateOpen(false)}
        onOk={() => form.submit()}
        confirmLoading={creating}
      >
        <Form form={form} layout="vertical" onFinish={handleCreate} initialValues={{ embeddingProvider: 'qwen-embedding' }}>
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="如：产品手册 / 品牌规范" maxLength={128} />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={2} placeholder="知识库用途说明（可选）" maxLength={512} />
          </Form.Item>
          <Form.Item
            name="embeddingProvider"
            label="Embedding 模型"
            extra="创建后不可更换，换模型需重建知识库"
            rules={[{ required: true, message: '请选择 embedding 模型' }]}
          >
            <Select
              placeholder="选择 embedding 模型"
              options={providers.map((p) => ({
                value: p.provider,
                label: `${p.provider} — ${p.model}（${p.dimension} 维）${p.available ? '' : '（未配置密钥）'}`,
                disabled: !p.available,
              }))}
            />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default DatasetsPage;
