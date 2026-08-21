import { Table, Button, Space, Popconfirm, Typography } from 'antd';
import { CheckOutlined, CloseOutlined, ReloadOutlined } from '@ant-design/icons';
import { adminApi } from '@/api/admin';
import { AgentTask } from '@/types/api';
import { usePagination } from '@/hooks/usePagination';
import { useAppFeedback } from '@/utils/feedback';
import StatusTag from '@/components/common/StatusTag';
import dayjs from 'dayjs';
import styles from './ReviewPage.module.css';

const { Title } = Typography;

const ReviewPage: React.FC = () => {
  const { message } = useAppFeedback();

  const table = usePagination<AgentTask>(
    async (page, size) => {
      const res = await adminApi.listAllTasks({ page, size, status: 'REVIEWING' });
      return res.data.data;
    },
    []
  );

  const handleReview = async (id: string, action: 'PASS' | 'REJECT') => {
    try {
      await adminApi.reviewTask(id, action);
      message.success(action === 'PASS' ? '已通过' : '已拒绝');
      table.refresh();
    } catch { /* handled */ }
  };

  const columns = [
    { title: '任务ID', dataIndex: 'id', key: 'id', width: 180, render: (v: string) => v.slice(0, 8) + '...' + v.slice(-4) },
    { title: '用户ID', dataIndex: 'userId', key: 'userId', width: 180, render: (v: string) => v.slice(0, 8) + '...' },
    { title: '类型', dataIndex: 'workflowType', key: 'type', width: 80 },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 100,
      render: (s: string) => <StatusTag status={s} />,
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
    <div className={styles.page}>
      <div className={styles.pageHeader}>
        <div>
          <Title level={3} className={styles.pageTitle}>任务审核</Title>
          <Typography.Text type="secondary" className={styles.pageHint}>处理需要人工确认的任务</Typography.Text>
        </div>
        <Button icon={<ReloadOutlined />} onClick={table.refresh}>刷新</Button>
      </div>
      <div className={styles.tableShell}>
        <Table className={styles.reviewTable} dataSource={table.records} columns={columns} rowKey="id" loading={table.loading} pagination={table.pagination} />
      </div>
    </div>
  );
};

export default ReviewPage;
