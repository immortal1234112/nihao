import { useState } from 'react';
import { useNavigate, Link, useLocation } from 'react-router-dom';
import { Form, Input, Button, Typography } from 'antd';
import { UserOutlined, LockOutlined, RocketOutlined } from '@ant-design/icons';
import { useAuthStore } from '@/stores/authStore';
import { authApi } from '@/api/auth';
import { useAppFeedback } from '@/utils/feedback';
import ParticleBackground from '@/components/common/ParticleBackground';

const { Title, Text } = Typography;

const glassStyle: React.CSSProperties = {
  background: 'rgba(255,255,255,0.06)',
  backdropFilter: 'blur(20px)',
  WebkitBackdropFilter: 'blur(20px)',
  border: '1px solid rgba(255,255,255,0.12)',
  borderRadius: 20,
  boxShadow: '0 25px 60px rgba(0,0,0,0.4), 0 0 80px rgba(100,130,255,0.08) inset',
  padding: '48px 40px',
  width: 420,
  zIndex: 1,
  position: 'relative' as const,
};

const LoginPage: React.FC = () => {
  const [loading, setLoading] = useState(false);
  const { setTokens, setUser } = useAuthStore();
  const { message } = useAppFeedback();
  const navigate = useNavigate();
  const location = useLocation();

  const from = (location.state as { from?: { pathname: string } })?.from?.pathname || '/dashboard';

  const onFinish = async (values: { username: string; password: string }) => {
    setLoading(true);
    try {
      const res = await authApi.login(values);
      const { accessToken, refreshToken, expiresIn } = res.data.data;
      setTokens({ accessToken, refreshToken, expiresIn });

      const userRes = await import('@/api/user').then((m) => m.userApi.getMe());
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
      // 错误已在拦截器处理
    } finally {
      setLoading(false);
    }
  };

  return (
    <div style={{ minHeight: '100vh', display: 'flex', justifyContent: 'center', alignItems: 'center', position: 'relative' }}>
      <ParticleBackground />

      <div style={glassStyle}>
        {/* Logo + 标题 */}
        <div style={{ textAlign: 'center', marginBottom: 36 }}>
          <div style={{
            width: 64, height: 64, borderRadius: 16,
            background: 'linear-gradient(135deg, #6366f1, #8b5cf6, #a855f7)',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            margin: '0 auto 16px',
            boxShadow: '0 8px 32px rgba(99,102,241,0.3)',
          }}>
            <RocketOutlined style={{ fontSize: 28, color: '#fff' }} />
          </div>
          <Title level={2} style={{ margin: '0 0 4px', color: '#e8e8f0', fontWeight: 700, letterSpacing: 2 }}>
            AI Agent 创作平台
          </Title>
          <Text style={{ color: 'rgba(200,205,230,0.7)', fontSize: 14 }}>
            企业级 AI 内容生成平台
          </Text>
        </div>

        <Form name="login" onFinish={onFinish} size="large">
          <Form.Item name="username" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input
              prefix={<UserOutlined style={{ color: 'rgba(180,190,220,0.6)' }} />}
              placeholder="用户名"
              autoComplete="username"
              style={{
                background: 'rgba(255,255,255,0.05)', border: '1px solid rgba(255,255,255,0.12)',
                color: '#e0e0f0', borderRadius: 10, height: 48,
              }}
            />
          </Form.Item>
          <Form.Item name="password" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password
              prefix={<LockOutlined style={{ color: 'rgba(180,190,220,0.6)' }} />}
              placeholder="密码"
              autoComplete="current-password"
              style={{
                background: 'rgba(255,255,255,0.05)', border: '1px solid rgba(255,255,255,0.12)',
                color: '#e0e0f0', borderRadius: 10, height: 48,
              }}
            />
          </Form.Item>
          <Form.Item style={{ marginTop: 8 }}>
            <Button
              type="primary" htmlType="submit" loading={loading} block
              style={{
                height: 48, borderRadius: 10, fontSize: 16, fontWeight: 600,
                background: 'linear-gradient(135deg, #6366f1, #8b5cf6)',
                border: 'none',
                boxShadow: '0 4px 20px rgba(99,102,241,0.35)',
              }}
            >
              登 录
            </Button>
          </Form.Item>
        </Form>

        <div style={{ textAlign: 'center' }}>
          <Text style={{ color: 'rgba(200,205,230,0.5)' }}>
            还没有账户？<Link to="/register" style={{ color: '#8b8cf6' }}>立即注册</Link>
          </Text>
        </div>
      </div>
    </div>
  );
};

export default LoginPage;
