import { useEffect, useState } from 'react';
import { Table, Tag, Button, Space, Popconfirm, Typography, message } from 'antd';
import { CheckOutlined, CloseOutlined, ReloadOutlined } from '@ant-design/icons';
import { adminApi } from '@/api/admin';
import { AgentTask } from '@/types/api';
import dayjs from 'dayjs';

const { Title } = Typography;

const ReviewPage: React.FC = () => {
  const [tasks, setTasks] = useState<AgentTask[]>([]);
  const [loading, setLoading] = useState(false);
  const [pagination, setPagination] = useState({ page: 1, size: 20, total: 0 });

  const loadTasks = async (page = 1, size = 20) => {
    setLoading(true);
    try {
      const res = await adminApi.listAllTasks({ page, size, status: 'REVIEWING' });
      const data = res.data.data;
      setTasks(data.records);
      setPagination({ page: data.pagination.page, size: data.pagination.size, total: data.pagination.total });
    } finally { setLoading(false); }
  };

  useEffect(() => { loadTasks(); }, []);

  const handleReview = async (id: string, action: 'PASS' | 'REJECT') => {
    try {
      await adminApi.reviewTask(id, action);
      message.success(action === 'PASS' ? '已通过' : '已拒绝');
      loadTasks();
    } catch { /* handled */ }
  };

  const columns = [
    { title: '任务ID', dataIndex: 'id', key: 'id', width: 180, render: (v: string) => v.slice(0, 8) + '...' + v.slice(-4) },
    { title: '用户ID', dataIndex: 'userId', key: 'userId', width: 180, render: (v: string) => v.slice(0, 8) + '...' },
    { title: '类型', dataIndex: 'workflowType', key: 'type', width: 80 },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 100,
      render: (s: string) => <Tag color="warning">{s}</Tag>,
    },
    { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 170, render: (v: string) => dayjs(v).format('MM-DD HH:mm:ss') },
    {
      title: '操作', key: 'actions', width: 160,
      render: (_: unknown, record: AgentTask) => (
        <Space>
          <Popconfirm title="确定通过该任务？" onConfirm={() => handleReview(record.id, 'PASS')}>
            <Button type="link" size="small" icon={<CheckOutlined />} style={{ color: '#52c41a' }}>通过</Button>
          </Popconfirm>
          <Popconfirm title="确定拒绝该任务？" onConfirm={() => handleReview(record.id, 'REJECT')}>
            <Button type="link" size="small" danger icon={<CloseOutlined />}>拒绝</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <Title level={4} style={{ margin: 0 }}>任务审核</Title>
        <Button icon={<ReloadOutlined />} onClick={() => loadTasks()}>刷新</Button>
      </div>
      <Table
        dataSource={tasks}
        columns={columns}
        rowKey="id"
        loading={loading}
        pagination={{
          current: pagination.page,
          pageSize: pagination.size,
          total: pagination.total,
          showTotal: (t) => `共 ${t} 条待审核`,
          onChange: (page, size) => loadTasks(page, size),
        }}
      />
    </div>
  );
};

export default ReviewPage;
