import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Empty, Flex, Segmented, Skeleton, Typography, Tag, Tooltip } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { taskApi } from '@/api/task';
import { AgentTask } from '@/types/api';
import { usePagination } from '@/hooks/usePagination';
import { parseResultUrls, isVideoUrl } from '@/components/common/ResultPreview';
import { AuthImage, AuthVideo } from '@/components/common/AuthMedia';
import StatusTag from '@/components/common/StatusTag';

const { Text } = Typography;

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

/** 任务 prompt 摘要 */
function getPrompt(task: AgentTask): string {
  try {
    return (JSON.parse(task.requestParams).prompt as string) || '';
  } catch {
    return '';
  }
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
      const res = await taskApi.list({ page, size });
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
    <div>
      <Flex justify="space-between" align="center" style={{ marginBottom: 16 }}>
        <Typography.Title level={4} style={{ margin: 0 }}>作品画廊</Typography.Title>
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
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(240px, 1fr))', gap: 16 }}>
          {Array.from({ length: 8 }, (_, i) => (
            <div key={i} style={{ borderRadius: 12, overflow: 'hidden', background: '#fff' }}>
              <Skeleton.Image active style={{ width: '100%', height: 200 }} />
              <div style={{ padding: 12 }}>
                <Skeleton active paragraph={{ rows: 1 }} title={{ width: '70%' }} />
              </div>
            </div>
          ))}
        </div>
      ) : filtered.length === 0 ? (
        <Empty description="还没有可展示的作品，去创作页生成一个吧" style={{ marginTop: 80 }}>
          <Typography.Link onClick={() => navigate('/workspace/image')}>前往图片生成</Typography.Link>
        </Empty>
      ) : (
        <div style={{ columnCount: 4, columnGap: 16, columnWidth: 240 }}>
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
  const prompt = getPrompt(task);
  const engineType = getEngineType(task);

  return (
    <div
      onClick={onOpen}
      style={{
        breakInside: 'avoid',
        marginBottom: 16,
        borderRadius: 12,
        overflow: 'hidden',
        background: '#fff',
        boxShadow: '0 2px 8px rgba(0,0,0,0.06)',
        cursor: 'pointer',
        transition: 'transform .2s, box-shadow .2s',
      }}
      onMouseEnter={(e) => {
        e.currentTarget.style.transform = 'translateY(-2px)';
        e.currentTarget.style.boxShadow = '0 6px 20px rgba(0,0,0,0.12)';
      }}
      onMouseLeave={(e) => {
        e.currentTarget.style.transform = 'none';
        e.currentTarget.style.boxShadow = '0 2px 8px rgba(0,0,0,0.06)';
      }}
    >
      {/* 图片不开启独立预览：点击任意位置都跳转任务详情（与卡片 onClick 行为一致） */}
      {urls.map((url, i) =>
        isVideoUrl(url) ? (
          <AuthVideo key={i} src={url} muted preload="metadata" style={{ width: '100%', display: 'block' }} />
        ) : (
          <AuthImage
            key={i}
            src={url}
            alt={prompt.slice(0, 30) || '作品'}
            style={{ width: '100%', display: 'block' }}
            preview={false}
          />
        )
      )}
      <div style={{ padding: '10px 12px' }}>
        <div style={{ marginBottom: 6 }}>
          <StatusTag status={task.status} />
          {engineType && (
            <Tag color={engineType === 'VIDEO' ? 'purple' : 'blue'} style={{ marginLeft: 6 }}>
              {engineType === 'VIDEO' ? '视频' : '图片'}
            </Tag>
          )}
        </div>
        <Text type="secondary" style={{ fontSize: 12, display: 'block' }} ellipsis={{ tooltip: prompt }}>
          {prompt || '无提示词'}
        </Text>
        <Text type="secondary" style={{ fontSize: 12 }}>
          {task.createdAt ? dayjs(task.createdAt).format('YYYY-MM-DD HH:mm') : ''}
        </Text>
      </div>
    </div>
  );
};

export default GalleryPage;
