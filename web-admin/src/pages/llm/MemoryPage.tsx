import { useState, useCallback, useEffect } from 'react';
import { Card, Table, Button, Tag, Typography, App } from 'antd';
import { DeleteOutlined, ReloadOutlined } from '@ant-design/icons';
import { llmApi } from '@/api/llm';
import { MemoryItem } from '@/types/api';
import type { ColumnsType } from 'antd/es/table';

const { Text } = Typography;

const MemoryPage: React.FC = () => {
  const { message } = App.useApp();
  const [records, setRecords] = useState<MemoryItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);
  const [loading, setLoading] = useState(false);

  const load = useCallback(() => {
    setLoading(true);
    llmApi.listMemories(page, size).then((res) => {
      setRecords(res.data.data.records);
      setTotal(res.data.data.pagination.total);
    }).catch(() => {
      message.error('加载记忆失败');
    }).finally(() => setLoading(false));
  }, [page, size, message]);

  useEffect(() => { load(); }, [load]);

  const handleDelete = async (item: MemoryItem) => {
    try {
      await llmApi.deleteMemory(item.id);
      message.success('记忆已删除');
      load();
    } catch {
      message.error('删除失败');
    }
  };

  const columns: ColumnsType<MemoryItem> = [
    {
      title: '记忆内容',
      dataIndex: 'content',
      key: 'content',
      render: (text: string) => <Text style={{ wordBreak: 'break-all' }}>{text}</Text>,
    },
    {
      title: '类型',
      dataIndex: 'memoryType',
      key: 'memoryType',
      width: 120,
      render: (type: string) => (
        <Tag color={type === 'PREFERENCE' ? 'purple' : 'blue'}>
          {type === 'PREFERENCE' ? '偏好' : '事实'}
        </Tag>
      ),
    },
    { title: '来源会话', dataIndex: 'sourceConversationId', key: 'sourceConversationId', width: 180, ellipsis: true },
    { title: '提取时间', dataIndex: 'createdAt', key: 'createdAt', width: 170 },
    {
      title: '操作',
      key: 'action',
      width: 90,
      render: (_, item) => (
        <Button type="text" danger size="small" icon={<DeleteOutlined />} onClick={() => handleDelete(item)}>
          删除
        </Button>
      ),
    },
  ];

  return (
    <Card
      title="长期记忆管理"
      extra={
        <Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>
      }
    >
      <div style={{ marginBottom: 12 }}>
        <Text type="secondary">
          对话中自动提取的用户事实与偏好，跨会话生效（检索注入到 AI 系统提示）。删除后不再命中。
        </Text>
      </div>
      <Table<MemoryItem>
        rowKey="id"
        columns={columns}
        dataSource={records}
        loading={loading}
        pagination={{
          current: page,
          pageSize: size,
          total,
          showSizeChanger: true,
          showTotal: (t) => `共 ${t} 条记忆`,
          onChange: (p, s) => { setPage(p); setSize(s); },
        }}
      />
    </Card>
  );
};

export default MemoryPage;
