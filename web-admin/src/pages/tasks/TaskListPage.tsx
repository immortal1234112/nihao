import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Table, Tag, Select, Button, Space, Typography, Popconfirm, message, Tooltip, Flex, Card } from 'antd';
import { ReloadOutlined, EyeOutlined, StopOutlined, DeleteOutlined, ClearOutlined } from '@ant-design/icons';
import { taskApi } from '@/api/task';
import { AgentTask } from '@/types/api';
import dayjs from 'dayjs';

const { Title, Text } = Typography;

const statusColorMap: Record<string, string> = {
  CREATED: 'default', VALIDATING: 'processing', PENDING: 'warning',
  PROCESSING: 'processing', DONE: 'success', FAILED: 'error',
  CANCELLED: 'default', REVIEWING: 'warning',
};

const statusLabelMap: Record<string, string> = {
  CREATED: '已创建', VALIDATING: '校验中', PENDING: '等待中',
  PROCESSING: '处理中', DONE: '已完成', FAILED: '失败',
  CANCELLED: '已取消', REVIEWING: '审核中',
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
    } catch { message.error('取消失败'); }
  };

  const handleDelete = async (id: string) => {
    try {
      await taskApi.delete(id);
      message.success('任务已删除');
      loadTasks(pagination.page, pagination.size, statusFilter);
    } catch { message.error('删除失败'); }
  };

  const handleClearFailed = async () => {
    try {
      const res = await taskApi.list({ page: 1, size: 100, status: 'FAILED' });
      const failedTasks = res.data.data.records;
      for (const t of failedTasks) {
        await taskApi.delete(t.id).catch(() => {});
      }
      message.success(`已清除 ${failedTasks.length} 个失败任务`);
      loadTasks(pagination.page, pagination.size, statusFilter);
    } catch { message.error('清除失败'); }
  };

  const columns = [
    {
      title: '任务ID', dataIndex: 'id', key: 'id', width: 120,
      render: (v: string) => (
        <Tooltip title={v}>
          <Text code copyable={{ text: v }} style={{ fontSize: 12 }}>{v.slice(-8)}</Text>
        </Tooltip>
      ),
    },
    {
      title: '类型', dataIndex: 'workflowType', key: 'type', width: 70,
      render: (v: string) => {
        const map: Record<string, string> = { SINGLE: '单任务', PIPELINE: '流水线', BATCH: '批量' };
        return <Tag>{map[v] || v}</Tag>;
      },
    },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 90,
      render: (s: string) => <Tag color={statusColorMap[s] || 'default'}>{statusLabelMap[s] || s}</Tag>,
    },
    {
      title: 'Prompt', dataIndex: 'requestParams', key: 'prompt', ellipsis: true,
      render: (v: string) => {
        try { return JSON.parse(v).prompt?.slice(0, 60) || '-'; } catch { return '-'; }
      },
    },
    {
      title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 160,
      render: (v: string) => v ? dayjs(v).format('MM-DD HH:mm:ss') : '-',
    },
    {
      title: '操作', key: 'actions', width: 170,
      render: (_: unknown, record: AgentTask) => (
        <Space size={0} onClick={(e) => e.stopPropagation()}>
          <Button type="link" size="small" icon={<EyeOutlined />}
            onClick={() => navigate(`/tasks/${record.id}`)}>详情</Button>
          {(record.status === 'PENDING' || record.status === 'PROCESSING') && (
            <Popconfirm title="确定取消该任务？" onConfirm={() => handleCancel(record.id)}>
              <Button type="link" size="small" icon={<StopOutlined />}>取消</Button>
            </Popconfirm>
          )}
          <Popconfirm
            title="确定删除该任务？此操作不可恢复。"
            description="删除后将同时清除关联的工作流步骤。"
            onConfirm={() => handleDelete(record.id)}
            okButtonProps={{ danger: true }}
          >
            <Button type="link" size="small" danger icon={<DeleteOutlined />}>删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Flex justify="space-between" align="center" style={{ marginBottom: 16 }}>
        <Title level={4} style={{ margin: 0 }}>任务管理</Title>
        <Space>
          <Select
            allowClear placeholder="全部状态" style={{ width: 120 }} value={statusFilter}
            onChange={(v) => setStatusFilter(v)}
            options={[
              { value: 'PENDING', label: '等待中' },
              { value: 'PROCESSING', label: '处理中' },
              { value: 'DONE', label: '已完成' },
              { value: 'FAILED', label: '失败' },
              { value: 'CANCELLED', label: '已取消' },
            ]}
          />
          <Button icon={<ReloadOutlined />} onClick={() => loadTasks(pagination.page, pagination.size, statusFilter)}>
            刷新
          </Button>
          <Popconfirm title="确定清除所有失败任务？" onConfirm={handleClearFailed}>
            <Button icon={<ClearOutlined />} danger>清除失败</Button>
          </Popconfirm>
        </Space>
      </Flex>
      <Table
        dataSource={tasks}
        columns={columns}
        rowKey="id"
        loading={loading}
        size="middle"
        pagination={{
          current: pagination.page,
          pageSize: pagination.size,
          total: pagination.total,
          showSizeChanger: true,
          showTotal: (t) => `共 ${t} 条`,
          onChange: (page, size) => loadTasks(page, size, statusFilter),
        }}
        onRow={(record) => ({
          onClick: () => navigate(`/tasks/${record.id}`),
          style: { cursor: 'pointer' },
        })}
        locale={{ emptyText: '暂无任务数据' }}
      />
    </div>
  );
};

export default TaskListPage;
