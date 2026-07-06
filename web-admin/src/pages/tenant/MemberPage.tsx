import { useEffect, useState } from 'react';
import { Table, Button, Modal, Form, Input, Select, Tag, Space, Typography, Popconfirm, message } from 'antd';
import { PlusOutlined, DeleteOutlined } from '@ant-design/icons';
import { useAuthStore } from '@/stores/authStore';
import { tenantApi } from '@/api/tenant';
import { TenantMember } from '@/types/api';

const { Title } = Typography;

const roleOptions = [
  { value: 'TENANT_ADMIN', label: '租户管理员' },
  { value: 'DEVELOPER', label: '开发者' },
  { value: 'CONTENT_CREATOR', label: '内容创作者' },
  { value: 'VIEWER', label: '观察者' },
];

const MemberPage: React.FC = () => {
  const { user } = useAuthStore();
  const [members, setMembers] = useState<TenantMember[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [form] = Form.useForm();

  const loadMembers = async () => {
    if (!user?.tenantId) return;
    setLoading(true);
    try {
      const res = await tenantApi.getMembers(user.tenantId);
      setMembers(res.data.data);
    } finally { setLoading(false); }
  };

  useEffect(() => { loadMembers(); }, [user]);

  const handleAdd = async (values: { userId: string; roleCode: string }) => {
    try {
      await tenantApi.addMember(user!.tenantId, values);
      message.success('成员添加成功');
      setModalOpen(false);
      form.resetFields();
      loadMembers();
    } catch { /* handled */ }
  };

  const handleRemove = async (userId: string) => {
    try {
      await tenantApi.removeMember(user!.tenantId, userId);
      message.success('成员已移除');
      loadMembers();
    } catch { /* handled */ }
  };

  const columns = [
    { title: '用户ID', dataIndex: 'userId', key: 'userId', width: 200, render: (v: string) => v.slice(0, 12) + '...' },
    { title: '用户名', dataIndex: 'username', key: 'username' },
    { title: '邮箱', dataIndex: 'email', key: 'email' },
    {
      title: '角色', dataIndex: 'roleCode', key: 'role', width: 120,
      render: (v: string) => <Tag color={v === 'TENANT_ADMIN' ? 'blue' : 'default'}>{v}</Tag>,
    },
    {
      title: '操作', key: 'actions', width: 80,
      render: (_: unknown, record: TenantMember) => (
        record.roleCode !== 'TENANT_ADMIN' && (
          <Popconfirm title="确定移除该成员？" onConfirm={() => handleRemove(record.userId)}>
            <Button type="link" danger size="small" icon={<DeleteOutlined />}>移除</Button>
          </Popconfirm>
        )
      ),
    },
  ];

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <Title level={4} style={{ margin: 0 }}>成员管理</Title>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setModalOpen(true)}>添加成员</Button>
      </div>
      <Table dataSource={members} columns={columns} rowKey="userId" loading={loading} />

      <Modal title="添加成员" open={modalOpen} onCancel={() => setModalOpen(false)} footer={null}>
        <Form form={form} layout="vertical" onFinish={handleAdd}>
          <Form.Item name="userId" label="用户ID" rules={[{ required: true, message: '请输入用户ID' }]}>
            <Input placeholder="输入要添加的用户ID" />
          </Form.Item>
          <Form.Item name="roleCode" label="角色" rules={[{ required: true, message: '请选择角色' }]}>
            <Select options={roleOptions} />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" block>添加</Button>
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default MemberPage;
