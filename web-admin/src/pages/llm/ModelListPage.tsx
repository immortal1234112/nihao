import { useEffect, useState } from 'react';
import { Card, Row, Col, Tag, Typography, Spin, Alert } from 'antd';
import { CheckCircleOutlined, CloseCircleOutlined } from '@ant-design/icons';
import { llmApi } from '@/api/llm';
import { LlmModel } from '@/types/api';

const { Title, Text } = Typography;

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

  return (
    <div>
      <Title level={4} style={{ marginBottom: 24 }}>模型广场</Title>
      {models.length === 0 ? (
        <Alert type="info" message="暂无可用的 AI 模型，请联系管理员配置" />
      ) : (
        <Row gutter={[16, 16]}>
          {models.map((model) => (
            <Col key={`${model.provider}-${model.model}`} span={8}>
              <Card
                title={model.model}
                extra={
                  model.available
                    ? <Tag color="success" icon={<CheckCircleOutlined />}>可用</Tag>
                    : <Tag color="error" icon={<CloseCircleOutlined />}>不可用</Tag>
                }
              >
                <Text type="secondary">提供商: {model.provider}</Text>
              </Card>
            </Col>
          ))}
        </Row>
      )}
    </div>
  );
};

export default ModelListPage;
