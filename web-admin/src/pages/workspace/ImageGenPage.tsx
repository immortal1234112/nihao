import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
  Button,
  Card,
  Col,
  Collapse,
  Empty,
  Flex,
  Form,
  Input,
  Row,
  Segmented,
  Select,
  Slider,
  Space,
  Spin,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import {
  CopyOutlined,
  DownloadOutlined,
  FolderAddOutlined,
  SendOutlined,
  SyncOutlined,
} from '@ant-design/icons';
import { taskApi } from '@/api/task';
import { assetApi } from '@/api/asset';
import { projectApi } from '@/api/project';
import { fileApi } from '@/api/file';
import { AuthImage, downloadAuthFile } from '@/components/common/AuthMedia';
import { useAppFeedback } from '@/utils/feedback';
import { useTaskProgress, TERMINAL_STATUSES } from '@/hooks/useTaskProgress';
import { AssetRow, ProjectRow, StorageFile, TaskSubmitRequest } from '@/types/api';
import DatasetSelect from '@/components/rag/DatasetSelect';
import PromptTemplateModal from '@/components/workspace/PromptTemplateModal';
import ResultPreview, { parseResultUrls } from '@/components/common/ResultPreview';
import StatusTag from '@/components/common/StatusTag';
import ArchiveToProjectModal from '@/components/create/ArchiveToProjectModal';

const { Title, Text } = Typography;
const { TextArea } = Input;

const imageModels = [
  { value: 'qwen-image', label: '通义万相 (Qwen)', desc: '阿里云，1024×1024，写实/动漫/水彩' },
  { value: 'dalle', label: 'DALL·E 3 (OpenAI)', desc: 'OpenAI，1024×1024，高品质写实' },
  { value: 'stability', label: 'Stable Diffusion (Stability AI)', desc: '开源，1024×1024，风格全面' },
  { value: 'midjourney', label: 'Midjourney V6', desc: '艺术感强，1024×1024' },
  { value: 'ernie-image', label: '文心一格 (百度)', desc: '百度，中文优化，1024×1024' },
  { value: 'agnes-image', label: 'Agnes Image 2.1 (免费)', desc: 'Agnes AI，高信息密度，1K~4K，支持 21:9 宽幅' },
];

/**
 * AI 图片生成 — 三栏布局：
 * 左=素材与参考图（资产中心复用/项目资产选取，选定即作为图生图输入）
 * 中=结果画布（提交后原地轮询渲染，结果四操作：保存到项目/创建变体/提交审核/下载）
 * 右=参数面板（简单模式 + 高级参数折叠）
 * 支持 query 参数：?sourceAssetId=xx（复用创作）&projectId=xx（从项目进入）
 */
const ImageGenPage: React.FC = () => {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const { message } = useAppFeedback();
  const [form] = Form.useForm();

  const [loading, setLoading] = useState(false);
  const [templateOpen, setTemplateOpen] = useState(false);

  // 任务/画布状态
  const [taskId, setTaskId] = useState<string | null>(null);
  const [taskStatus, setTaskStatus] = useState<string | null>(null);
  const [resultRaw, setResultRaw] = useState<string | null>(null);
  const [archivedAssets, setArchivedAssets] = useState<AssetRow[]>([]);
  const [archiveTaskId, setArchiveTaskId] = useState<string | null>(null);

  // 素材与参考图
  const [refAssetId, setRefAssetId] = useState<string | undefined>(searchParams.get('sourceAssetId') ?? undefined);
  const [refAsset, setRefAsset] = useState<AssetRow | null>(null);
  const [libraryAssets, setLibraryAssets] = useState<AssetRow[]>([]);
  // 素材来源：资产中心 / 文件管理（文件管理里的图片选中后先导入为资产再作为参考图）
  const [sourceTab, setSourceTab] = useState<'asset' | 'files'>('asset');
  const [managedFiles, setManagedFiles] = useState<StorageFile[]>([]);
  const [importing, setImporting] = useState(false);

  // 项目归属
  const [projectId, setProjectId] = useState<string | undefined>(searchParams.get('projectId') ?? undefined);
  const [projectOptions, setProjectOptions] = useState<ProjectRow[]>([]);

  // ===== 素材库加载 =====
  useEffect(() => {
    assetApi
      .list({ assetType: 'IMAGE', size: 60 })
      .then((res) => setLibraryAssets(res.data.data.records))
      .catch(() => {});
    projectApi
      .list({ page: 1, size: 100, scope: 'mine' })
      .then((res) => setProjectOptions(res.data.data.records))
      .catch(() => {});
    // 文件管理里的图片（参考图用）
    fileApi
      .list({ page: 1, size: 100 })
      .then((res) => setManagedFiles(res.data.data.records.filter((f) => f.contentType.startsWith('image/'))))
      .catch(() => {});
  }, []);

  /** 选择文件管理里的图片：先导入为资产（IMPORT），再作为参考图 */
  const pickManagedFile = async (f: StorageFile) => {
    setImporting(true);
    try {
      const res = await assetApi.create({
        title: f.originalName,
        fileId: f.id,
        derivationType: 'IMPORT',
        assetType: 'IMAGE',
      });
      setRefAssetId(res.data.data.id);
      message.success(`已导入「${f.originalName}」作为参考图`);
    } catch {
      message.error('导入文件失败，请重试');
    } finally {
      setImporting(false);
    }
  };

  useEffect(() => {
    if (!refAssetId) {
      setRefAsset(null);
      return;
    }
    assetApi
      .detail(refAssetId)
      .then((res) => setRefAsset(res.data.data))
      .catch(() => setRefAsset(null));
  }, [refAssetId]);

  // ===== 结果刷新（WS 驱动 + 终态拉归档资产）=====
  const loadResult = useCallback(async () => {
    if (!taskId) return;
    try {
      const res = await taskApi.getResult(taskId);
      const data = res.data.data;
      setTaskStatus(data.status);
      if (data.result) setResultRaw(JSON.stringify(data.result));
      if (TERMINAL_STATUSES.has(data.status) && data.status === 'DONE') {
        const assetsRes = await assetApi.listByTask(taskId);
        setArchivedAssets(assetsRes.data.data);
        // 全局入口生成（未挂项目）且已归档 → 弹归档弹窗
        if (assetsRes.data.data.length > 0 && !projectId) {
          setArchiveTaskId(taskId);
        }
      }
    } catch {
      /* silent */
    }
  }, [taskId, projectId]);

  useTaskProgress(taskId ?? undefined, () => {
    loadResult();
  });

  useEffect(() => {
    if (taskId) loadResult();
  }, [taskId, loadResult]);

  // ===== 提交 =====
  const onFinish = async (values: Record<string, unknown>) => {
    setLoading(true);
    try {
      const base: TaskSubmitRequest = {
        prompt: values.prompt as string,
        name: (values.name as string | undefined) || undefined,
        modelProvider: values.modelProvider as string,
        size: values.size as string,
        count: values.count as number,
        style: values.style as string,
        format: values.format as string,
        quality: values.quality as string,
        negativePrompt: values.negativePrompt as string,
        aspectRatio: values.aspectRatio as string,
        datasetId: values.datasetId as string | undefined,
        ragTopK: 3,
        projectId,
        sourceAssetId: refAssetId,
        batchGroupId: (values.count as number) > 1 ? crypto.randomUUID() : undefined,
      };
      const res = await taskApi.submitImage(base);
      message.success('任务提交成功，结果将显示在画布');
      setTaskId(res.data.data?.task?.id ?? null);
      setTaskStatus(null);
      setResultRaw(null);
      setArchivedAssets([]);
    } finally {
      setLoading(false);
    }
  };

  const resultUrls = useMemo(() => parseResultUrls(resultRaw), [resultRaw]);

  /** 创建变体：同参数 + 已归档资产作为输入，生成兄弟节点 */
  const createVariant = async () => {
    const source = archivedAssets[0];
    if (!source) {
      message.warning('归档资产尚未就绪，稍后再试');
      return;
    }
    setRefAssetId(source.id);
    const values = form.getFieldsValue();
    await onFinish(values);
  };

  /** 提交审核：对本次归档资产逐个发起 */
  const submitForReview = async () => {
    if (archivedAssets.length === 0) {
      message.warning('归档资产尚未就绪，稍后再试');
      return;
    }
    for (const a of archivedAssets) {
      await assetApi.submitReview(a.id);
    }
    message.success(`已提交 ${archivedAssets.length} 个资产审核`);
  };

  return (
    <div>
      <Title level={4} style={{ marginBottom: 16 }}>AI 图片生成</Title>

      <Row gutter={16}>
        {/* ===== 左栏：素材与参考图 ===== */}
        <Col span={5}>
          <Card size="small" title="素材与参考图" style={{ height: '100%' }}>
            <Flex vertical gap={12}>
              {refAsset ? (
                <div>
                  <Text type="secondary" style={{ fontSize: 12 }}>当前参考图（图生图）</Text>
                  <div style={{ marginTop: 4 }}>
                    {refAsset.file?.url && (
                      <AuthImage
                        src={refAsset.file.url}
                        alt={refAsset.title}
                        style={{ width: '100%', borderRadius: 8, maxHeight: 160, objectFit: 'cover' }}
                      />
                    )}
                    <Text strong style={{ fontSize: 12, display: 'block' }} ellipsis>
                      {refAsset.title}
                    </Text>
                    <Button size="small" type="text" onClick={() => setRefAssetId(undefined)}>
                      移除参考图
                    </Button>
                  </div>
                </div>
              ) : (
                <Text type="secondary" style={{ fontSize: 12 }}>
                  选择一张图片作为参考图（可选），将触发图生图
                </Text>
              )}
              <Segmented
                size="small"
                value={sourceTab}
                onChange={(v) => setSourceTab(v as 'asset' | 'files')}
                options={[
                  { label: '资产中心', value: 'asset' },
                  { label: '文件管理', value: 'files' },
                ]}
                style={{ marginBottom: 8 }}
              />
              <div style={{ maxHeight: 420, overflowY: 'auto' }}>
                {sourceTab === 'asset' ? (
                  <>
                    <Flex gap={8} wrap>
                      {libraryAssets.map((a) => (
                        <Tooltip key={a.id} title={a.title}>
                          <div
                            onClick={() => setRefAssetId(a.id)}
                            style={{
                              width: 56,
                              height: 56,
                              borderRadius: 6,
                              overflow: 'hidden',
                              cursor: 'pointer',
                              border: refAssetId === a.id ? '2px solid #1677ff' : '1px solid #f0f0f0',
                            }}
                          >
                            {a.file?.url ? (
                              <AuthImage src={a.file.url} alt={a.title} style={{ width: '100%', height: '100%', objectFit: 'cover' }} preview={false} />
                            ) : (
                              <div style={{ width: '100%', height: '100%', background: '#fafafa' }} />
                            )}
                          </div>
                        </Tooltip>
                      ))}
                    </Flex>
                    {libraryAssets.length === 0 && (
                      <Text type="secondary" style={{ fontSize: 12 }}>暂无素材，生成或上传后可在资产中心看到</Text>
                    )}
                  </>
                ) : (
                  <>
                    <Flex gap={8} wrap>
                      {managedFiles.map((f) => (
                        <Tooltip key={f.id} title={`${f.originalName}（点击导入为参考图）`}>
                          <div
                            onClick={() => pickManagedFile(f)}
                            style={{
                              width: 56,
                              height: 56,
                              borderRadius: 6,
                              overflow: 'hidden',
                              cursor: 'pointer',
                              border: '1px solid #f0f0f0',
                              opacity: importing ? 0.5 : 1,
                            }}
                          >
                            <AuthImage
                              src={`/api/v1/files/${f.id}/download`}
                              alt={f.originalName}
                              style={{ width: '100%', height: '100%', objectFit: 'cover' }}
                              preview={false}
                            />
                          </div>
                        </Tooltip>
                      ))}
                    </Flex>
                    {managedFiles.length === 0 && (
                      <Text type="secondary" style={{ fontSize: 12 }}>
                        文件管理里还没有图片，去「文件管理」页上传后即可在这里选用
                      </Text>
                    )}
                  </>
                )}
              </div>
            </Flex>
          </Card>
        </Col>

        {/* ===== 中栏：结果画布 ===== */}
        <Col span={13}>
          <Card
            size="small"
            title={
              <Flex align="center" gap={8}>
                <span>结果画布</span>
                {taskStatus && <StatusTag status={taskStatus} />}
              </Flex>
            }
            extra={
              taskId && (
                <Button size="small" type="link" onClick={() => navigate(`/tasks/${taskId}`)}>
                  任务详情
                </Button>
              )
            }
            style={{ height: '100%' }}
          >
            {loading ? (
              <div style={{ textAlign: 'center', padding: 60 }}>
                <Spin tip="提交中…" />
              </div>
            ) : resultUrls.length > 0 ? (
              <Flex vertical gap={12}>
                <ResultPreview raw={resultRaw} />
                <Flex gap={8} wrap>
                  <Button
                    size="small"
                    type="primary"
                    icon={<FolderAddOutlined />}
                    onClick={() => setArchiveTaskId(taskId)}
                  >
                    保存到项目
                  </Button>
                  <Button size="small" icon={<SyncOutlined />} onClick={createVariant}>
                    创建变体
                  </Button>
                  <Button size="small" onClick={submitForReview}>
                    提交审核
                  </Button>
                  {resultUrls.map((url) => (
                    <Button
                      key={url}
                      size="small"
                      icon={<DownloadOutlined />}
                      onClick={async () => {
                        const ok = await downloadAuthFile(url, `generated-${Date.now()}.png`);
                        if (ok) message.success('下载完成');
                        else message.error('下载失败，请重试');
                      }}
                    >
                      下载
                    </Button>
                  ))}
                </Flex>
                {archivedAssets.length > 0 && (
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    已自动归档 {archivedAssets.length} 个资产{projectId ? '到所选项目' : '到资产中心'}
                  </Text>
                )}
              </Flex>
            ) : taskStatus && !TERMINAL_STATUSES.has(taskStatus) ? (
              <div style={{ textAlign: 'center', padding: 60 }}>
                <Spin tip="生成中，完成后自动显示…" />
              </div>
            ) : (
              <Empty
                description="提交生成后，结果将在此展示"
                style={{ padding: 60 }}
              >
                <Text type="secondary" style={{ fontSize: 12 }}>
                  结果完成后自动归档为资产，可保存到项目 / 提交审核
                </Text>
              </Empty>
            )}
          </Card>
        </Col>

        {/* ===== 右栏：参数面板 ===== */}
        <Col span={6}>
          <Card size="small" title="参数面板">
            <Form
              form={form}
              layout="vertical"
              onFinish={onFinish}
              initialValues={{ count: 1, size: '1024x1024', format: 'png', quality: 'standard', aspectRatio: '1:1' }}
            >
              <Form.Item
                name="prompt"
                label={
                  <Space size={8}>
                    <span>提示词</span>
                    <Button type="link" size="small" icon={<CopyOutlined />} onClick={() => setTemplateOpen(true)} style={{ padding: 0, height: 'auto' }}>
                      模板
                    </Button>
                  </Space>
                }
                rules={[{ required: true, message: '请输入提示词' }]}
              >
                <TextArea rows={4} placeholder="描述你想要生成的图片内容..." maxLength={2000} showCount />
              </Form.Item>
              <Form.Item name="name" label="作品名称（可选）">
                <Input placeholder="给作品起个名字，画廊和项目中会用它展示" maxLength={50} />
              </Form.Item>
              <Form.Item name="modelProvider" label="模型选择" initialValue="qwen-image">
                <Select
                  placeholder="选择图片生成模型"
                  options={imageModels.map((m) => ({ value: m.value, label: m.label }))}
                />
              </Form.Item>
              <Form.Item name="projectId_display" label="保存到项目（可选）">
                <Select
                  allowClear
                  placeholder="选择项目，结果自动归档到项目"
                  value={projectId}
                  onChange={setProjectId}
                  options={projectOptions.map((p) => ({ value: p.id, label: p.name }))}
                />
              </Form.Item>
              <Row gutter={8}>
                <Col span={12}>
                  <Form.Item name="style" label="风格">
                    <Select allowClear placeholder="风格" options={[
                      { value: 'realistic', label: '写实' },
                      { value: 'anime', label: '动漫' },
                      { value: 'oil_painting', label: '油画' },
                      { value: 'watercolor', label: '水彩' },
                    ]} />
                  </Form.Item>
                </Col>
                <Col span={12}>
                  <Form.Item name="aspectRatio" label="宽高比">
                    <Select options={[
                      { value: '1:1', label: '1:1' },
                      { value: '16:9', label: '16:9' },
                      { value: '9:16', label: '9:16' },
                      { value: '4:3', label: '4:3' },
                    ]} />
                  </Form.Item>
                </Col>
              </Row>
              <Form.Item name="count" label="生成数量（>1 自动归为同批次）">
                <Slider min={1} max={10} marks={{ 1: '1', 5: '5', 10: '10' }} />
              </Form.Item>

              {/* 高级参数 */}
              <Collapse
                ghost
                items={[
                  {
                    key: 'advanced',
                    label: <Text type="secondary" style={{ fontSize: 12 }}>高级参数</Text>,
                    children: (
                      <>
                        <Form.Item name="negativePrompt" label="负面提示词">
                          <TextArea rows={2} placeholder="不希望出现的元素（可选）" maxLength={500} />
                        </Form.Item>
                        <Row gutter={8}>
                          <Col span={12}>
                            <Form.Item name="size" label="尺寸">
                              <Select options={[
                                { value: '512x512', label: '512×512' },
                                { value: '768x768', label: '768×768' },
                                { value: '1024x1024', label: '1024×1024' },
                                { value: '2048x2048', label: '2048×2048' },
                              ]} />
                            </Form.Item>
                          </Col>
                          <Col span={12}>
                            <Form.Item name="format" label="输出格式">
                              <Select options={[
                                { value: 'png', label: 'PNG' },
                                { value: 'jpeg', label: 'JPEG' },
                                { value: 'webp', label: 'WebP' },
                              ]} />
                            </Form.Item>
                          </Col>
                        </Row>
                        <Form.Item name="quality" label="画质">
                          <Select options={[
                            { value: 'standard', label: '标准' },
                            { value: 'hd', label: '高清' },
                          ]} />
                        </Form.Item>
                        <Form.Item name="datasetId" label="知识库增强（可选）">
                          <DatasetSelect />
                        </Form.Item>
                      </>
                    ),
                  },
                ]}
              />

              <Form.Item style={{ marginBottom: 0 }}>
                <Button type="primary" htmlType="submit" icon={<SendOutlined />} loading={loading} block>
                  生成
                </Button>
              </Form.Item>
            </Form>
          </Card>
        </Col>
      </Row>

      <PromptTemplateModal
        open={templateOpen}
        engineType="IMAGE"
        onClose={() => setTemplateOpen(false)}
        onSelect={(t) => {
          form.setFieldsValue({ prompt: t.prompt, negativePrompt: t.negativePrompt ?? '' });
          message.success(`已应用模板：${t.name}`);
        }}
      />

      <ArchiveToProjectModal
        taskId={archiveTaskId}
        onClose={() => setArchiveTaskId(null)}
      />
    </div>
  );
};

export default ImageGenPage;
