import { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { Form, Input, Button, Typography } from 'antd';
import { UserOutlined, LockOutlined, MailOutlined, PhoneOutlined, RocketOutlined } from '@ant-design/icons';
import { authApi } from '@/api/auth';
import { useAppFeedback } from '@/utils/feedback';
import ParticleBackground from '@/components/common/ParticleBackground';
import styles from './AuthPage.module.css';

const { Title, Text } = Typography;

const RegisterPage: React.FC = () => {
  const [loading, setLoading] = useState(false);
  const navigate = useNavigate();
  const { message } = useAppFeedback();

  const onFinish = async (values: { username: string; password: string; email: string; phone?: string }) => {
    setLoading(true);
    try {
      await authApi.register(values);
      message.success('注册成功，请登录');
      navigate('/login');
    } catch {
      // 错误已在拦截器处理
    } finally { setLoading(false); }
  };

  return (
    <div className={styles.authPage}>
      <ParticleBackground />

      <div className={`${styles.authPanel} ${styles.registerPanel}`}>
        <div className={styles.brandBlock}>
          <div className={styles.brandMark}>
            <RocketOutlined />
          </div>
          <Title level={2} className={styles.brandTitle}>
            创建账户
          </Title>
          <Text type="secondary" className={styles.brandSubtitle}>
            加入 AI Agent 创作平台
          </Text>
        </div>

        <Form name="register" onFinish={onFinish} size="large">
          <Form.Item name="username" rules={[
            { required: true, message: '请输入用户名' }, { min: 3, max: 32, message: '3-32 个字符' },
          ]}>
            <Input prefix={<UserOutlined />} placeholder="用户名" className={styles.authInput} />
          </Form.Item>
          <Form.Item name="password" rules={[
            { required: true, message: '请输入密码' }, { min: 8, max: 64, message: '8-64 个字符' },
          ]}>
            <Input.Password prefix={<LockOutlined />} placeholder="密码" className={styles.authInput} />
          </Form.Item>
          <Form.Item name="confirmPassword" dependencies={['password']} rules={[
            { required: true, message: '请确认密码' },
            ({ getFieldValue }) => ({
              validator(_, value) {
                if (!value || getFieldValue('password') === value) return Promise.resolve();
                return Promise.reject(new Error('两次密码不一致'));
              },
            }),
          ]}>
            <Input.Password prefix={<LockOutlined />} placeholder="确认密码" className={styles.authInput} />
          </Form.Item>
          <Form.Item name="email" rules={[
            { required: true, message: '请输入邮箱' }, { type: 'email', message: '请输入有效邮箱' },
          ]}>
            <Input prefix={<MailOutlined />} placeholder="邮箱" className={styles.authInput} />
          </Form.Item>
          <Form.Item name="phone">
            <Input prefix={<PhoneOutlined />} placeholder="手机号（可选）" className={styles.authInput} />
          </Form.Item>
          <Form.Item style={{ marginTop: 8 }}>
            <Button type="primary" htmlType="submit" loading={loading} block className={styles.submitButton}>
              注 册
            </Button>
          </Form.Item>
        </Form>

        <div className={styles.footerText}>
          <Text type="secondary">
            已有账户？<Link to="/login" className={styles.authLink}>返回登录</Link>
          </Text>
        </div>
      </div>
    </div>
  );
};

export default RegisterPage;
