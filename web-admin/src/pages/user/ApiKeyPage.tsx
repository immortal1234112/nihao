import { useState } from 'react';
import {
  Table,
  Button,
  Modal,
  Form,
  Input,
  InputNumber,
  DatePicker,
  Tag,
  Space,
  Popconfirm,
  Typography,
  Alert,
} from 'antd';
import { PlusOutlined, DeleteOutlined, CopyOutlined } from '@ant-design/icons';
import { userApi } from '@/api/user';
import { ApiKey } from '@/types/api';
import { useRequest } from '@/hooks/useRequest';
import { useAppFeedback } from '@/utils/feedback';
import dayjs from 'dayjs';
import styles from './ApiKeyPage.module.css';

const { Title, Text } = Typography;

const ApiKeyPage: React.FC = () => {
  const [modalOpen, setModalOpen] = useState(false);
  const [newKey, setNewKey] = useState<{ apiKey: string; hint: string } | null>(null);
  const [form] = Form.useForm();
  const { message } = useAppFeedback();

  const { data: keys, loading, refresh: loadKeys } = useRequest<ApiKey[]>(
    async () => {
      const res = await userApi.listApiKeys();
      return res.data.data;
    },
    []
  );

  const handleCreate = async (values: {
    name: string;
    rateLimitQpm?: number;
    expiresAt?: dayjs.Dayjs;
  }) => {
    try {
      const res = await userApi.createApiKey({
        name: values.name,
        rateLimitQpm: values.rateLimitQpm,
        expiresAt: values.expiresAt?.toISOString(),
      });
      setNewKey(res.data.data);
      form.resetFields();
      setModalOpen(false);
      loadKeys();
    } catch {
      /* handled by axios interceptor */
    }
  };

  const handleRevoke = async (keyId: string) => {
    try {
      await userApi.revokeApiKey(keyId);
      message.success('API Key 已撤销');
      loadKeys();
    } catch {
      /* handled by axios interceptor */
    }
  };

  const columns = [
    { title: '名称', dataIndex: 'name', key: 'name' },
    {
      title: '前缀',
      dataIndex: 'keyPrefix',
      key: 'prefix',
      render: (v: string) => <code>{v}...</code>,
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 80,
      render: (v: string) => (
        <Tag color={v === 'ACTIVE' ? 'success' : 'error'}>{v}</Tag>
      ),
    },
    {
      title: 'QPM限制',
      dataIndex: 'rateLimitQpm',
      key: 'qpm',
      width: 80,
    },
    {
      title: '最后使用',
      dataIndex: 'lastUsedAt',
      key: 'lastUsed',
      width: 170,
      render: (v: string) => (v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '-'),
    },
    {
      title: '过期时间',
      dataIndex: 'expiresAt',
      key: 'expires',
      width: 170,
      render: (v: string) => (v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '永久'),
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      key: 'created',
      width: 170,
      render: (v: string) => dayjs(v).format('YYYY-MM-DD HH:mm'),
    },
    {
      title: '操作',
      key: 'actions',
      width: 80,
      render: (_: unknown, record: ApiKey) =>
        record.status === 'ACTIVE' && (
          <Popconfirm
            title="撤销后该 Key 将无法使用，确定？"
            onConfirm={() => handleRevoke(record.id)}
          >
            <Button type="link" danger size="small" icon={<DeleteOutlined />}>
              撤销
            </Button>
          </Popconfirm>
        ),
    },
  ];

  return (
    <div className={styles.page}>
      <div className={styles.pageHeader}>
        <div>
          <Title level={3} className={styles.pageTitle}>
        API Key 管理
          </Title>
          <Typography.Text type="secondary" className={styles.pageHint}>管理用于外部服务调用的访问密钥</Typography.Text>
        </div>
        <Button className={styles.primaryButton} type="primary" icon={<PlusOutlined />} onClick={() => setModalOpen(true)}>
          创建 API Key
        </Button>
      </div>

      {newKey && (
        <Alert
          className={styles.keyAlert}
          type="warning"
          showIcon
          closable
          onClose={() => setNewKey(null)}
          message="新 API Key 已生成，请立即复制保存，关闭后将无法再查看！"
          description={
            <Space direction="vertical">
              <code style={{ fontSize: 16, userSelect: 'all' }}>{newKey.apiKey}</code>
              <Button
                size="small"
                icon={<CopyOutlined />}
                onClick={() => {
                  navigator.clipboard.writeText(newKey.apiKey);
                  message.success('已复制');
                }}
              >
                复制
              </Button>
              <Text type="secondary">{newKey.hint}</Text>
            </Space>
          }
        />
      )}

      <div className={styles.tableShell}>
        <Table className={styles.keyTable} dataSource={keys ?? []} columns={columns} rowKey="id" loading={loading} />
      </div>

      <Modal
        title="创建 API Key"
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        footer={null}
      >
        <Form form={form} layout="vertical" onFinish={handleCreate}>
          <Form.Item
            name="name"
            label="名称"
            rules={[{ required: true, message: '请输入名称' }]}
          >
            <Input placeholder="例如：生产环境、测试项目" />
          </Form.Item>
          <Form.Item name="rateLimitQpm" label="每分钟限制" initialValue={60}>
            <InputNumber min={1} max={1000} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item
            name="expiresAt"
            label="过期时间（可选，不设则永久有效）"
          >
            <DatePicker showTime style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" block>
              创建
            </Button>
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default ApiKeyPage;
