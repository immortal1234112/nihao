import { useEffect, useState } from 'react';
import { Card, Row, Col, Progress, Typography, Spin, Empty } from 'antd';
import {
  PictureOutlined,
  VideoCameraOutlined,
  DatabaseOutlined,
  ApiOutlined,
} from '@ant-design/icons';
import { userApi } from '@/api/user';
import styles from './QuotaPage.module.css';

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
    <div className={styles.page}>
      <div className={styles.pageHeader}>
        <div>
          <Title level={3} className={styles.pageTitle}>配额管理</Title>
          <Typography.Text type="secondary" className={styles.pageHint}>查看图片、视频、存储和 API 调用资源使用情况</Typography.Text>
        </div>
      </div>
      {!hasData ? (
        <div className={styles.emptyState}><Empty description="暂无配额数据" /></div>
      ) : (
        <Row gutter={[16, 16]}>
          {quotaCards.map((qc) => {
            const q = quotas[qc.type];
            if (!q) return null;
            const percent = q.limit > 0 ? Math.round((q.used / q.limit) * 100) : 0;
            return (
              <Col key={qc.type} xs={24} sm={12} xl={6}>
                <Card className={styles.quotaCard}>
                  <div className={styles.quotaHeading}>
                    <div className={styles.quotaIcon}>{qc.icon}</div>
                    <Title level={5} className={styles.quotaTitle}>
                      {qc.label}
                    </Title>
                  </div>
                  <Progress
                    percent={percent}
                    strokeColor={percent > 80 ? '#e06b54' : '#0f766e'}
                    status={percent > 80 ? 'exception' : 'active'}
                  />
                  <div className={styles.quotaNumbers}>
                    <span><strong>{q.used}</strong> 已使用</span>
                    <Text type="secondary">剩余 {q.remaining} / {q.limit}</Text>
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
