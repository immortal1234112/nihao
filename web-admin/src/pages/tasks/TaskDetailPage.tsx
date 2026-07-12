import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Card, Descriptions, Tag, Steps, Button, Spin, Typography, Image, Alert, Space, Row, Col, Empty, message, Tooltip } from 'antd';
import { ArrowLeftOutlined, ReloadOutlined, DownloadOutlined } from '@ant-design/icons';
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

  const downloadFile = async (url: string) => {
    const key = 'file-download';
    message.loading({ content: '下载中...', key });
    try {
      const token = localStorage.getItem('access_token');
      const res = await fetch(`/api/v1/tasks/download?url=${encodeURIComponent(url)}`, {
        headers: token ? { Authorization: `Bearer ${token}` } : {},
      });
      if (!res.ok) throw new Error('download failed');
      const blob = await res.blob();
      const blobUrl = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = blobUrl;
      const isVideo = url.includes('.mp4') || url.includes('video') || blob.type.startsWith('video/');
      a.download = isVideo ? `generated-${Date.now()}.mp4` : `generated-${Date.now()}.png`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(blobUrl);
      message.success({ content: '下载完成', key });
    } catch {
      message.error({ content: '下载失败，请重试', key });
    }
  };

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

  // 处理中自动刷新
  useEffect(() => {
    if (!detail?.task || (detail.task.status !== 'CREATED' && detail.task.status !== 'VALIDATING'
        && detail.task.status !== 'PENDING' && detail.task.status !== 'PROCESSING' && detail.task.status !== 'REVIEWING'))
      return;
    const timer = setInterval(load, 3000);
    return () => clearInterval(timer);
  }, [detail?.task?.status]);

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

      {/* 步骤 + 内嵌结果 */}
      {steps && steps.length > 0 && (
        <Card title="执行步骤与结果" style={{ marginBottom: 24 }}>
          {steps.map((step) => {
            let stepImages: string[] = [];
            try {
              if (step.output) {
                const out = JSON.parse(step.output);
                if (out.outputFiles) {
                  stepImages = out.outputFiles
                    .filter((f: { url?: string }) => f.url)
                    .map((f: { url: string }) => f.url);
                } else if (out.url) {
                  stepImages = [out.url];
                } else if (out.videoUrl) {
                  stepImages = [out.videoUrl];
                }
              }
            } catch { /* */ }

            return (
              <Card
                key={step.id}
                size="small"
                title={
                  <Space>
                    <span>步骤 {step.stepOrder}: {step.stepType}</span>
                    <Tag color={statusColorMap[step.status] || 'default'}>{step.status}</Tag>
                  </Space>
                }
                style={{ marginBottom: 12 }}
                extra={
                  <span style={{ fontSize: 12, color: '#999' }}>
                    {step.startedAt && `开始 ${dayjs(step.startedAt).format('HH:mm:ss')}`}
                    {step.finishedAt && ` → 完成 ${dayjs(step.finishedAt).format('HH:mm:ss')}`}
                  </span>
                }
              >
                {step.errorMsg && <Alert type="error" message={step.errorMsg} style={{ marginBottom: 12 }} />}
                {stepImages.length > 0 && (
                  <Row gutter={[8, 8]}>
                    {stepImages.map((url, i) => (
                      <Col key={i} xs={24} sm={12} md={8} lg={6}>
                        <div style={{ textAlign: 'center' }}>
                          {url.includes('.mp4') || url.includes('video') ? (
                            <video src={url} controls style={{ width: '100%', borderRadius: 8 }} />
                          ) : (
                            <Image src={url} alt={`生成结果 ${i + 1}`} style={{ borderRadius: 8 }}
                              fallback="data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=" />
                          )}
                          <br />
                          <Tooltip title="下载到本地">
                            <Button type="link" size="small" icon={<DownloadOutlined />}
                              onClick={(e) => { e.stopPropagation(); downloadFile(url); }}>
                              下载
                            </Button>
                          </Tooltip>
                        </div>
                      </Col>
                    ))}
                  </Row>
                )}
                {step.status === 'PROCESSING' && !stepImages.length && (
                  <Spin tip="处理中..." />
                )}
              </Card>
            );
          })}
        </Card>
      )}

      {/* 任务级结果 */}
      {task.result && (
        <Card title="最终结果" style={{ marginTop: 24 }}>
          {(() => {
            try {
              const result = JSON.parse(task.result);
              const allImages: string[] = [];

              // 解析 outputFiles 数组
              if (result.outputFiles && Array.isArray(result.outputFiles)) {
                result.outputFiles
                  .filter((f: { url?: string }) => f.url)
                  .forEach((f: { url: string }) => allImages.push(f.url));
              }
              // 直接 url
              if (result.url) allImages.push(result.url);
              // 视频
              if (result.videoUrl) allImages.push(result.videoUrl);

              if (allImages.length > 0) {
                return (
                  <Row gutter={[12, 12]}>
                    {allImages.map((url, i) => (
                      <Col key={i} xs={24} sm={12} md={8} lg={6}>
                        <div style={{ textAlign: 'center' }}>
                          {url.includes('.mp4') || url.includes('video') ? (
                            <video src={url} controls style={{ width: '100%', borderRadius: 8 }} />
                          ) : (
                            <Image src={url} alt={`结果 ${i + 1}`} style={{ borderRadius: 8 }}
                              fallback="data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=" />
                          )}
                          <br />
                          <Tooltip title="下载到本地">
                            <Button type="link" size="small" icon={<DownloadOutlined />}
                              onClick={(e) => { e.stopPropagation(); downloadFile(url); }}>
                              下载
                            </Button>
                          </Tooltip>
                        </div>
                      </Col>
                    ))}
                  </Row>
                );
              }

              // 元信息
              const meta = { ...result };
              delete meta.outputFiles;
              return (
                <div>
                  {Object.keys(meta).length > 0 && (
                    <Descriptions size="small" column={3} bordered style={{ marginBottom: 12 }}>
                      {Object.entries(meta).map(([k, v]) => (
                        <Descriptions.Item key={k} label={k}>{typeof v === 'object' ? JSON.stringify(v) : String(v)}</Descriptions.Item>
                      ))}
                    </Descriptions>
                  )}
                  <Empty description="任务完成，结果见上方步骤" />
                </div>
              );
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
