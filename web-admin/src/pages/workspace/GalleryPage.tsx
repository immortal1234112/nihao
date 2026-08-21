import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Empty, Flex, Segmented, Skeleton, Typography, Tooltip } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { taskApi } from '@/api/task';
import { AgentTask } from '@/types/api';
import { usePagination } from '@/hooks/usePagination';
import { parseResultUrls } from '@/components/common/ResultPreview';
import { AuthImage, AuthVideo } from '@/components/common/AuthMedia';
import styles from './GalleryPage.module.css';

/** 从任务 requestParams JSON 中解析引擎类型（IMAGE/VIDEO） */
function getEngineType(task: AgentTask): string | null {
  try {
    return (JSON.parse(task.requestParams).engineType as string) ?? null;
  } catch {
    return null;
  }
}

/** 任务是否有可展示的作品（result 解析出图片/视频 URL） */
function hasWork(task: AgentTask): boolean {
  return parseResultUrls(task.result).length > 0;
}

const GALLERY_PAGE_SIZE = 100;

/**
 * 作品画廊 — 瀑布流展示本人所有带结果的任务（图片/视频），
 * 纯前端从 requestParams/result JSON 解析，无需额外后端接口。
 */
const GalleryPage: React.FC = () => {
  const navigate = useNavigate();
  const [typeFilter, setTypeFilter] = useState<string>('all');

  const table = usePagination<AgentTask>(
    async (page, size) => {
      // hideNoVisibleAsset：归档资产已删除的任务不展示（删除作品后画廊不留残留）
      const res = await taskApi.list({ page, size, hideNoVisibleAsset: true });
      return res.data.data;
    },
    [],
    { size: GALLERY_PAGE_SIZE }
  );

  const works = useMemo(() => table.records.filter(hasWork), [table.records]);

  const filtered = useMemo(() => {
    if (typeFilter === 'all') return works;
    const target = typeFilter === 'video' ? 'VIDEO' : 'IMAGE';
    return works.filter((t) => getEngineType(t) === target);
  }, [works, typeFilter]);

  const counts = useMemo(() => {
    const img = works.filter((t) => getEngineType(t) === 'IMAGE').length;
    const vid = works.filter((t) => getEngineType(t) === 'VIDEO').length;
    return { all: works.length, img, vid };
  }, [works]);

  return (
    <div className={styles.page}>
      <Flex justify="space-between" align="center" wrap gap={12} className={styles.pageHeader}>
        <div>
          <Typography.Title level={3} className={styles.pageTitle}>作品画廊</Typography.Title>
          <Typography.Text type="secondary" className={styles.pageHint}>浏览已生成的图片与视频作品</Typography.Text>
        </div>
        <Flex align="center" gap={12}>
          <Segmented
            value={typeFilter}
            onChange={(v) => setTypeFilter(v as string)}
            options={[
              { label: `全部 ${counts.all}`, value: 'all' },
              { label: `图片 ${counts.img}`, value: 'image' },
              { label: `视频 ${counts.vid}`, value: 'video' },
            ]}
          />
          <Tooltip title="刷新">
            <ReloadOutlined onClick={table.refresh} style={{ cursor: 'pointer' }} />
          </Tooltip>
        </Flex>
      </Flex>

      {table.loading && works.length === 0 ? (
        <div className={styles.loadingGrid}>
          {Array.from({ length: 8 }, (_, i) => (
            <div key={i} className={styles.loadingCard}>
              <Skeleton.Image active className={styles.loadingImage} />
              <div className={styles.loadingBody}>
                <Skeleton active paragraph={{ rows: 1 }} title={{ width: '70%' }} />
              </div>
            </div>
          ))}
        </div>
      ) : filtered.length === 0 ? (
        <div className={styles.emptyState}>
          <Empty description="还没有可展示的作品，去创作页生成一个吧">
          <Typography.Link onClick={() => navigate('/workspace/image')}>前往图片生成</Typography.Link>
          </Empty>
        </div>
      ) : (
        <div className={styles.galleryGrid}>
          {filtered.map((task) => (
            <GalleryCard key={task.id} task={task} onOpen={() => navigate(`/tasks/${task.id}`)} />
          ))}
        </div>
      )}
    </div>
  );
};

interface GalleryCardProps {
  task: AgentTask;
  onOpen: () => void;
}

const GalleryCard: React.FC<GalleryCardProps> = ({ task, onOpen }) => {
  const urls = parseResultUrls(task.result);
  // 只显示照片：提示词/状态/时间全部隐藏，有作品名则在照片底部叠加
  const title = task.name?.trim() || '';
  // 本地文件 URL 无扩展名（相对路径），按任务引擎类型判断视频/图片
  const isVideo = getEngineType(task) === 'VIDEO';

  return (
    <div
      onClick={onOpen}
      className={styles.galleryCard}
    >
      <div className={styles.mediaStack}>
        {/* 图片不开启独立预览：点击任意位置都跳转任务详情（与卡片 onClick 行为一致） */}
        {urls.map((url, i) =>
          isVideo ? (
            /* 视频：固定 16:9 容器避免瀑布流卡片高度塌陷错乱；静音自动播放保证有画面（不黑屏） */
            <div key={i} className={styles.videoFrame}>
              <AuthVideo
                src={url}
                muted
                autoPlay
                loop
                playsInline
                preload="auto"
                className={styles.video}
              />
            </div>
          ) : (
            <AuthImage
              key={i}
              src={url}
              alt={title || '作品'}
              className={styles.image}
              preview={false}
            />
          )
        )}
        {title && (
          <div className={styles.galleryTitle}>
            {title}
          </div>
        )}
      </div>
    </div>
  );
};

export default GalleryPage;
