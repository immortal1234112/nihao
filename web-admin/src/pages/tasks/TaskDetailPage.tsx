import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Card, Descriptions, Tag, Steps, Button, Spin, Typography, Image, Alert, Space } from 'antd';
import { ArrowLeftOutlined, ReloadOutlined } from '@ant-design/icons';
import { taskApi } from '@/api/task';
import { TaskDetail } from '@/types/api';
import dayjs from 'dayjs';

const { Title, Paragraph } = Typography;

const statusColorMap: Record<string, string> = {
  PENDING: 'default', PROCESSING: 'processing', DONE: 'success', FAILED: 'error', SKIPPED: 'warning',
};

const TaskDetailPage: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [detail, setDetail] = useState<TaskDetail | null>(null);
  const [loading, setLoading] = useState(true);

  const load = async () => {
    setLoading(true);
    try {
      const res = await taskApi.getById(id!);
      setDetail(res.data.data);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { if (id) load(); }, [id]);

  if (loading) return <Spin size="large" style={{ display: 'block', margin: '100px auto' }} />;
  if (!detail) return <Alert type="error" message="任务不存在" />;

  const { task, steps } = detail;
  let params: Record<string, unknown> = {};
  try { params = JSON.parse(task.requestParams); } catch { /* */ }

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/tasks')}>返回列表</Button>
        <Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>
      </Space>

      <Card title={<Title level={4} style={{ margin: 0 }}>任务详情</Title>} style={{ marginBottom: 24 }}>
        <Descriptions bordered column={3} size="small">
          <Descriptions.Item label="任务ID">{task.id}</Descriptions.Item>
          <Descriptions.Item label="类型">{task.workflowType}</Descriptions.Item>
          <Descriptions.Item label="状态">
            <Tag color={statusColorMap[task.status] || 'default'}>{task.status}</Tag>
          </Descriptions.Item>
          <Descriptions.Item label="提示词" span={3}>
            <Paragraph ellipsis={{ rows: 2, expandable: true }}>{params.prompt as string || '-'}</Paragraph>
          </Descriptions.Item>
          <Descriptions.Item label="创建时间">{dayjs(task.createdAt).format('YYYY-MM-DD HH:mm:ss')}</Descriptions.Item>
          <Descriptions.Item label="更新时间">{dayjs(task.updatedAt).format('YYYY-MM-DD HH:mm:ss')}</Descriptions.Item>
          <Descriptions.Item label="完成时间">{task.completedAt ? dayjs(task.completedAt).format('YYYY-MM-DD HH:mm:ss') : '-'}</Descriptions.Item>
          <Descriptions.Item label="重试次数">{task.retryCount}</Descriptions.Item>
          <Descriptions.Item label="优先级">{task.priority}</Descriptions.Item>
          <Descriptions.Item label="错误信息">{task.errorMsg || '-'}</Descriptions.Item>
        </Descriptions>
      </Card>

      {steps && steps.length > 0 && (
        <Card title="执行步骤">
          <Steps
            direction="vertical"
            current={steps.findIndex((s) => s.status === 'PROCESSING')}
            items={steps.map((step) => ({
              title: `步骤 ${step.stepOrder}: ${step.stepType}`,
              description: (
                <div>
                  <Tag color={statusColorMap[step.status]}>{step.status}</Tag>
                  {step.startedAt && <span>开始: {dayjs(step.startedAt).format('HH:mm:ss')}</span>}
                  {step.finishedAt && <span> 完成: {dayjs(step.finishedAt).format('HH:mm:ss')}</span>}
                  {step.errorMsg && <div style={{ color: '#ff4d4f' }}>错误: {step.errorMsg}</div>}
                </div>
              ),
              status: step.status === 'FAILED' ? 'error' : step.status === 'DONE' ? 'finish' : step.status === 'PROCESSING' ? 'process' : 'wait',
            }))}
          />
        </Card>
      )}

      {task.result && (
        <Card title="生成结果" style={{ marginTop: 24 }}>
          {(() => {
            try {
              const result = JSON.parse(task.result);
              if (result.url) {
                return <Image src={result.url} alt="生成结果" style={{ maxWidth: '100%', maxHeight: 500 }} />;
              }
              if (result.videoUrl) {
                return <video src={result.videoUrl} controls style={{ maxWidth: '100%', maxHeight: 500 }} />;
              }
              return <pre>{JSON.stringify(result, null, 2)}</pre>;
            } catch {
              return <Paragraph>{task.result}</Paragraph>;
            }
          })()}
        </Card>
      )}
    </div>
  );
};

export default TaskDetailPage;
