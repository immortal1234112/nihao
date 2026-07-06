import { useEffect, useState } from 'react';
import { Card, Row, Col, Progress, Typography, Spin, Empty } from 'antd';
import {
  PictureOutlined,
  VideoCameraOutlined,
  DatabaseOutlined,
  ApiOutlined,
} from '@ant-design/icons';
import { userApi } from '@/api/user';

const { Title, Text } = Typography;

interface QuotaCard {
  type: string;
  label: string;
  icon: React.ReactNode;
}

const quotaCards: QuotaCard[] = [
  { type: 'IMAGE', label: '图片生成', icon: <PictureOutlined /> },
  { type: 'VIDEO', label: '视频生成', icon: <VideoCameraOutlined /> },
  { type: 'STORAGE', label: '存储空间', icon: <DatabaseOutlined /> },
  { type: 'API_CALL', label: 'API 调用', icon: <ApiOutlined /> },
];

interface QuotaData {
  limit: number;
  used: number;
  remaining: number;
}

const QuotaPage: React.FC = () => {
  const [quotas, setQuotas] = useState<Record<string, QuotaData>>({});
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    Promise.allSettled(
      quotaCards.map((q) => userApi.getQuota(q.type)),
    ).then((results) => {
      const map: Record<string, QuotaData> = {};
      results.forEach((r, i) => {
        if (r.status === 'fulfilled') {
          map[quotaCards[i].type] = r.value.data.data;
        }
      });
      setQuotas(map);
    }).finally(() => setLoading(false));
  }, []);

  if (loading) {
    return <Spin size="large" style={{ display: 'block', margin: '100px auto' }} />;
  }

  const hasData = Object.keys(quotas).length > 0;

  return (
    <div>
      <Title level={4} style={{ marginBottom: 24 }}>配额管理</Title>
      {!hasData ? (
        <Empty description="暂无配额数据" />
      ) : (
        <Row gutter={[16, 16]}>
          {quotaCards.map((qc) => {
            const q = quotas[qc.type];
            if (!q) return null;
            const percent = q.limit > 0 ? Math.round((q.used / q.limit) * 100) : 0;
            return (
              <Col key={qc.type} span={6}>
                <Card>
                  <div style={{ textAlign: 'center', marginBottom: 16 }}>
                    <div style={{ fontSize: 32, color: '#1677ff' }}>{qc.icon}</div>
                    <Title level={5} style={{ marginTop: 8 }}>
                      {qc.label}
                    </Title>
                  </div>
                  <Progress
                    percent={percent}
                    status={percent > 80 ? 'exception' : 'active'}
                  />
                  <div style={{ textAlign: 'center', marginTop: 8 }}>
                    <Text type="secondary">
                      {q.used} / {q.limit} (剩余 {q.remaining})
                    </Text>
                  </div>
                </Card>
              </Col>
            );
          })}
        </Row>
      )}
    </div>
  );
};

export default QuotaPage;
