import { useCallback, useEffect, useRef, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Card, Descriptions, Button, Typography, Alert, Space, Empty, Spin } from 'antd';
import { ArrowLeftOutlined, ReloadOutlined } from '@ant-design/icons';
import type { AxiosError } from 'axios';
import { taskApi } from '@/api/task';
import { TaskDetail } from '@/types/api';
import { useTaskProgress } from '@/hooks/useTaskProgress';
import StatusTag from '@/components/common/StatusTag';
import PageLoading from '@/components/common/PageLoading';
import ResultPreview, { parseResultUrls } from '@/components/common/ResultPreview';
import dayjs from 'dayjs';

const { Title, Paragraph } = Typography;

const TaskDetailPage: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [detail, setDetail] = useState<TaskDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  /** silent=true 时失败不弹 toast（WS 兜底轮询每 10s 一次，后端宕机时会刷屏），仅保留页面错误态 */
  const load = useCallback(async (silent = false) => {
    // 静默刷新：仅首次加载显示 loading，WS 事件/手动刷新不整页闪烁
    setLoading((prev) => prev && detailRef.current === null);
    setError(null);
    try {
      const res = await taskApi.getById(id!, silent);
      setDetail(res.data.data);
    } catch (e) {
      // 请求失败保留旧数据（拦截器已提示）；无旧数据时显示错误态：
      // 业务错误（拦截器 reject 的 Error 带后端 message，如「任务不存在」）原样显示；
      // 网络错误（AxiosError）显示通用提示
      if (!detailRef.current) {
        setError((e as Error)?.message && !(e as AxiosError).isAxiosError
          ? (e as Error).message
          : '加载失败，请检查网络后重试');
      }
    } finally {
      setLoading(false);
    }
  }, [id]);

  // 供 setLoading 判断首次加载的 ref（避免闭包依赖 detail）
  const detailRef = useRef<TaskDetail | null>(null);
  detailRef.current = detail;

  useEffect(() => { if (id) load(); }, [id, load]);

  // 实时进度：WebSocket 收到状态变更事件即刷新全量详情（快照+增量，替代 3s 轮询）
  // 连接持续失败时 hook 会周期性发 RECONNECTED 伪事件，这里作为轮询兜底继续拉取快照
  // 兜底轮询走静默模式：失败不弹 toast（每 10s 一次，后端宕机时会刷屏）
  useTaskProgress(id, () => { load(true); });

  if (error) return <Alert type="error" message={error} action={<Button onClick={() => load()}>重试</Button>} />;
  if (loading && !detail) return <PageLoading />;
  if (!detail) return <Alert type="error" message="任务不存在" />;

  const { task, steps } = detail;
  // request_params 列可空；JSON.parse(null) 返回 null 不抛错，取属性会白屏，这里统一兜底为 {}
  let params: Record<string, unknown> = {};
  try { params = JSON.parse(task.requestParams || '{}') ?? {}; } catch { /* */ }

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/tasks')}>返回列表</Button>
        <Button icon={<ReloadOutlined />} onClick={() => load()}>刷新</Button>
      </Space>

      <Card title={<Title level={4} style={{ margin: 0 }}>任务详情</Title>} style={{ marginBottom: 24 }}>
        <Descriptions bordered column={3} size="small">
          <Descriptions.Item label="任务ID">{task.id}</Descriptions.Item>
          <Descriptions.Item label="类型">{task.workflowType}</Descriptions.Item>
          <Descriptions.Item label="状态">
            <StatusTag status={task.status} />
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
          {steps.map((step) => (
            <Card
              key={step.id}
              size="small"
              title={
                <Space>
                  <span>步骤 {step.stepOrder}: {step.stepType}</span>
                  <StatusTag status={step.status} />
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
              <ResultPreview raw={step.output} />
              {step.status === 'PROCESSING' && parseResultUrls(step.output).length === 0 && (
                <div style={{ textAlign: 'center', padding: 16 }}>
                  <Spin />
                </div>
              )}
            </Card>
          ))}
        </Card>
      )}

      {/* 任务级结果 */}
      {task.result && (
        <Card title="最终结果" style={{ marginTop: 24 }}>
          {parseResultUrls(task.result).length > 0 ? (
            <ResultPreview raw={task.result} />
          ) : (
            (() => {
              try {
                const result = JSON.parse(task.result);
                const meta = { ...result };
                delete meta.outputFiles;
                return (
                  <div>
                    {Object.keys(meta).length > 0 && (
                      <Descriptions size="small" column={3} bordered style={{ marginBottom: 12 }}>
                        {Object.entries(meta).map(([k, v]) => (
                          <Descriptions.Item key={k} label={k}>
                            {typeof v === 'object' ? JSON.stringify(v) : String(v)}
                          </Descriptions.Item>
                        ))}
                      </Descriptions>
                    )}
                    <Empty description="任务完成，结果见上方步骤" />
                  </div>
                );
              } catch {
                return <Paragraph>{task.result}</Paragraph>;
              }
            })()
          )}
        </Card>
      )}
    </div>
  );
};

export default TaskDetailPage;
