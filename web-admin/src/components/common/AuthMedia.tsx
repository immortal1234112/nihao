import { Image } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * 本平台存储的文件（走网关下载接口 /api/v1/files/...）要求 JWT：
 * <img>/<video>/<a href> 直连会因无 Authorization 头返回 401 破图。
 * 所有渲染本平台文件的组件都应使用本文件提供的 AuthImage / AuthVideo / downloadAuthFile，
 * 内部对本地文件带 Bearer fetch 成 Blob 再预览/下载；远端 provider 直链不受影响。
 */

/** 本地文件 = 平台相对/绝对下载路径（相对路径跟随页面 origin，localhost 与公网域名均正确） */
export const isLocalFile = (url: string): boolean => url.includes('/api/v1/files/');

/** 按 URL 特征判断是否为视频（远端直链带扩展名/路径关键字；本地文件请用 useMediaKind 探测） */
export function isVideoUrl(url: string): boolean {
  return url.includes('.mp4') || url.includes('.webm') || url.includes('video');
}

export type MediaKind = 'video' | 'image' | 'unknown' | 'error';

/**
 * 探测 URL 的媒体类型：远端直链按 URL 特征；本地平台文件 fetch 一次按 Content-Type 判定
 * （相对路径无扩展名，且 thumb 等 URL 可能与实际内容不符）。
 */
export function useMediaKind(url?: string | null): MediaKind {
  const [kind, setKind] = useState<MediaKind>('unknown');
  const isLocal = !!url && isLocalFile(url);

  useEffect(() => {
    setKind('unknown');
    if (!url) return;
    if (!isLocal) {
      setKind(isVideoUrl(url) ? 'video' : 'image');
      return;
    }
    let cancelled = false;
    (async () => {
      try {
        const token = localStorage.getItem('access_token');
        const res = await fetch(url, { headers: token ? { Authorization: `Bearer ${token}` } : {} });
        if (!res.ok) throw new Error('fetch failed');
        const b = await res.blob();
        const t = b.type;
        if (!cancelled) setKind(t.startsWith('video') ? 'video' : t.startsWith('image') ? 'image' : 'unknown');
      } catch {
        if (!cancelled) setKind('error');
      }
    })();
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [url]);

  return kind;
}

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
  autoPlay?: boolean;
  loop?: boolean;
  playsInline?: boolean;
  preload?: React.VideoHTMLAttributes<HTMLVideoElement>['preload'];
  className?: string;
  /** 播放失败时的占位内容（默认灰底 + 提示），防止画廊/画布出现黑屏卡片 */
  fallback?: React.ReactNode;
}

/** 鉴权视频：本地文件 fetch 成 Blob 后播放，远端直链直接播放；加载/解码失败显示占位 */
export const AuthVideo: React.FC<AuthVideoProps> = ({ src, style, controls, muted, autoPlay, loop, playsInline, preload, className, fallback }) => {
  const { isLocal, blobUrl, failed } = useAuthFileUrl(src);
  const [playFailed, setPlayFailed] = useState(false);
  const [poster, setPoster] = useState<string>();
  const videoRef = useRef<HTMLVideoElement>(null);

  useEffect(() => {
    setPlayFailed(false);
    setPoster(undefined);
  }, [src]);

  // 抓首帧作 poster：视频未播放时不再黑屏（preload=metadata 无首帧，浏览器默认不渲染画面）
  const grabFirstFrame = useCallback(() => {
    const v = videoRef.current;
    if (!v || v.videoWidth === 0 || poster) return;
    try {
      // seek 到 0.05s 触发首帧解码（部分浏览器 0 位置为黑帧）；成功后画到 canvas 转 dataURL
      v.currentTime = 0.05;
    } catch {
      /* 尚未可播放时 seek 抛错，忽略 */
    }
  }, [poster]);

  const paintFrame = useCallback(() => {
    const v = videoRef.current;
    if (!v || v.videoWidth === 0 || poster) return;
    try {
      const c = document.createElement('canvas');
      c.width = v.videoWidth;
      c.height = v.videoHeight;
      c.getContext('2d')?.drawImage(v, 0, 0);
      setPoster(c.toDataURL('image/jpeg', 0.7));
    } catch {
      /* canvas 被跨域数据污染时忽略，保持无 poster */
    }
  }, [poster]);

  if (!src) return null;
  const finalSrc = isLocal && blobUrl ? blobUrl : src;
  if ((isLocal && failed) || playFailed) {
    return (
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          minHeight: 120,
          background: '#f5f5f5',
          color: '#999',
          fontSize: 12,
          ...style,
        }}
        className={className}
      >
        {fallback ?? '视频无法加载'}
      </div>
    );
  }
  return (
    <video
      ref={videoRef}
      src={finalSrc}
      controls={controls}
      muted={muted}
      autoPlay={autoPlay}
      loop={loop}
      playsInline={playsInline}
      preload={preload}
      poster={poster}
      style={style}
      className={className}
      onError={() => setPlayFailed(true)}
      onLoadedData={grabFirstFrame}
      onSeeked={paintFrame}
    />
  );
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
    // 按实际内容校正扩展名：服务端返回的 Content-Type 与扩展名不符（如内容 mp4 标 image/png）
    // 会导致下载的文件打不开；octet-stream 等未知类型保留原文件名
    const finalName = fixExtByBlobType(blob, filename);
    const downloadUrl = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = downloadUrl;
    a.download = finalName;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(downloadUrl);
    return true;
  } catch {
    return false;
  }
}

/** 按 Blob 实际 Content-Type 校正下载文件扩展名（与内容不符时替换，未知类型保留原文件名） */
function fixExtByBlobType(blob: Blob, filename: string): string {
  const extByType: Record<string, string> = {
    'video/mp4': 'mp4',
    'video/webm': 'webm',
    'video/quicktime': 'mov',
    'image/png': 'png',
    'image/jpeg': 'jpg',
    'image/webp': 'webp',
    'image/gif': 'gif',
  };
  const ext = extByType[blob.type];
  if (!ext) return filename;
  return filename.replace(/\.[a-z0-9]+$/i, '') + '.' + ext;
}
