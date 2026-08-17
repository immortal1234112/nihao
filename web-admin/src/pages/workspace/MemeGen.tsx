import { useState, useRef, useCallback, useEffect } from 'react';
import { Card, Form, Input, Button, Typography, Spin, Result, Space, Tag } from 'antd';
import { SendOutlined, ClearOutlined, ReloadOutlined } from '@ant-design/icons';
import { memeApi, MemeResultRes } from '@/api/meme';
import { AuthImage } from '@/components/common/AuthMedia';

const { Title, Text, Paragraph } = Typography;
const { TextArea } = Input;

const statusLabels: Record<string, { color: string; text: string }> = {
  PENDING:  { color: 'default',   text: '排队中' },
  PROCESSING: { color: 'processing', text: '生成中' },
  DONE:     { color: 'success',   text: '完成' },
  FAILED:   { color: 'error',     text: '失败' },
};

const MemeGen: React.FC = () => {
  const [loading, setLoading] = useState(false);
  const [taskId, setTaskId] = useState<string | null>(null);
  const [result, setResult] = useState<MemeResultRes | null>(null);
  const [form] = Form.useForm();
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const stopPolling = useCallback(() => {
    if (pollRef.current) {
      clearInterval(pollRef.current);
      pollRef.current = null;
    }
  }, []);

  // D4：组件卸载时清理轮询，避免卸载后继续 setState 造成内存泄漏
  useEffect(() => stopPolling, [stopPolling]);

  const startPolling = useCallback((id: string) => {
    stopPolling();
    pollRef.current = setInterval(async () => {
      try {
        const res = await memeApi.getResult(id);
        const data = res.data.data;
        if (data) {
          setResult(data);
          if (data.status === 'DONE' || data.status === 'FAILED') {
            stopPolling();
            setLoading(false);
          }
        }
      } catch {
        stopPolling();
        setLoading(false);
      }
    }, 2000);
  }, [stopPolling]);

  const onFinish = async (values: { text: string }) => {
    setLoading(true);
    setResult(null);
    setTaskId(null);
    try {
      const res = await memeApi.generate(values.text);
      const id = res.data.data?.taskId;
      if (id) {
        setTaskId(id);
        startPolling(id);
      }
    } catch {
      setLoading(false);
    }
  };

  const onReset = () => {
    stopPolling();
    form.resetFields();
    setTaskId(null);
    setResult(null);
    setLoading(false);
  };

  const statusTag = result ? statusLabels[result.status] : null;

  return (
    <div>
      <Title level={5} style={{ marginBottom: 16 }}>
        😄 表情包生成 <Text type="secondary" style={{ fontSize: 14, fontWeight: 'normal' }}>— 一句话生成表情包</Text>
      </Title>

      <Card>
        <Form form={form} layout="vertical" onFinish={onFinish}>
          <Form.Item
            name="text"
            label="输入一句话"
            rules={[{ required: true, message: '请输入你想表达的内容' }]}
          >
            <TextArea
              rows={3}
              placeholder="例如：今天周五了、明天又要上班了、代码终于跑通了..."
              maxLength={200}
              showCount
              disabled={loading}
            />
          </Form.Item>

          <Form.Item>
            <Space>
              <Button
                type="primary"
                htmlType="submit"
                icon={<SendOutlined />}
                loading={loading && !result}
              >
                生成表情包
              </Button>
              <Button icon={<ClearOutlined />} onClick={onReset} disabled={loading}>
                重置
              </Button>
            </Space>
          </Form.Item>
        </Form>

        {/* 状态提示 */}
        {taskId && statusTag && (
          <div style={{ marginBottom: 16 }}>
            <Tag color={statusTag.color}>{statusTag.text}</Tag>
            {result?.status !== 'DONE' && result?.status !== 'FAILED' && (
              <Spin size="small" style={{ marginLeft: 8 }} />
            )}
          </div>
        )}

        {/* 生成结果 */}
        {result?.status === 'DONE' && (
          <div style={{ textAlign: 'center', padding: '24px 0' }}>
            <AuthImage
              src={result.imageUrl!}
              alt={result.memeText || '表情包'}
              style={{ maxWidth: '100%', maxHeight: 480, borderRadius: 8 }}
            />
            {result.memeText && (
              <Paragraph style={{ marginTop: 16, fontSize: 18, fontWeight: 600 }}>
                {result.memeText.split('\n').map((line, i) => (
                  <span key={i}>{line}{i < result.memeText!.split('\n').length - 1 && <br />}</span>
                ))}
              </Paragraph>
            )}
            <Button
              style={{ marginTop: 12 }}
              icon={<ReloadOutlined />}
              onClick={onReset}
            >
              再来一张
            </Button>
          </div>
        )}

        {/* 失败提示 */}
        {result?.status === 'FAILED' && (
          <Result
            status="error"
            title="生成失败"
            subTitle={result.errorInfo || '未知错误'}
            extra={<Button onClick={onReset}>重试</Button>}
          />
        )}
      </Card>

      {/* 说明 */}
      <Card title="💡 使用说明" size="small" style={{ marginTop: 16 }}>
        <ul style={{ margin: 0, paddingLeft: 20 }}>
          <li>输入你想表达的一句话，AI 会自动匹配热门表情包模板</li>
          <li>千问大模型负责选模板、写梗和生成绘图提示词</li>
          <li>通义万相负责把提示词变成图片，约需 10-30 秒</li>
          <li>生成的表情包可直接保存到本地使用</li>
        </ul>
      </Card>
    </div>
  );
};

export default MemeGen;
