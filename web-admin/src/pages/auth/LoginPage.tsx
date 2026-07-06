import { useState } from 'react';
import { useNavigate, Link, useLocation } from 'react-router-dom';
import { Form, Input, Button, Card, Typography, message } from 'antd';
import { UserOutlined, LockOutlined } from '@ant-design/icons';
import { useAuthStore } from '@/stores/authStore';
import { authApi } from '@/api/auth';

const { Title, Text } = Typography;

const LoginPage: React.FC = () => {
  const [loading, setLoading] = useState(false);
  const { setTokens, setUser } = useAuthStore();
  const navigate = useNavigate();
  const location = useLocation();

  const from = (location.state as { from?: { pathname: string } })?.from?.pathname || '/dashboard';

  const onFinish = async (values: { username: string; password: string }) => {
    setLoading(true);
    try {
      const res = await authApi.login(values);
      const { accessToken, refreshToken, expiresIn } = res.data.data;
      setTokens({ accessToken, refreshToken, expiresIn });

      // 获取用户信息
      const userRes = await import('@/api/user').then((m) =>
        m.userApi.getMe()
      );
      const userData = userRes.data.data;
      setUser({
        id: userData.id as string,
        username: userData.username as string,
        email: userData.email as string,
        phone: (userData.phone as string) || '',
        avatarUrl: (userData.avatarUrl as string) || '',
        status: userData.status as string,
        roles: (userData.roles as string[]) || [],
        tenantId: userData.tenantId as string,
      });

      message.success('登录成功');
      navigate(from, { replace: true });
    } catch {
      // 错误已在拦截器中处理
    } finally {
      setLoading(false);
    }
  };

  return (
    <div style={{ minHeight: '100vh', display: 'flex', justifyContent: 'center', alignItems: 'center', background: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)' }}>
      <Card style={{ width: 400, boxShadow: '0 8px 24px rgba(0,0,0,0.15)' }}>
        <div style={{ textAlign: 'center', marginBottom: 32 }}>
          <Title level={2} style={{ marginBottom: 4 }}>AI Agent 创作平台</Title>
          <Text type="secondary">登录您的账户</Text>
        </div>
        <Form name="login" onFinish={onFinish} size="large">
          <Form.Item name="username" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input prefix={<UserOutlined />} placeholder="用户名" />
          </Form.Item>
          <Form.Item name="password" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password prefix={<LockOutlined />} placeholder="密码" />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" loading={loading} block>
              登 录
            </Button>
          </Form.Item>
        </Form>
        <div style={{ textAlign: 'center' }}>
          <Text type="secondary">
            还没有账户？<Link to="/register">立即注册</Link>
          </Text>
        </div>
      </Card>
    </div>
  );
};

export default LoginPage;
