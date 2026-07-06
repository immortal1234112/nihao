import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Table, Tag, Select, Button, Space, Typography, Popconfirm, message } from 'antd';
import { ReloadOutlined, EyeOutlined, StopOutlined } from '@ant-design/icons';
import { taskApi } from '@/api/task';
import { AgentTask } from '@/types/api';
import dayjs from 'dayjs';

const { Title } = Typography;

const statusColorMap: Record<string, string> = {
  CREATED: 'default', VALIDATING: 'processing', PENDING: 'warning',
  PROCESSING: 'processing', DONE: 'success', FAILED: 'error',
  CANCELLED: 'default', REVIEWING: 'warning',
};

const TaskListPage: React.FC = () => {
  const [tasks, setTasks] = useState<AgentTask[]>([]);
  const [loading, setLoading] = useState(false);
  const [pagination, setPagination] = useState({ page: 1, size: 20, total: 0 });
  const [statusFilter, setStatusFilter] = useState<string | undefined>(undefined);
  const navigate = useNavigate();

  const loadTasks = async (page = 1, size = 20, status?: string) => {
    setLoading(true);
    try {
      const res = await taskApi.list({ page, size, status });
      const data = res.data.data;
      setTasks(data.records);
      setPagination({ page: data.pagination.page, size: data.pagination.size, total: data.pagination.total });
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { loadTasks(1, 20, statusFilter); }, [statusFilter]);

  const handleCancel = async (id: string) => {
    try {
      await taskApi.cancel(id);
      message.success('任务已取消');
      loadTasks(pagination.page, pagination.size, statusFilter);
    } catch { /* handled */ }
  };

  const columns = [
    { title: '任务ID', dataIndex: 'id', key: 'id', width: 180, render: (v: string) => v.slice(0, 8) + '...' + v.slice(-4) },
    {
      title: '类型', dataIndex: 'workflowType', key: 'type', width: 80,
      render: (v: string) => ({ SINGLE: '单任务', PIPELINE: '流水线', BATCH: '批量' }[v] || v),
    },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 110,
      render: (s: string) => <Tag color={statusColorMap[s] || 'default'}>{s}</Tag>,
    },
    {
      title: 'Prompt', dataIndex: 'requestParams', key: 'prompt', ellipsis: true,
      render: (v: string) => {
        try { return JSON.parse(v).prompt?.slice(0, 80) || '-'; } catch { return '-'; }
      },
    },
    { title: '重试', dataIndex: 'retryCount', key: 'retry', width: 60 },
    {
      title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 170,
      render: (v: string) => dayjs(v).format('MM-DD HH:mm:ss'),
    },
    {
      title: '操作', key: 'actions', width: 150,
      render: (_: unknown, record: AgentTask) => (
        <Space>
          <Button type="link" size="small" icon={<EyeOutlined />} onClick={() => navigate(`/tasks/${record.id}`)}>详情</Button>
          {(record.status === 'PENDING' || record.status === 'PROCESSING') && (
            <Popconfirm title="确定取消该任务？" onConfirm={() => handleCancel(record.id)}>
              <Button type="link" size="small" danger icon={<StopOutlined />}>取消</Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <Title level={4} style={{ margin: 0 }}>任务管理</Title>
        <Space>
          <Select
            allowClear placeholder="筛选状态" style={{ width: 140 }} value={statusFilter}
            onChange={(v) => setStatusFilter(v)}
            options={[
              { value: 'PENDING', label: '等待中' }, { value: 'PROCESSING', label: '处理中' },
              { value: 'DONE', label: '已完成' }, { value: 'FAILED', label: '失败' },
              { value: 'CANCELLED', label: '已取消' },
            ]}
          />
          <Button icon={<ReloadOutlined />} onClick={() => loadTasks(pagination.page, pagination.size, statusFilter)}>
            刷新
          </Button>
        </Space>
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
          showTotal: (t) => `共 ${t} 条`,
          onChange: (page, size) => loadTasks(page, size, statusFilter),
        }}
        onRow={(record) => ({ onClick: () => navigate(`/tasks/${record.id}`), style: { cursor: 'pointer' } })}
      />
    </div>
  );
};

export default TaskListPage;
