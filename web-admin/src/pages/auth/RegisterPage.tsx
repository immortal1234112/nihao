import { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { Form, Input, Button, Typography, message } from 'antd';
import { UserOutlined, LockOutlined, MailOutlined, PhoneOutlined, RocketOutlined } from '@ant-design/icons';
import { authApi } from '@/api/auth';
import ParticleBackground from '@/components/common/ParticleBackground';

const { Title, Text } = Typography;

const glassStyle: React.CSSProperties = {
  background: 'rgba(255,255,255,0.06)',
  backdropFilter: 'blur(20px)',
  WebkitBackdropFilter: 'blur(20px)',
  border: '1px solid rgba(255,255,255,0.12)',
  borderRadius: 20,
  boxShadow: '0 25px 60px rgba(0,0,0,0.4), 0 0 80px rgba(100,130,255,0.08) inset',
  padding: '40px 40px',
  width: 440,
  zIndex: 1,
  position: 'relative' as const,
};

const inputStyle: React.CSSProperties = {
  background: 'rgba(255,255,255,0.05)', border: '1px solid rgba(255,255,255,0.12)',
  color: '#e0e0f0', borderRadius: 10, height: 44,
};

const RegisterPage: React.FC = () => {
  const [loading, setLoading] = useState(false);
  const navigate = useNavigate();

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
    <div style={{ minHeight: '100vh', display: 'flex', justifyContent: 'center', alignItems: 'center', position: 'relative' }}>
      <ParticleBackground />

      <div style={glassStyle}>
        <div style={{ textAlign: 'center', marginBottom: 28 }}>
          <div style={{
            width: 56, height: 56, borderRadius: 14,
            background: 'linear-gradient(135deg, #6366f1, #8b5cf6, #a855f7)',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            margin: '0 auto 12px',
            boxShadow: '0 8px 32px rgba(99,102,241,0.3)',
          }}>
            <RocketOutlined style={{ fontSize: 24, color: '#fff' }} />
          </div>
          <Title level={2} style={{ margin: '0 0 4px', color: '#e8e8f0', fontWeight: 700 }}>
            创建账户
          </Title>
          <Text style={{ color: 'rgba(200,205,230,0.7)', fontSize: 14 }}>
            加入 AI Agent 创作平台
          </Text>
        </div>

        <Form name="register" onFinish={onFinish} size="large">
          <Form.Item name="username" rules={[
            { required: true, message: '请输入用户名' }, { min: 3, max: 32, message: '3-32 个字符' },
          ]}>
            <Input prefix={<UserOutlined style={{ color: 'rgba(180,190,220,0.6)' }} />} placeholder="用户名" style={inputStyle} />
          </Form.Item>
          <Form.Item name="password" rules={[
            { required: true, message: '请输入密码' }, { min: 8, max: 64, message: '8-64 个字符' },
          ]}>
            <Input.Password prefix={<LockOutlined style={{ color: 'rgba(180,190,220,0.6)' }} />} placeholder="密码" style={inputStyle} />
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
            <Input.Password prefix={<LockOutlined style={{ color: 'rgba(180,190,220,0.6)' }} />} placeholder="确认密码" style={inputStyle} />
          </Form.Item>
          <Form.Item name="email" rules={[
            { required: true, message: '请输入邮箱' }, { type: 'email', message: '请输入有效邮箱' },
          ]}>
            <Input prefix={<MailOutlined style={{ color: 'rgba(180,190,220,0.6)' }} />} placeholder="邮箱" style={inputStyle} />
          </Form.Item>
          <Form.Item name="phone">
            <Input prefix={<PhoneOutlined style={{ color: 'rgba(180,190,220,0.6)' }} />} placeholder="手机号（可选）" style={inputStyle} />
          </Form.Item>
          <Form.Item style={{ marginTop: 8 }}>
            <Button type="primary" htmlType="submit" loading={loading} block
              style={{ height: 46, borderRadius: 10, fontSize: 16, fontWeight: 600,
                background: 'linear-gradient(135deg, #6366f1, #8b5cf6)', border: 'none',
                boxShadow: '0 4px 20px rgba(99,102,241,0.35)' }}>
              注 册
            </Button>
          </Form.Item>
        </Form>

        <div style={{ textAlign: 'center' }}>
          <Text style={{ color: 'rgba(200,205,230,0.5)' }}>
            已有账户？<Link to="/login" style={{ color: '#8b8cf6' }}>返回登录</Link>
          </Text>
        </div>
      </div>
    </div>
  );
};

export default RegisterPage;
