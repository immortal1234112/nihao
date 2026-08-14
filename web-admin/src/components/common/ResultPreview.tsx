import { Button, Col, Image, Row, Tooltip } from 'antd';
import { DownloadOutlined } from '@ant-design/icons';
import { useAppFeedback } from '@/utils/feedback';

const FALLBACK_IMG =
  'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=';

/**
 * 解析任务/步骤结果 JSON，提取图片/视频 URL 列表（画廊页也复用此函数）。
 * 兼容三种结构：outputFiles[] / url / videoUrl。
 */
export function parseResultUrls(raw: string | null | undefined): string[] {
  if (!raw) return [];
  try {
    const obj = JSON.parse(raw);
    const urls: string[] = [];
    if (Array.isArray(obj.outputFiles)) {
      obj.outputFiles
        .filter((f: { url?: string }) => f.url)
        .forEach((f: { url: string }) => urls.push(f.url));
    }
    if (obj.url) urls.push(obj.url);
    if (obj.videoUrl) urls.push(obj.videoUrl);
    return urls;
  } catch {
    return [];
  }
}

/** 按 URL 特征判断是否为视频（.mp4 / .webm / 路径含 video） */
export function isVideoUrl(url: string): boolean {
  return url.includes('.mp4') || url.includes('.webm') || url.includes('video');
}

interface ResultPreviewProps {
  /** 步骤 output 或任务 result 的 JSON 字符串 */
  raw?: string | null;
}

/**
 * 任务结果预览（图片/视频网格 + 下载），从 TaskDetailPage 抽取，画廊等页复用。
 */
const ResultPreview: React.FC<ResultPreviewProps> = ({ raw }) => {
  const { message } = useAppFeedback();
  const urls = parseResultUrls(raw);
  if (urls.length === 0) return null;

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
      const isVideo = isVideoUrl(url) || blob.type.startsWith('video/');
      a.download = `generated-${Date.now()}.${isVideo ? 'mp4' : 'png'}`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(blobUrl);
      message.success({ content: '下载完成', key });
    } catch {
      message.error({ content: '下载失败，请重试', key });
    }
  };

  return (
    <Row gutter={[12, 12]}>
      {urls.map((url, i) => (
        <Col key={i} xs={24} sm={12} md={8} lg={6}>
          <div style={{ textAlign: 'center' }}>
            {isVideoUrl(url) ? (
              <video src={url} controls style={{ width: '100%', borderRadius: 8 }} />
            ) : (
              <Image
                src={url}
                alt={`生成结果 ${i + 1}`}
                style={{ borderRadius: 8 }}
                fallback={FALLBACK_IMG}
              />
            )}
            <br />
            <Tooltip title="下载到本地">
              <Button
                type="link"
                size="small"
                icon={<DownloadOutlined />}
                onClick={(e) => {
                  e.stopPropagation();
                  downloadFile(url);
                }}
              >
                下载
              </Button>
            </Tooltip>
          </div>
        </Col>
      ))}
    </Row>
  );
};

export default ResultPreview;
