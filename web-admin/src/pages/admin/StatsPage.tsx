import { useEffect, useState } from 'react';
import { Card, Row, Col, Statistic, Typography, Spin } from 'antd';
import { adminApi } from '@/api/admin';
import { TaskStats } from '@/types/api';

const { Title } = Typography;

const StatsPage: React.FC = () => {
  const [stats, setStats] = useState<TaskStats | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    adminApi.getStats()
      .then((res) => setStats(res.data.data))
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  if (loading) return <Spin size="large" style={{ display: 'block', margin: '100px auto' }} />;

  const chartData = stats ? [
    { key: 'total', label: '总任务', value: stats.total, color: '#1677ff' },
    { key: 'pending', label: '待处理', value: stats.pending, color: '#faad14' },
    { key: 'processing', label: '处理中', value: stats.processing, color: '#1677ff' },
    { key: 'done', label: '已完成', value: stats.done, color: '#52c41a' },
    { key: 'failed', label: '失败', value: stats.failed, color: '#ff4d4f' },
    { key: 'cancelled', label: '已取消', value: stats.cancelled, color: '#999' },
  ] : [];

  return (
    <div>
      <Title level={4} style={{ marginBottom: 24 }}>系统统计</Title>
      <Row gutter={[16, 16]}>
        {chartData.map((item) => (
          <Col key={item.key} span={8}>
            <Card>
              <Statistic title={item.label} value={item.value} valueStyle={{ color: item.color }} />
            </Card>
          </Col>
        ))}
      </Row>
    </div>
  );
};

export default StatsPage;
