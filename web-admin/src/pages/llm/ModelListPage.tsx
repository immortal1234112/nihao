import { useEffect, useState } from 'react';
import { Card, Row, Col, Tag, Typography, Spin, Alert, Descriptions, Space } from 'antd';
import { CheckCircleOutlined, CloseCircleOutlined, RobotOutlined } from '@ant-design/icons';
import { llmApi } from '@/api/llm';
import { LlmModel } from '@/types/api';

const { Title, Text } = Typography;

const providerNames: Record<string, { name: string; color: string }> = {
  openai:    { name: 'OpenAI',     color: '#10a37f' },
  anthropic: { name: 'Anthropic',  color: '#d97706' },
  deepseek:  { name: 'DeepSeek',   color: '#4f46e5' },
  qwen:      { name: '通义千问',    color: '#1677ff' },
  baidu:     { name: '文心一言',    color: '#f5222d' },
  spark:     { name: '讯飞星火',    color: '#1677ff' },
  zhipu:     { name: '智谱 GLM',   color: '#722ed1' },
  moonshot:  { name: '月之暗面 Kimi', color: '#13c2c2' },
  google:    { name: 'Google Gemini', color: '#4285f4' },
};

const ModelListPage: React.FC = () => {
  const [models, setModels] = useState<LlmModel[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    llmApi.listModels()
      .then((res) => setModels(res.data.data))
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  if (loading) return <Spin size="large" style={{ display: 'block', margin: '100px auto' }} />;

  const llmModels = models.filter(m => m.provider !== 'image' && m.provider !== 'video');

  return (
    <div>
      <Title level={4} style={{ marginBottom: 24 }}>
        <RobotOutlined style={{ marginRight: 8 }} />模型广场
        <Text type="secondary" style={{ fontSize: 14, marginLeft: 12 }}>
          共 {llmModels.filter(m => m.available).length}/{llmModels.length} 个可用
        </Text>
      </Title>
      {llmModels.length === 0 ? (
        <Alert type="info" message="暂无可用的 AI 模型" description="请在 Nacos 或 application.yml 中配置模型的 API Key 后重启服务" />
      ) : (
        <Row gutter={[16, 16]}>
          {llmModels.map((model) => {
            const pn = providerNames[model.provider] || { name: model.provider, color: '#666' };
            return (
              <Col key={`${model.provider}-${model.model}`} span={8}>
                <Card
                  title={
                    <Space>
                      <span style={{ color: pn.color, fontWeight: 600 }}>{pn.name}</span>
                      <Tag>{model.provider}</Tag>
                    </Space>
                  }
                  extra={
                    model.available
                      ? <Tag color="success" icon={<CheckCircleOutlined />}>可用</Tag>
                      : <Tag color="error" icon={<CloseCircleOutlined />}>需配置 API Key</Tag>
                  }
                >
                  <Descriptions column={1} size="small">
                    <Descriptions.Item label="模型">{model.model}</Descriptions.Item>
                  </Descriptions>
                </Card>
              </Col>
            );
          })}
        </Row>
      )}
    </div>
  );
};

export default ModelListPage;
