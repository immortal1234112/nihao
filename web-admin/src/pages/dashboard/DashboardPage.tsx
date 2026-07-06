import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Row, Col, Card, Statistic, Typography, Table, Tag, Button, Space } from 'antd';
import {
  PictureOutlined,
  VideoCameraOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  PlusOutlined,
} from '@ant-design/icons';
import { taskApi } from '@/api/task';
import { adminApi } from '@/api/admin';
import { userApi } from '@/api/user';
import { AgentTask, TaskStats, QuotaInfo } from '@/types/api';
import { useAuthStore } from '@/stores/authStore';
import dayjs from 'dayjs';

const { Title } = Typography;

const statusColorMap: Record<string, string> = {
  CREATED: 'default',
  VALIDATING: 'processing',
  PENDING: 'warning',
  PROCESSING: 'processing',
  DONE: 'success',
  FAILED: 'error',
  CANCELLED: 'default',
  REVIEWING: 'warning',
};

const DashboardPage: React.FC = () => {
  const [stats, setStats] = useState<TaskStats | null>(null);
  const [recentTasks, setRecentTasks] = useState<AgentTask[]>([]);
  const [quota, setQuota] = useState<QuotaInfo | null>(null);
  const [loading, setLoading] = useState(true);
  const navigate = useNavigate();
  const { user } = useAuthStore();
  const roles = user?.roles || [];
  const isAdmin = roles.includes('TENANT_ADMIN') || roles.includes('SUPER_ADMIN');

  useEffect(() => {
    const load = async () => {
      try {
        const [quotaRes, taskRes] = await Promise.all([
          userApi.getQuota('IMAGE').catch(() => null),
          taskApi.list({ page: 1, size: 5 }).catch(() => null),
        ]);
        if (quotaRes) setQuota(quotaRes.data.data);
        if (taskRes) setRecentTasks(taskRes.data.data.records);

        if (isAdmin) {
          const statsRes = await adminApi.getStats().catch(() => null);
          if (statsRes) setStats(statsRes.data.data);
        }
      } finally {
        setLoading(false);
      }
    };
    load();
  }, []);

  const columns = [
    { title: '任务ID', dataIndex: 'id', key: 'id', width: 200, render: (v: string) => v.slice(0, 12) + '...' },
    { title: '类型', dataIndex: 'workflowType', key: 'type', width: 80 },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (s: string) => <Tag color={statusColorMap[s] || 'default'}>{s}</Tag>,
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 180,
      render: (v: string) => dayjs(v).format('YYYY-MM-DD HH:mm'),
    },
  ];

  return (
    <div>
      <Title level={4} style={{ marginBottom: 24 }}>首页仪表盘</Title>

      {isAdmin && stats && (
        <Row gutter={16} style={{ marginBottom: 24 }}>
          <Col span={4}><Card><Statistic title="总任务" value={stats.total} /></Card></Col>
          <Col span={4}><Card><Statistic title="待处理" value={stats.pending} valueStyle={{ color: '#faad14' }} /></Card></Col>
          <Col span={4}><Card><Statistic title="处理中" value={stats.processing} valueStyle={{ color: '#1677ff' }} /></Card></Col>
          <Col span={4}><Card><Statistic title="已完成" value={stats.done} valueStyle={{ color: '#52c41a' }} /></Card></Col>
          <Col span={4}><Card><Statistic title="失败" value={stats.failed} valueStyle={{ color: '#ff4d4f' }} /></Card></Col>
          <Col span={4}><Card><Statistic title="已取消" value={stats.cancelled} /></Card></Col>
        </Row>
      )}

      <Row gutter={16} style={{ marginBottom: 24 }}>
        <Col span={6}>
          <Card hoverable onClick={() => navigate('/workspace/image')}>
            <Statistic title="图片生成配额剩余" value={quota?.remaining ?? '-'} prefix={<PictureOutlined />} />
          </Card>
        </Col>
        <Col span={6}>
          <Card hoverable onClick={() => navigate('/workspace/video')}>
            <Statistic title="视频生成配额剩余" value={quota?.remaining ?? '-'} prefix={<VideoCameraOutlined />} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title="进行中任务" value={stats?.processing ?? '-'} prefix={<ClockCircleOutlined />} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title="已完成任务" value={stats?.done ?? '-'} prefix={<CheckCircleOutlined />} />
          </Card>
        </Col>
      </Row>

      <Row gutter={16}>
        <Col span={12}>
          <Card
            title="快速开始"
            actions={[
              <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/workspace/image')}>图片生成</Button>,
              <Button icon={<PlusOutlined />} onClick={() => navigate('/workspace/video')}>视频生成</Button>,
              <Button icon={<PlusOutlined />} onClick={() => navigate('/workspace/batch')}>批量生成</Button>,
            ]}
          >
            <p>选择一种创作方式开始生成 AI 内容</p>
          </Card>
        </Col>
        <Col span={12}>
          <Card
            title="最近任务"
            extra={<Button type="link" onClick={() => navigate('/tasks')}>查看全部</Button>}
          >
            <Table
              dataSource={recentTasks}
              columns={columns}
              rowKey="id"
              loading={loading}
              size="small"
              pagination={false}
              onRow={(record) => ({
                onClick: () => navigate(`/tasks/${record.id}`),
                style: { cursor: 'pointer' },
              })}
            />
          </Card>
        </Col>
      </Row>
    </div>
  );
};

export default DashboardPage;
