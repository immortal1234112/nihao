import { useEffect, useState } from 'react';
import { Table, Upload, Button, Space, Typography, Tag, Popconfirm, message, Modal } from 'antd';
import { UploadOutlined, DownloadOutlined, DeleteOutlined, ReloadOutlined } from '@ant-design/icons';
import type { UploadFile } from 'antd';
import { fileApi } from '@/api/file';
import { StorageFile } from '@/types/api';
import dayjs from 'dayjs';

const { Title } = Typography;

const FileListPage: React.FC = () => {
  const [files, setFiles] = useState<StorageFile[]>([]);
  const [loading, setLoading] = useState(false);
  const [pagination, setPagination] = useState({ page: 1, size: 20, total: 0 });
  const [uploadOpen, setUploadOpen] = useState(false);

  const loadFiles = async (page = 1, size = 20) => {
    setLoading(true);
    try {
      const res = await fileApi.list({ page, size });
      const data = res.data.data;
      setFiles(data.records);
      setPagination({ page: data.pagination.page, size: data.pagination.size, total: data.pagination.total });
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { loadFiles(); }, []);

  const handleUpload = async (info: { file: UploadFile }) => {
    const file = info.file as unknown as File;
    if (!file) return;
    try {
      await fileApi.upload(file);
      message.success('上传成功');
      setUploadOpen(false);
      loadFiles();
    } catch { /* handled */ }
  };

  const handleDelete = async (id: string) => {
    try {
      await fileApi.delete(id);
      message.success('删除成功');
      loadFiles();
    } catch { /* handled */ }
  };

  const formatSize = (bytes: number) => {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  };

  const columns = [
    { title: '文件名', dataIndex: 'originalName', key: 'name', ellipsis: true },
    { title: '类型', dataIndex: 'contentType', key: 'type', width: 120, ellipsis: true },
    { title: '大小', dataIndex: 'fileSize', key: 'size', width: 100, render: (v: number) => formatSize(v) },
    {
      title: '存储类型', dataIndex: 'storageClass', key: 'class', width: 90,
      render: (v: string) => <Tag>{v}</Tag>,
    },
    {
      title: '来源', dataIndex: 'sourceType', key: 'source', width: 100,
      render: (v: string) => v || '-',
    },
    {
      title: '上传时间', dataIndex: 'createdAt', key: 'createdAt', width: 170,
      render: (v: string) => dayjs(v).format('MM-DD HH:mm:ss'),
    },
    {
      title: '操作', key: 'actions', width: 150,
      render: (_: unknown, record: StorageFile) => (
        <Space>
          <Button type="link" size="small" icon={<DownloadOutlined />}
            href={fileApi.getDownloadUrl(record.id)} target="_blank">
            下载
          </Button>
          <Popconfirm title="确定删除该文件？" onConfirm={() => handleDelete(record.id)}>
            <Button type="link" size="small" danger icon={<DeleteOutlined />}>删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <Title level={4} style={{ margin: 0 }}>文件管理</Title>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => loadFiles()}>刷新</Button>
          <Button type="primary" icon={<UploadOutlined />} onClick={() => setUploadOpen(true)}>上传文件</Button>
        </Space>
      </div>

      <Table
        dataSource={files}
        columns={columns}
        rowKey="id"
        loading={loading}
        pagination={{
          current: pagination.page,
          pageSize: pagination.size,
          total: pagination.total,
          showTotal: (t) => `共 ${t} 条`,
          onChange: (page, size) => loadFiles(page, size),
        }}
      />

      <Modal title="上传文件" open={uploadOpen} onCancel={() => setUploadOpen(false)} footer={null}>
        <Upload.Dragger
          customRequest={({ file, onSuccess }) => {
            handleUpload({ file: file as UploadFile });
            onSuccess?.('ok');
          }}
          showUploadList={false}
        >
          <p><UploadOutlined style={{ fontSize: 48 }} /></p>
          <p>点击或拖拽文件到此处上传</p>
          <p style={{ color: '#999' }}>支持任意格式文件</p>
        </Upload.Dragger>
      </Modal>
    </div>
  );
};

export default FileListPage;
