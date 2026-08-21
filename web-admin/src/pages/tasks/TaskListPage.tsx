import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Table, Select, Button, Space, Typography, Popconfirm, Tooltip, Flex } from 'antd';
import { ReloadOutlined, EyeOutlined, StopOutlined, DeleteOutlined, ClearOutlined } from '@ant-design/icons';
import { taskApi } from '@/api/task';
import { AgentTask } from '@/types/api';
import { usePagination } from '@/hooks/usePagination';
import { useAppFeedback } from '@/utils/feedback';
import StatusTag from '@/components/common/StatusTag';
import dayjs from 'dayjs';
import styles from './TaskListPage.module.css';

const { Title, Text } = Typography;

const workflowTypeMap: Record<string, string> = { SINGLE: '单任务', PIPELINE: '流水线', BATCH: '批量' };

const TaskListPage: React.FC = () => {
  const [statusFilter, setStatusFilter] = useState<string | undefined>(undefined);
  const navigate = useNavigate();
  const { message } = useAppFeedback();

  const table = usePagination<AgentTask>(
    async (page, size) => {
      const res = await taskApi.list({ page, size, status: statusFilter });
      return res.data.data;
    },
    [statusFilter]
  );

  const handleCancel = async (id: string) => {
    try {
      await taskApi.cancel(id);
      message.success('任务已取消');
      table.refresh();
    } catch {
      // 失败原因已由拦截器提示，这里不再重复弹
    }
  };

  const handleDelete = async (id: string) => {
    try {
      await taskApi.delete(id);
      message.success('任务已删除');
      table.refresh();
    } catch {
      // 失败原因已由拦截器提示，这里不再重复弹
    }
  };

  /** 分页遍历删除全部失败任务（上限 10 页，防意外死循环） */
  const handleClearFailed = async () => {
    try {
      let deleted = 0;
      for (let p = 1; p <= 10; p++) {
        const res = await taskApi.list({ page: p, size: 100, status: 'FAILED' });
        const records = res.data.data.records;
        if (records.length === 0) break;
        for (const t of records) {
          await taskApi.delete(t.id).catch(() => {});
        }
        deleted += records.length;
        if (records.length < 100) break;
      }
      message.success(`已清除 ${deleted} 个失败任务`);
      table.refresh();
    } catch {
      // 失败原因已由拦截器提示，这里不再重复弹
    }
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
      render: (v: string) => <span>{workflowTypeMap[v] || v}</span>,
    },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 90,
      render: (s: string) => <StatusTag status={s} />,
    },
    {
      title: 'Prompt', dataIndex: 'requestParams', key: 'prompt', ellipsis: true,
      render: (v: string) => {
        try { return JSON.parse(v).prompt?.slice(0, 60) || '-'; } catch { return '-'; }
      },
    },
    {
      title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 160,
      render: (v: string) => (v ? dayjs(v).format('MM-DD HH:mm:ss') : '-'),
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
    <div className={styles.page}>
      <Flex justify="space-between" align="center" wrap gap={12} className={styles.pageHeader}>
        <div>
          <Title level={3} className={styles.pageTitle}>任务管理</Title>
          <Text type="secondary" className={styles.pageHint}>查看生成任务状态与执行结果</Text>
        </div>
        <Space wrap className={styles.headerActions}>
          <Select
            className={styles.statusSelect}
            allowClear placeholder="全部状态" value={statusFilter}
            onChange={(v) => setStatusFilter(v)}
            options={[
              { value: 'PENDING', label: '等待中' },
              { value: 'PROCESSING', label: '处理中' },
              { value: 'DONE', label: '已完成' },
              { value: 'FAILED', label: '失败' },
              { value: 'CANCELLED', label: '已取消' },
            ]}
          />
          <Button icon={<ReloadOutlined />} onClick={table.refresh}>
            刷新
          </Button>
          <Popconfirm title="确定清除所有失败任务？" onConfirm={handleClearFailed}>
            <Button icon={<ClearOutlined />} danger>清除失败</Button>
          </Popconfirm>
        </Space>
      </Flex>
      <div className={styles.tableShell}>
        <Table
          className={styles.taskTable}
          dataSource={table.records}
          columns={columns}
          rowKey="id"
          loading={table.loading}
          size="middle"
          pagination={table.pagination}
          onRow={(record) => ({
            onClick: () => navigate(`/tasks/${record.id}`),
            className: styles.clickableRow,
          })}
          locale={{ emptyText: '暂无任务数据' }}
        />
      </div>
    </div>
  );
};

export default TaskListPage;
