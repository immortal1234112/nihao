import { useState } from 'react';
import { Button, Flex, Form, Input, Modal, Popconfirm, Select, Table, Tag, Typography } from 'antd';
import { PlusOutlined, ReloadOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons';
import { templateApi } from '@/api/template';
import { PromptTemplate, PromptTemplateForm } from '@/types/api';
import { usePagination } from '@/hooks/usePagination';
import { useAppFeedback } from '@/utils/feedback';
import { useAuthStore } from '@/stores/authStore';
import styles from './TemplatePage.module.css';

const { Title } = Typography;

const CATEGORIES = ['摄影', '插画', '国风', '赛博朋克', '动漫', '产品', '海报', '人像', '风景', '视频'];

const scopeMap: Record<string, string> = { PRIVATE: '私有', TENANT: '租户', PUBLIC: '公开' };
const scopeColor: Record<string, string> = { PRIVATE: 'default', TENANT: 'blue', PUBLIC: 'gold' };

/** 模板管理页 — 展示并管理当前用户创建的模板 */
const TemplatePage: React.FC = () => {
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<PromptTemplate | null>(null);
  const [form] = Form.useForm<PromptTemplateForm>();
  const { message } = useAppFeedback();
  const { user } = useAuthStore();
  const myId = user?.id;

  const table = usePagination<PromptTemplate>(
    async (page, size) => {
      // ownerId 由后端过滤（服务端分页），避免前端过滤破坏分页 total 或出现「空页」
      const res = await templateApi.list({ page, size, ownerId: myId });
      return res.data.data;
    },
    [myId]
  );

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    setModalOpen(true);
  };

  const openEdit = (t: PromptTemplate) => {
    setEditing(t);
    form.setFieldsValue({
      name: t.name,
      category: t.category,
      engineType: t.engineType,
      prompt: t.prompt,
      negativePrompt: t.negativePrompt ?? '',
      scope: t.scope,
    });
    setModalOpen(true);
  };

  const handleSave = async () => {
    const values = await form.validateFields();
    try {
      if (editing) {
        await templateApi.update(editing.id, values);
        message.success('模板已更新');
      } else {
        await templateApi.create({ ...values, scope: 'PRIVATE' });
        message.success('模板已创建');
      }
      setModalOpen(false);
      table.refresh();
    } catch {
      // 校验失败或保存失败，错误已由拦截器提示
    }
  };

  const handleDelete = async (id: string) => {
    try {
      await templateApi.delete(id);
      message.success('模板已删除');
      table.refresh();
    } catch {
      // 失败原因已由拦截器提示，这里不再重复弹
    }
  };

  const columns = [
    {
      title: '名称', dataIndex: 'name', key: 'name',
      render: (v: string, r: PromptTemplate) => (
        <Flex align="center" gap={8}>
          <span>{v}</span>
          <Tag color={scopeColor[r.scope]}>{scopeMap[r.scope] || r.scope}</Tag>
        </Flex>
      ),
    },
    { title: '分类', dataIndex: 'category', key: 'category', width: 100, render: (v: string) => <Tag color="blue">{v}</Tag> },
    {
      title: '引擎', dataIndex: 'engineType', key: 'engineType', width: 100,
      render: (v: string) => (v === 'VIDEO' ? '视频' : v === 'IMAGE' ? '图片' : '通用'),
    },
    { title: '提示词', dataIndex: 'prompt', key: 'prompt', ellipsis: true, render: (v: string) => v.slice(0, 80) },
    { title: '使用次数', dataIndex: 'usageCount', key: 'usageCount', width: 90 },
    {
      title: '操作', key: 'actions', width: 130,
      render: (_: unknown, r: PromptTemplate) => (
        <Flex gap={4}>
          <Button type="link" size="small" icon={<EditOutlined />} onClick={() => openEdit(r)}>编辑</Button>
          <Popconfirm title="确定删除该模板？" onConfirm={() => handleDelete(r.id)}>
            <Button type="link" size="small" danger icon={<DeleteOutlined />}>删除</Button>
          </Popconfirm>
        </Flex>
      ),
    },
  ];

  return (
    <div className={styles.page}>
      <Flex justify="space-between" align="center" wrap gap={12} className={styles.pageHeader}>
        <div>
          <Title level={3} className={styles.pageTitle}>我的模板</Title>
          <Typography.Text type="secondary" className={styles.pageHint}>保存常用提示词，快速复用到图片和视频创作</Typography.Text>
        </div>
        <Flex gap={8} className={styles.headerActions}>
          <Button icon={<ReloadOutlined />} onClick={table.refresh}>刷新</Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新建模板</Button>
        </Flex>
      </Flex>

      <div className={styles.tableShell}>
        <Table
          className={styles.templateTable}
          dataSource={table.records}
          columns={columns}
          rowKey="id"
          loading={table.loading}
          size="middle"
          pagination={table.pagination}
          locale={{ emptyText: '还没有自己的模板，点右上角「新建模板」创建' }}
        />
      </div>

      <Modal
        title={editing ? '编辑模板' : '新建模板'}
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        onOk={handleSave}
        okText="保存"
        width={640}
        destroyOnHidden
      >
        <Form form={form} layout="vertical" className={styles.templateForm}>
          <Form.Item name="name" label="模板名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="例如：黄昏城市剪影" maxLength={50} />
          </Form.Item>
          <Flex gap={12}>
            <Form.Item name="category" label="分类" rules={[{ required: true, message: '请选择分类' }]} style={{ flex: 1 }}>
              <Select options={CATEGORIES.map((c) => ({ value: c, label: c }))} placeholder="选择分类" />
            </Form.Item>
            <Form.Item name="engineType" label="适用引擎" rules={[{ required: true }]} style={{ flex: 1 }}>
              <Select
                options={[
                  { value: 'IMAGE', label: '图片' },
                  { value: 'VIDEO', label: '视频' },
                  { value: 'ALL', label: '通用' },
                ]}
              />
            </Form.Item>
          </Flex>
          <Form.Item name="prompt" label="提示词" rules={[{ required: true, message: '请输入提示词' }]}>
            <Input.TextArea rows={5} placeholder="模板提示词正文..." maxLength={2000} showCount />
          </Form.Item>
          <Form.Item name="negativePrompt" label="负面提示词（可选）">
            <Input.TextArea rows={2} placeholder="负面提示词..." maxLength={500} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default TemplatePage;
