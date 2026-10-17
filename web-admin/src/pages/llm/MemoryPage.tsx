import { useState, useCallback, useEffect, useMemo } from 'react';
import {
  Card, Table, Button, Tag, Typography, App, Modal, Form, Input, Select, Space, Tabs, Tooltip, Popconfirm,
} from 'antd';
import {
  DeleteOutlined, ReloadOutlined, PlusOutlined, EditOutlined, FolderOutlined, LockOutlined,
} from '@ant-design/icons';
import { llmApi } from '@/api/llm';
import { MemoryItem, MemorySpace } from '@/types/api';
import type { ColumnsType } from 'antd/es/table';
import styles from './MemoryPage.module.css';

const { Text } = Typography;

type MemoryCategory = 'LONG_TERM' | 'SHORT_TERM';

interface MemoryFormValues {
  content: string;
  memoryType: 'FACT' | 'PREFERENCE';
}

interface SpaceFormValues {
  name: string;
  description?: string;
}

const DESCRIPTIONS: Record<MemoryCategory, string> = {
  LONG_TERM:
    '手动配置的长期设定，每次对话无条件注入 AI 系统提示作为对话基础，AI 生成必须遵守。AI 不会自动写入此板块，只能手动添加/编辑/删除。',
  SHORT_TERM:
    '对话中自动提取或你通过「记住 …」指令要求记住的信息，跨会话生效；对话时按相关性检索注入，供 AI 参考。不支持手动添加。',
};

const SPACE_HINT =
  '记忆空间是一套独立的长期+短期记忆。新建对话时选定一个空间，该对话只会参考并使用这个空间的记忆——这样你可以同时开多个对话，分别对应工作、写作、个人等不同场景而互不干扰。';

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

  // ===== 记忆空间 =====
  const [spaces, setSpaces] = useState<MemorySpace[]>([]);
  const [activeSpaceId, setActiveSpaceId] = useState<string | undefined>(undefined);
  const [spaceModalOpen, setSpaceModalOpen] = useState(false);
  const [editingSpace, setEditingSpace] = useState<MemorySpace | null>(null);
  const [savingSpace, setSavingSpace] = useState(false);
  const [spaceForm] = Form.useForm<SpaceFormValues>();

  const activeSpace = useMemo(
    () => spaces.find((s) => s.id === activeSpaceId),
    [spaces, activeSpaceId],
  );

  const loadSpaces = useCallback(() => {
    llmApi.listSpaces().then((res) => {
      const list = res.data.data;
      setSpaces(list);
      // 首次加载或当前空间已不存在（被删除）时，回落到默认空间
      setActiveSpaceId((prev) => {
        if (prev && list.some((s) => s.id === prev)) return prev;
        return list.find((s) => s.isDefault)?.id ?? list[0]?.id;
      });
    }).catch(() => {
      message.error('加载记忆空间失败');
    });
  }, [message]);

  useEffect(() => { loadSpaces(); }, [loadSpaces]);

  const load = useCallback(() => {
    if (!activeSpaceId) return;
    setLoading(true);
    llmApi.listMemories(page, size, activeCategory, activeSpaceId).then((res) => {
      setRecords(res.data.data.records);
      setTotal(res.data.data.pagination.total);
    }).catch(() => {
      message.error('加载记忆失败');
    }).finally(() => setLoading(false));
  }, [page, size, activeCategory, activeSpaceId, message]);

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
        // 新增记忆归属当前选中的空间
        await llmApi.createMemory({ ...values, spaceId: activeSpaceId });
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

  // ===== 空间操作 =====
  const openCreateSpace = () => {
    setEditingSpace(null);
    spaceForm.resetFields();
    setSpaceModalOpen(true);
  };

  const openEditSpace = (space: MemorySpace) => {
    setEditingSpace(space);
    spaceForm.setFieldsValue({ name: space.name, description: space.description });
    setSpaceModalOpen(true);
  };

  const handleSaveSpace = async () => {
    try {
      const values = await spaceForm.validateFields();
      setSavingSpace(true);
      if (editingSpace) {
        await llmApi.updateSpace(editingSpace.id, values);
        message.success('空间已更新');
      } else {
        const res = await llmApi.createSpace(values);
        message.success('空间已创建');
        // 新建后直接切过去，符合「建完就想用」的预期
        setActiveSpaceId(res.data.data.id);
      }
      setSpaceModalOpen(false);
      setPage(1);
      loadSpaces();
    } catch (e) {
      if (!(e as { errorFields?: unknown }).errorFields) {
        message.error('保存失败，请重试');
      }
    } finally {
      setSavingSpace(false);
    }
  };

  const handleDeleteSpace = async (space: MemorySpace) => {
    try {
      await llmApi.deleteSpace(space.id);
      message.success('空间已删除');
      setPage(1);
      loadSpaces();
    } catch {
      // 失败原因已由拦截器提示（如"默认空间不可删除"）
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
          <Button icon={<ReloadOutlined />} onClick={() => { loadSpaces(); load(); }}>刷新</Button>
        </Space>
      </div>

      {/* 记忆空间切换：决定下方列表属于哪一套记忆 */}
      <Card className={styles.spaceCard} styles={{ body: { padding: '10px 12px' } }}>
        <div className={styles.spaceBar}>
          <Space size={6} className={styles.spaceLabel}>
            <FolderOutlined />
            <Text strong>记忆空间</Text>
            <Tooltip title={SPACE_HINT} placement="right">
              <Text type="secondary" className={styles.spaceHelp}>这是什么？</Text>
            </Tooltip>
          </Space>

          <Space size={4} wrap className={styles.spaceChips}>
            {spaces.map((s) => {
              const active = s.id === activeSpaceId;
              return (
                <span
                  key={s.id}
                  className={`${styles.spaceChip} ${active ? styles.spaceChipActive : ''}`}
                  onClick={() => { setActiveSpaceId(s.id); setPage(1); }}
                >
                  {s.isDefault && <LockOutlined className={styles.spaceChipIcon} />}
                  {s.name}
                </span>
              );
            })}
            <Button
              type="text"
              size="small"
              icon={<PlusOutlined />}
              className={styles.spaceAddBtn}
              onClick={openCreateSpace}
            >
              新建空间
            </Button>
          </Space>

          {activeSpace && (
            <Space size={0} className={styles.spaceActions}>
              <Button
                type="text"
                size="small"
                icon={<EditOutlined />}
                disabled={activeSpace.isDefault}
                onClick={() => openEditSpace(activeSpace)}
              >
                编辑
              </Button>
              <Popconfirm
                title="删除该空间？"
                description="空间内的全部记忆会被一并删除，且不可恢复。"
                okText="删除"
                okButtonProps={{ danger: true }}
                cancelText="取消"
                onConfirm={() => handleDeleteSpace(activeSpace)}
                disabled={activeSpace.isDefault}
              >
                <Button
                  type="text"
                  size="small"
                  danger
                  icon={<DeleteOutlined />}
                  disabled={activeSpace.isDefault}
                >
                  删除
                </Button>
              </Popconfirm>
            </Space>
          )}
        </div>
        {activeSpace?.description && (
          <div className={styles.spaceDesc}>
            <Text type="secondary">{activeSpace.description}</Text>
          </div>
        )}
      </Card>

      <Tabs
        className={styles.tabs}
        activeKey={activeCategory}
        onChange={(k) => { setActiveCategory(k as MemoryCategory); setPage(1); }}
        items={[
          { key: 'LONG_TERM', label: '长期记忆', children: table },
          { key: 'SHORT_TERM', label: '短期记忆', children: table },
        ]}
      />

      {/* 记忆编辑 */}
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
          {!editing && activeSpace && (
            <Form.Item label="所属空间">
              <Text className={styles.modalSpaceName}>{activeSpace.name}</Text>
            </Form.Item>
          )}
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

      {/* 空间新建/编辑 */}
      <Modal
        title={editingSpace ? '编辑记忆空间' : '新建记忆空间'}
        open={spaceModalOpen}
        onOk={handleSaveSpace}
        onCancel={() => setSpaceModalOpen(false)}
        confirmLoading={savingSpace}
        okText={editingSpace ? '保存' : '创建'}
        destroyOnClose
      >
        <Form form={spaceForm} layout="vertical" style={{ marginTop: 8 }}>
          <Form.Item
            name="name"
            label="空间名称"
            rules={[
              { required: true, message: '请输入空间名称' },
              { max: 64, message: '不能超过 64 个字符' },
            ]}
          >
            <Input placeholder="例：工作、写作、个人" maxLength={64} />
          </Form.Item>
          <Form.Item name="description" label="说明（可选）">
            <Input.TextArea
              rows={2}
              maxLength={255}
              showCount
              placeholder="例：写代码与需求评审时使用，长期记忆里是我的技术背景"
            />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default MemoryPage;
