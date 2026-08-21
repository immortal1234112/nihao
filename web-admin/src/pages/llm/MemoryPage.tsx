import { useState, useCallback, useEffect } from 'react';
import { Card, Table, Button, Tag, Typography, App, Modal, Form, Input, Select, Space, Tabs } from 'antd';
import { DeleteOutlined, ReloadOutlined, PlusOutlined, EditOutlined } from '@ant-design/icons';
import { llmApi } from '@/api/llm';
import { MemoryItem } from '@/types/api';
import type { ColumnsType } from 'antd/es/table';
import styles from './MemoryPage.module.css';

const { Text } = Typography;

type MemoryCategory = 'LONG_TERM' | 'SHORT_TERM';

interface MemoryFormValues {
  content: string;
  memoryType: 'FACT' | 'PREFERENCE';
}

const DESCRIPTIONS: Record<MemoryCategory, string> = {
  LONG_TERM:
    '手动配置的长期设定，每次对话无条件注入 AI 系统提示作为对话基础，AI 生成必须遵守。AI 不会自动写入此板块，只能手动添加/编辑/删除。',
  SHORT_TERM:
    '对话中自动提取或你通过「记住 …」指令要求记住的信息，跨会话生效；对话时按相关性检索注入，供 AI 参考。不支持手动添加。',
};

const MemoryPage: React.FC = () => {
  const { message } = App.useApp();
  const [activeCategory, setActiveCategory] = useState<MemoryCategory>('LONG_TERM');
  const [records, setRecords] = useState<MemoryItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);
  const [loading, setLoading] = useState(false);
  const [editing, setEditing] = useState<MemoryItem | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm<MemoryFormValues>();

  const load = useCallback(() => {
    setLoading(true);
    llmApi.listMemories(page, size, activeCategory).then((res) => {
      setRecords(res.data.data.records);
      setTotal(res.data.data.pagination.total);
    }).catch(() => {
      message.error('加载记忆失败');
    }).finally(() => setLoading(false));
  }, [page, size, activeCategory, message]);

  useEffect(() => { load(); }, [load]);

  const handleDelete = async (item: MemoryItem) => {
    try {
      await llmApi.deleteMemory(item.id);
      message.success('记忆已删除');
      load();
    } catch {
      message.error('删除失败');
    }
  };

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    setModalOpen(true);
  };

  const openEdit = (item: MemoryItem) => {
    setEditing(item);
    form.setFieldsValue({ content: item.content, memoryType: item.memoryType === 'PREFERENCE' ? 'PREFERENCE' : 'FACT' });
    setModalOpen(true);
  };

  const handleSave = async () => {
    try {
      const values = await form.validateFields();
      setSaving(true);
      if (editing) {
        await llmApi.updateMemory(editing.id, values);
        message.success('记忆已更新');
      } else {
        await llmApi.createMemory(values);
        message.success('记忆已保存');
      }
      setModalOpen(false);
      load();
    } catch (e) {
      // 表单校验失败或后端业务错误（拦截器已提示，如"已存在相同内容的记忆"）不再重复弹
      if (!(e as { errorFields?: unknown }).errorFields) {
        message.error('保存失败，请重试');
      }
    } finally {
      setSaving(false);
    }
  };

  const columns: ColumnsType<MemoryItem> = [
    {
      title: '记忆内容',
      dataIndex: 'content',
      key: 'content',
      render: (text: string) => <Text style={{ wordBreak: 'break-all' }}>{text}</Text>,
    },
    {
      title: '类型',
      dataIndex: 'memoryType',
      key: 'memoryType',
      width: 90,
      render: (type: string) => (
        <Tag color={type === 'PREFERENCE' ? 'purple' : 'blue'}>
          {type === 'PREFERENCE' ? '偏好' : '事实'}
        </Tag>
      ),
    },
    {
      title: '来源会话',
      dataIndex: 'sourceConversationId',
      key: 'sourceConversationId',
      width: 180,
      ellipsis: true,
      render: (v: string | null) => v ?? '-',
    },
    { title: '时间', dataIndex: 'createdAt', key: 'createdAt', width: 170 },
    {
      title: '操作',
      key: 'action',
      width: 140,
      render: (_, item) => (
        <Space size={0}>
          <Button type="text" size="small" icon={<EditOutlined />} onClick={() => openEdit(item)}>
            编辑
          </Button>
          <Button type="text" danger size="small" icon={<DeleteOutlined />} onClick={() => handleDelete(item)}>
            删除
          </Button>
        </Space>
      ),
    },
  ];

  const table = (
    <div className={styles.tableShell}>
      <Table<MemoryItem>
        className={styles.memoryTable}
        rowKey="id"
        columns={columns}
        dataSource={records}
        loading={loading}
        pagination={{
          current: page,
          pageSize: size,
          total,
          showSizeChanger: true,
          showTotal: (t) => `共 ${t} 条记忆`,
          onChange: (p, s) => { setPage(p); setSize(s); },
        }}
      />
    </div>
  );

  return (
    <div className={styles.page}>
      <div className={styles.pageHeader}>
        <div>
          <Typography.Title level={3} className={styles.pageTitle}>记忆管理</Typography.Title>
          <Text type="secondary" className={styles.pageHint}>{DESCRIPTIONS[activeCategory]}</Text>
        </div>
        <Space className={styles.headerActions}>
          {activeCategory === 'LONG_TERM' && (
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>添加记忆</Button>
          )}
          <Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>
        </Space>
      </div>
      <Tabs
        className={styles.tabs}
        activeKey={activeCategory}
        onChange={(k) => { setActiveCategory(k as MemoryCategory); setPage(1); }}
        items={[
          { key: 'LONG_TERM', label: '长期记忆', children: table },
          { key: 'SHORT_TERM', label: '短期记忆', children: table },
        ]}
      />

      <Modal
        title={editing ? '编辑记忆' : '添加记忆'}
        open={modalOpen}
        onOk={handleSave}
        onCancel={() => setModalOpen(false)}
        confirmLoading={saving}
        okText={editing ? '保存' : '添加'}
        destroyOnClose
      >
        <Form form={form} layout="vertical" style={{ marginTop: 8 }}>
          <Form.Item
            name="content"
            label="记忆内容"
            rules={[{ required: true, message: '请输入想记住的内容' }]}
          >
            <Input.TextArea
              rows={4}
              maxLength={200}
              showCount
              placeholder="例：回答问题时请始终使用中文；我叫 immortal，是产品经理"
            />
          </Form.Item>
          <Form.Item name="memoryType" label="类型" initialValue="FACT">
            <Select
              options={[
                { label: '事实（客观信息）', value: 'FACT' },
                { label: '偏好（喜好与习惯）', value: 'PREFERENCE' },
              ]}
            />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default MemoryPage;
