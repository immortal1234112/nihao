import { useCallback, useEffect, useRef, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Card, Table, Button, Upload, Typography, Popconfirm, Space,
  Tag, Drawer, Input, Slider, Alert, Spin, Progress, Row, Col,
} from 'antd';
import {
  ArrowLeftOutlined, UploadOutlined, ReloadOutlined, EyeOutlined, DeleteOutlined,
} from '@ant-design/icons';
import { ragApi } from '@/api/rag';
import { RagDataset, RagDocument, RagChunk, RagSearchResult } from '@/types/api';
import { useAppFeedback } from '@/utils/feedback';
import styles from './DatasetDetailPage.module.css';

const { Title, Text } = Typography;
const { TextArea } = Input;

const statusMap: Record<string, { color: string; label: string }> = {
  PARSING: { color: 'gold', label: '解析中' },
  READY: { color: 'green', label: '已完成' },
  FAILED: { color: 'red', label: '失败' },
};

const DatasetDetailPage: React.FC = () => {
  const { id = '' } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [dataset, setDataset] = useState<RagDataset | null>(null);
  const [documents, setDocuments] = useState<RagDocument[]>([]);
  const [loading, setLoading] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [chunkDrawer, setChunkDrawer] = useState<{ open: boolean; docName: string; chunks: RagChunk[]; loading: boolean }>({
    open: false, docName: '', chunks: [], loading: false,
  });
  const [searchQuery, setSearchQuery] = useState('');
  const [searchTopK, setSearchTopK] = useState(3);
  const [searchResult, setSearchResult] = useState<RagSearchResult | null>(null);
  const [searching, setSearching] = useState(false);
  const hasParsingRef = useRef(false);
  const { message } = useAppFeedback();

  const loadDataset = useCallback(async () => {
    try {
      const res = await ragApi.getDataset(id);
      setDataset(res.data.data);
    } catch { /* 拦截器已提示 */ }
  }, [id]);

  const loadDocuments = useCallback(async () => {
    setLoading(true);
    try {
      const res = await ragApi.listDocuments(id, { page: 1, size: 100 });
      setDocuments(res.data.data.records);
      hasParsingRef.current = res.data.data.records.some((d) => d.status === 'PARSING');
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => { loadDataset(); loadDocuments(); }, [loadDataset, loadDocuments]);

  // PARSING 文档存在时轮询状态
  const hasParsing = documents.some((d) => d.status === 'PARSING');
  useEffect(() => {
    if (!hasParsing) return;
    const timer = setInterval(() => {
      loadDocuments();
      if (!hasParsingRef.current) clearInterval(timer);
    }, 3000);
    return () => clearInterval(timer);
  }, [hasParsing, loadDocuments]);

  const handleUpload = async (file: File) => {
    setUploading(true);
    try {
      await ragApi.uploadDocument(id, file);
      message.success('上传成功，开始解析');
      await loadDocuments();
      await loadDataset();
    } catch {
      /* 拦截器已提示 */
    } finally {
      setUploading(false);
    }
    return false; // 阻止 antd 默认上传
  };

  const handleDeleteDoc = async (docId: string) => {
    try {
      await ragApi.deleteDocument(id, docId);
      message.success('文档已删除');
      await loadDocuments();
      await loadDataset();
    } catch { /* 拦截器已提示 */ }
  };

  const handleReparse = async (docId: string) => {
    try {
      await ragApi.reparse(docId);
      message.success('已重新解析');
      await loadDocuments();
    } catch { /* 拦截器已提示 */ }
  };

  const handleShowChunks = async (doc: RagDocument) => {
    setChunkDrawer({ open: true, docName: doc.fileName, chunks: [], loading: true });
    try {
      const res = await ragApi.listChunks(doc.id, { page: 1, size: 100 });
      setChunkDrawer((prev) => ({ ...prev, chunks: res.data.data.records, loading: false }));
    } catch {
      setChunkDrawer((prev) => ({ ...prev, loading: false }));
    }
  };

  const handleSearch = async () => {
    if (!searchQuery.trim()) {
      message.warning('请输入检索内容');
      return;
    }
    setSearching(true);
    try {
      const res = await ragApi.search({ datasetId: id, query: searchQuery.trim(), topK: searchTopK });
      setSearchResult(res.data.data);
    } catch { /* 拦截器已提示 */ }
    finally { setSearching(false); }
  };

  const docColumns = [
    { title: '文件名', dataIndex: 'fileName', ellipsis: true },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      render: (status: string) => (
        <Space>
          <Tag color={statusMap[status]?.color || 'default'}>{statusMap[status]?.label || status}</Tag>
          {status === 'PARSING' && <Progress type="circle" size={16} percent={100} format={() => ''} />}
        </Space>
      ),
    },
    { title: '分块数', dataIndex: 'chunkCount', width: 80 },
    { title: '大小', dataIndex: 'fileSize', width: 90, render: (v: number) => `${(v / 1024).toFixed(1)} KB` },
    {
      title: '失败原因',
      dataIndex: 'errorMsg',
      ellipsis: true,
      render: (v: string | null) => v ? <Text type="danger" style={{ fontSize: 12 }}>{v}</Text> : '-',
    },
    {
      title: '操作',
      width: 220,
      render: (_: unknown, record: RagDocument) => (
        <Space>
          <Button type="link" size="small" icon={<EyeOutlined />} onClick={() => handleShowChunks(record)}>
            分块
          </Button>
          {record.status === 'FAILED' && (
            <Button type="link" size="small" icon={<ReloadOutlined />} onClick={() => handleReparse(record.id)}>
              重试
            </Button>
          )}
          <Popconfirm title="删除该文档及全部分块？" onConfirm={() => handleDeleteDoc(record.id)}>
            <Button type="link" size="small" danger icon={<DeleteOutlined />}>删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div className={styles.page}>
      <div className={styles.pageHeader}>
        <Space align="center">
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/rag/datasets')}>返回</Button>
          <div>
            <Title level={3} className={styles.pageTitle}>{dataset?.name || '知识库详情'}</Title>
            {dataset && (
              <Text type="secondary" className={styles.pageHint}>
                {dataset.embeddingModel} · {dataset.dimension} 维 · {dataset.docCount} 文档 / {dataset.chunkCount} 分块
              </Text>
            )}
          </div>
        </Space>
      </div>

      <Row gutter={[16, 16]} className={styles.contentGrid}>
        <Col flex="1 1 680px">
        <Card className={styles.panel} title="文档管理" extra={
          <Upload beforeUpload={handleUpload} showUploadList={false} accept=".txt,.md">
            <Button type="primary" icon={<UploadOutlined />} loading={uploading}>上传文档</Button>
          </Upload>
        }>
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 12 }}
            message="v1 仅支持 txt/md 纯文本（≤2MB），上传后自动解析分块与向量化"
          />
          <Table
            className={styles.documentTable}
            rowKey="id"
            columns={docColumns}
            dataSource={documents}
            loading={loading}
            pagination={{ pageSize: 10 }}
          />
        </Card>
        </Col>

        <Col flex="0 1 360px">
        <Card className={styles.panel} title="测试检索">
          <TextArea
            rows={3}
            placeholder="输入检索内容，验证知识库检索质量..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
          />
          <Space style={{ marginTop: 12, width: '100%' }} direction="vertical">
            <div>
              <Text type="secondary">返回条数：{searchTopK}</Text>
              <Slider min={1} max={10} value={searchTopK} onChange={setSearchTopK} />
            </div>
            <Button type="primary" block onClick={handleSearch} loading={searching}>检索</Button>
          </Space>
          {searchResult && (
            <div style={{ marginTop: 12 }}>
              <Text strong>命中 {searchResult.chunks.length} 条</Text>
              {searchResult.chunks.length === 0 && (
                <Text type="secondary"> 未找到相关内容（知识库可能为空）</Text>
              )}
              {searchResult.chunks.map((ref) => (
                <Card key={ref.chunkId} className={styles.resultCard} size="small">
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    [{ref.documentName}] 相似度 {(ref.similarity * 100).toFixed(1)}%
                  </Text>
                  <div style={{ fontSize: 12, marginTop: 4, maxHeight: 80, overflow: 'auto' }}>
                    {ref.content}
                  </div>
                </Card>
              ))}
            </div>
          )}
        </Card>
        </Col>
      </Row>

      <Drawer
        title={`分块预览 — ${chunkDrawer.docName}`}
        open={chunkDrawer.open}
        onClose={() => setChunkDrawer((prev) => ({ ...prev, open: false }))}
        width={520}
      >
        {chunkDrawer.loading ? (
          <Spin style={{ display: 'block', margin: '40px auto' }} />
        ) : (
          chunkDrawer.chunks.map((c) => (
            <Card key={c.id} className={styles.chunkCard} size="small">
              <Text type="secondary" style={{ fontSize: 12 }}>分块 #{c.chunkIndex}</Text>
              <div style={{ fontSize: 13, marginTop: 4, whiteSpace: 'pre-wrap' }}>{c.content}</div>
            </Card>
          ))
        )}
      </Drawer>
    </div>
  );
};

export default DatasetDetailPage;
