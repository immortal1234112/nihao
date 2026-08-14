import { useEffect, useState } from 'react';
import { Card, Row, Col, Form, Input, Button, Descriptions, Typography, Tag, Space, Spin } from 'antd';
import { SaveOutlined } from '@ant-design/icons';
import { useAuthStore } from '@/stores/authStore';
import { userApi } from '@/api/user';
import { useAppFeedback } from '@/utils/feedback';

const { Title } = Typography;

const ProfilePage: React.FC = () => {
  const { user, setUser } = useAuthStore();
  const [editing, setEditing] = useState(false);
  const [loading, setLoading] = useState(false);
  const [form] = Form.useForm();
  const { message } = useAppFeedback();

  useEffect(() => {
    if (user) {
      form.setFieldsValue({ email: user.email, phone: user.phone });
    }
  }, [user, form]);

  const handleSave = async (values: { email: string; phone: string }) => {
    setLoading(true);
    try {
      await userApi.updateMe(values);
      if (user) setUser({ ...user, email: values.email, phone: values.phone });
      message.success('更新成功');
      setEditing(false);
    } finally {
      setLoading(false);
    }
  };

  if (!user) return <Spin />;

  return (
    <div>
      <Title level={4} style={{ marginBottom: 24 }}>个人信息</Title>
      <Row gutter={24}>
        <Col span={12}>
          <Card title="基本信息">
            <Descriptions column={1} bordered size="small">
              <Descriptions.Item label="用户ID">{user.id}</Descriptions.Item>
              <Descriptions.Item label="用户名">{user.username}</Descriptions.Item>
              <Descriptions.Item label="邮箱">{user.email || '-'}</Descriptions.Item>
              <Descriptions.Item label="手机号">{user.phone || '-'}</Descriptions.Item>
              <Descriptions.Item label="租户ID">{user.tenantId}</Descriptions.Item>
              <Descriptions.Item label="状态">
                <Tag color={user.status === 'ACTIVE' ? 'success' : 'error'}>{user.status}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="角色">
                <Space>
                  {user.roles.map((r) => (
                    <Tag key={r}>{r}</Tag>
                  ))}
                </Space>
              </Descriptions.Item>
            </Descriptions>
          </Card>
        </Col>
        <Col span={12}>
          <Card
            title="编辑信息"
            extra={
              <Button type={editing ? 'default' : 'primary'} onClick={() => setEditing(!editing)}>
                {editing ? '取消' : '编辑'}
              </Button>
            }
          >
            <Form form={form} layout="vertical" onFinish={handleSave}>
              <Form.Item name="email" label="邮箱" rules={[{ type: 'email', message: '请输入有效的邮箱' }]}>
                <Input disabled={!editing} />
              </Form.Item>
              <Form.Item name="phone" label="手机号">
                <Input disabled={!editing} />
              </Form.Item>
              {editing && (
                <Form.Item>
                  <Button type="primary" htmlType="submit" icon={<SaveOutlined />} loading={loading}>
                    保存修改
                  </Button>
                </Form.Item>
              )}
            </Form>
          </Card>
        </Col>
      </Row>
    </div>
  );
};

export default ProfilePage;
