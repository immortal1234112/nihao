import { useState } from 'react';
import { useNavigate, Link, useLocation } from 'react-router-dom';
import { Form, Input, Button, Typography } from 'antd';
import { UserOutlined, LockOutlined, RocketOutlined } from '@ant-design/icons';
import { useAuthStore } from '@/stores/authStore';
import { authApi } from '@/api/auth';
import { useAppFeedback } from '@/utils/feedback';
import ParticleBackground from '@/components/common/ParticleBackground';
import styles from './AuthPage.module.css';

const { Title, Text } = Typography;

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
    <div className={styles.authPage}>
      <ParticleBackground />

      <div className={styles.authPanel}>
        {/* Logo + 标题 */}
        <div className={styles.brandBlock}>
          <div className={styles.brandMark}>
            <RocketOutlined />
          </div>
          <Title level={2} className={styles.brandTitle}>
            AI Agent 创作平台
          </Title>
          <Text type="secondary" className={styles.brandSubtitle}>
            企业级 AI 内容生成平台
          </Text>
        </div>

        <Form name="login" onFinish={onFinish} size="large">
          <Form.Item name="username" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input
              prefix={<UserOutlined />}
              placeholder="用户名"
              autoComplete="username"
              className={styles.authInput}
            />
          </Form.Item>
          <Form.Item name="password" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password
              prefix={<LockOutlined />}
              placeholder="密码"
              autoComplete="current-password"
              className={styles.authInput}
            />
          </Form.Item>
          <Form.Item style={{ marginTop: 8 }}>
            <Button
              type="primary" htmlType="submit" loading={loading} block
              className={styles.submitButton}
            >
              登 录
            </Button>
          </Form.Item>
        </Form>

        <div className={styles.footerText}>
          <Text type="secondary">
            还没有账户？<Link to="/register" className={styles.authLink}>立即注册</Link>
          </Text>
        </div>
      </div>
    </div>
  );
};

export default LoginPage;
