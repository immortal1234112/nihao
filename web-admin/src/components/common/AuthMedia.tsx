import { Image } from 'antd';
import { useEffect, useState } from 'react';

/**
 * 本平台存储的文件（走网关下载接口 /api/v1/files/...）要求 JWT：
 * <img>/<video>/<a href> 直连会因无 Authorization 头返回 401 破图。
 * 所有渲染本平台文件的组件都应使用本文件提供的 AuthImage / AuthVideo / downloadAuthFile，
 * 内部对本地文件带 Bearer fetch 成 Blob 再预览/下载；远端 provider 直链不受影响。
 */

export const isLocalFile = (url: string): boolean => url.includes('/api/v1/files/');

const FALLBACK_IMG =
  'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=';

/** 取本地文件的鉴权 Blob URL（本地文件在加载中/失败时返回 null） */
export function useAuthFileUrl(url?: string | null): { isLocal: boolean; blobUrl: string | null; failed: boolean } {
  const [blobUrl, setBlobUrl] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const isLocal = !!url && isLocalFile(url);

  useEffect(() => {
    if (!url || !isLocal) return;
    let cancelled = false;
    setBlobUrl(null);
    setFailed(false);
    (async () => {
      try {
        const token = localStorage.getItem('access_token');
        const res = await fetch(url, { headers: token ? { Authorization: `Bearer ${token}` } : {} });
        if (!res.ok) throw new Error('fetch failed');
        const b = await res.blob();
        if (!cancelled) setBlobUrl(URL.createObjectURL(b));
      } catch {
        if (!cancelled) setFailed(true);
      }
    })();
    return () => {
      cancelled = true;
      if (blobUrl) URL.revokeObjectURL(blobUrl);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [url]);

  return { isLocal, blobUrl, failed };
}

interface AuthImageProps {
  src?: string | null;
  alt?: string;
  style?: React.CSSProperties;
  width?: number | string;
  height?: number | string;
  preview?: boolean;
  className?: string;
  /** 本地文件加载中/失败时的占位内容（默认灰底） */
  placeholder?: React.ReactNode;
}

/** 鉴权图片：本地文件 fetch 成 Blob 预览，远端直链直接渲染 */
export const AuthImage: React.FC<AuthImageProps> = ({
  src,
  alt,
  style,
  width,
  height,
  preview = true,
  className,
  placeholder,
}) => {
  const { isLocal, blobUrl, failed } = useAuthFileUrl(src);

  if (!src) return null;
  // 本地文件尚未就绪：显示占位，避免 401 破图闪烁
  if (isLocal && !blobUrl) {
    if (placeholder) return <>{placeholder}</>;
    return <div style={{ background: '#f5f5f5', width, height, ...style }} className={className} />;
  }
  const finalSrc = isLocal && blobUrl ? blobUrl : src;
  return (
    <Image
      src={finalSrc}
      alt={alt}
      style={style}
      width={width}
      height={height}
      preview={preview}
      className={className}
      fallback={failed ? FALLBACK_IMG : undefined}
    />
  );
};

interface AuthVideoProps {
  src?: string | null;
  style?: React.CSSProperties;
  controls?: boolean;
  muted?: boolean;
  preload?: React.VideoHTMLAttributes<HTMLVideoElement>['preload'];
  className?: string;
}

/** 鉴权视频：本地文件 fetch 成 Blob 后播放，远端直链直接播放 */
export const AuthVideo: React.FC<AuthVideoProps> = ({ src, style, controls, muted, preload, className }) => {
  const { isLocal, blobUrl } = useAuthFileUrl(src);
  if (!src) return null;
  return <video src={isLocal && blobUrl ? blobUrl : src} controls={controls} muted={muted} preload={preload} style={style} className={className} />;
};

/**
 * 带鉴权的下载：本地文件直接 fetch Blob 保存；远端走服务端下载代理（统一处理双重编码/白名单）。
 * 不能返回 Promise<boolean> 以外的结果——调用方用它给用户提示。
 */
export async function downloadAuthFile(url: string, filename: string, token = localStorage.getItem('access_token') || ''): Promise<boolean> {
  try {
    const local = isLocalFile(url);
    let res: Response;
    if (local) {
      res = await fetch(url, { headers: token ? { Authorization: `Bearer ${token}` } : {} });
    } else {
      // 远端：走服务端代理（后端已做 URL 解码 + 白名单校验）
      res = await fetch(`/api/v1/tasks/download?url=${encodeURIComponent(url)}`, {
        headers: token ? { Authorization: `Bearer ${token}` } : {},
      });
    }
    if (!res.ok) throw new Error('download failed');
    const blob = await res.blob();
    const downloadUrl = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = downloadUrl;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(downloadUrl);
    return true;
  } catch {
    return false;
  }
}
