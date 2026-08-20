import { Button, Col, Row, Spin, Tooltip } from 'antd';
import { DownloadOutlined } from '@ant-design/icons';
import { useAppFeedback } from '@/utils/feedback';
import { AuthImage, AuthVideo, downloadAuthFile, isLocalFile, useMediaKind } from './AuthMedia';

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

/**
 * 单个结果媒体项：本地平台文件带 Bearer fetch 成 Blob 预览/下载（直连会 401）；
 * 远端 provider 直链直接渲染，下载走服务端代理（后端已做 URL 解码 + 白名单校验）。
 * 预览/下载逻辑统一在 AuthMedia。
 */
const MediaItem: React.FC<{ url: string; index: number }> = ({ url, index }) => {
  const { message } = useAppFeedback();
  // 本地平台文件无扩展名（相对路径），按 fetch 到的 Content-Type 判定视频/图片
  const kind = useMediaKind(url);
  const isVideo = isLocalFile(url) ? kind === 'video' : isVideoUrl(url);

  return (
    <div style={{ textAlign: 'center' }}>
      {isLocalFile(url) && kind === 'unknown' ? (
        /* 类型探测中：占位，避免渲染成错误组件 */
        <div style={{ minHeight: 120, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <Spin size="small" />
        </div>
      ) : kind === 'error' ? (
        <div style={{ minHeight: 120, background: '#f5f5f5', color: '#999', fontSize: 12, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          文件加载失败
        </div>
      ) : isVideo ? (
        <AuthVideo src={url} controls preload="auto" style={{ width: '100%', borderRadius: 8 }} />
      ) : (
        <AuthImage src={url} alt={`生成结果 ${index + 1}`} style={{ borderRadius: 8 }} />
      )}
      <br />
      <Tooltip title="下载到本地">
        <Button
          type="link"
          size="small"
          icon={<DownloadOutlined />}
          onClick={async (e) => {
            e.stopPropagation();
            const key = 'file-download';
            message.loading({ content: '下载中...', key });
            const ok = await downloadAuthFile(url, `generated-${Date.now()}.${isVideo ? 'mp4' : 'png'}`);
            if (ok) message.success({ content: '下载完成', key });
            else message.error({ content: '下载失败，请重试', key });
          }}
        >
          下载
        </Button>
      </Tooltip>
    </div>
  );
};

interface ResultPreviewProps {
  /** 步骤 output 或任务 result 的 JSON 字符串 */
  raw?: string | null;
}

/**
 * 任务结果预览（图片/视频网格 + 下载），从 TaskDetailPage 抽取，画廊等页复用。
 */
const ResultPreview: React.FC<ResultPreviewProps> = ({ raw }) => {
  const urls = parseResultUrls(raw);
  if (urls.length === 0) return null;

  return (
    <Row gutter={[12, 12]}>
      {urls.map((url, i) => (
        <Col key={i} xs={24} sm={12} md={8} lg={6}>
          <MediaItem url={url} index={i} />
        </Col>
      ))}
    </Row>
  );
};

export default ResultPreview;
