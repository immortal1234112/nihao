import { useState } from 'react';
import { Button, DatePicker, Flex, Input, Select, Table, Tag, Tooltip, Typography } from 'antd';
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import dayjs, { Dayjs } from 'dayjs';
import { auditApi } from '@/api/audit';
import { AuditLog } from '@/types/api';
import { usePagination } from '@/hooks/usePagination';

const { Title, Text } = Typography;

/** 常用操作枚举（与 AuditLogAspect 的 action 对齐） */
const COMMON_ACTIONS = [
  'LOGIN', 'LOGOUT', 'SUBMIT_IMAGE_TASK', 'SUBMIT_VIDEO_TASK', 'SUBMIT_BATCH_TASK',
  'SUBMIT_WORKFLOW_TASK', 'DELETE_TASK', 'CANCEL_TASK', 'DOWNLOAD_RESULT',
  'CREATE_PROMPT_TEMPLATE', 'UPDATE_PROMPT_TEMPLATE', 'DELETE_PROMPT_TEMPLATE',
];

/** 审计日志页 — 管理后台：操作审计查询（SUPER_ADMIN 全平台 / TENANT_ADMIN 本租户） */
const AuditLogPage: React.FC = () => {
  const [filters, setFilters] = useState<{
    userId?: string;
    action?: string;
    result?: string;
    range?: [Dayjs, Dayjs] | null;
  }>({});

  const table = usePagination<AuditLog>(
    async (page, size) => {
      const res = await auditApi.list({
        page,
        size,
        userId: filters.userId || undefined,
        action: filters.action || undefined,
        result: filters.result || undefined,
        startTime: filters.range?.[0] ? filters.range[0].format('YYYY-MM-DD HH:mm:ss') : undefined,
        endTime: filters.range?.[1] ? filters.range[1].format('YYYY-MM-DD HH:mm:ss') : undefined,
      });
      return res.data.data;
    },
    [filters]
  );

  const columns = [
    {
      title: '时间', dataIndex: 'actionTime', key: 'actionTime', width: 170,
      render: (v: string) => (v ? dayjs(v).format('YYYY-MM-DD HH:mm:ss') : '-'),
    },
    {
      title: '用户ID', dataIndex: 'userId', key: 'userId', width: 110,
      render: (v: string) => (
        <Tooltip title={v}>
          <Text code style={{ fontSize: 12 }}>{v.slice(-8)}</Text>
        </Tooltip>
      ),
    },
    { title: '租户ID', dataIndex: 'tenantId', key: 'tenantId', width: 110, render: (v: string) => <Text type="secondary" style={{ fontSize: 12 }}>{v?.slice(-8) ?? '-'}</Text> },
    {
      title: '操作', dataIndex: 'action', key: 'action', width: 190,
      render: (v: string) => <Tag color="geekblue">{v}</Tag>,
    },
    {
      title: '结果', dataIndex: 'result', key: 'result', width: 90,
      render: (v: string) => (
        <Tag color={v === 'SUCCESS' ? 'green' : 'red'}>{v === 'SUCCESS' ? '成功' : '失败'}</Tag>
      ),
    },
    {
      title: '详情', dataIndex: 'detail', key: 'detail', ellipsis: true,
      render: (v: string | null) => v ? <Text type="secondary">{v}</Text> : '-',
    },
    { title: 'IP', dataIndex: 'ip', key: 'ip', width: 130, render: (v: string | null) => v ?? '-' },
  ];

  return (
    <div>
      <Flex justify="space-between" align="center" style={{ marginBottom: 16 }}>
        <Title level={4} style={{ margin: 0 }}>审计日志</Title>
        <Button icon={<ReloadOutlined />} onClick={table.refresh}>刷新</Button>
      </Flex>

      <Flex wrap gap={8} align="center" style={{ marginBottom: 16 }}>
        <Input.Search
          placeholder="用户ID"
          allowClear
          style={{ width: 200 }}
          onSearch={(v) => setFilters((f) => ({ ...f, userId: v || undefined }))}
        />
        <Select
          allowClear
          placeholder="操作类型"
          style={{ width: 200 }}
          options={COMMON_ACTIONS.map((a) => ({ value: a, label: a }))}
          onChange={(v) => setFilters((f) => ({ ...f, action: v || undefined }))}
        />
        <Select
          allowClear
          placeholder="结果"
          style={{ width: 100 }}
          options={[
            { value: 'SUCCESS', label: '成功' },
            { value: 'FAIL', label: '失败' },
          ]}
          onChange={(v) => setFilters((f) => ({ ...f, result: v || undefined }))}
        />
        <DatePicker.RangePicker
          showTime
          placeholder={['开始时间', '结束时间']}
          onChange={(v) =>
            setFilters((f) => ({
              ...f,
              range: (v && v[0] && v[1] ? [v[0], v[1]] : null) as [Dayjs, Dayjs] | null,
            }))
          }
        />
        <Button type="primary" icon={<SearchOutlined />} onClick={() => setFilters({ ...filters })}>
          查询
        </Button>
      </Flex>

      <Table
        dataSource={table.records}
        columns={columns}
        rowKey="id"
        loading={table.loading}
        size="middle"
        pagination={table.pagination}
        locale={{ emptyText: '暂无审计日志' }}
      />
    </div>
  );
};

export default AuditLogPage;
