import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button, Table, Typography } from 'antd';
import {
  PictureOutlined,
  VideoCameraOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
} from '@ant-design/icons';
import { taskApi } from '@/api/task';
import { adminApi } from '@/api/admin';
import { userApi } from '@/api/user';
import { AgentTask, TaskStats, QuotaInfo } from '@/types/api';
import StatusTag from '@/components/common/StatusTag';
import { useAuthStore } from '@/stores/authStore';
import dayjs from 'dayjs';
import styles from './DashboardPage.module.css';

const { Title } = Typography;

const DashboardPage: React.FC = () => {
  const [stats, setStats] = useState<TaskStats | null>(null);
  const [recentTasks, setRecentTasks] = useState<AgentTask[]>([]);
  const [imageQuota, setImageQuota] = useState<QuotaInfo | null>(null);
  const [videoQuota, setVideoQuota] = useState<QuotaInfo | null>(null);
  const [loading, setLoading] = useState(true);
  const navigate = useNavigate();
  const { user } = useAuthStore();
  const roles = user?.roles || [];
  const isAdmin = roles.includes('TENANT_ADMIN') || roles.includes('SUPER_ADMIN');

  useEffect(() => {
    const load = async () => {
      try {
        // D5：图片/视频配额分别拉取，避免视频卡片显示图片配额
        const [imageQuotaRes, videoQuotaRes, taskRes] = await Promise.all([
          userApi.getQuota('IMAGE').catch(() => null),
          userApi.getQuota('VIDEO').catch(() => null),
          taskApi.list({ page: 1, size: 5 }).catch(() => null),
        ]);
        if (imageQuotaRes) setImageQuota(imageQuotaRes.data.data);
        if (videoQuotaRes) setVideoQuota(videoQuotaRes.data.data);
        if (taskRes) setRecentTasks(taskRes.data.data.records);

        if (isAdmin) {
          const statsRes = await adminApi.getStats().catch(() => null);
          if (statsRes) setStats(statsRes.data.data);
        }
      } finally {
        setLoading(false);
      }
    };
    load();
  }, [isAdmin]);

  const columns = [
    { title: '任务ID', dataIndex: 'id', key: 'id', width: 200, render: (v: string) => v.slice(0, 12) + '...' },
    { title: '类型', dataIndex: 'workflowType', key: 'type', width: 80 },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (s: string) => <StatusTag status={s} />,
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 180,
      render: (v: string) => dayjs(v).format('YYYY-MM-DD HH:mm'),
    },
  ];

  return (
    <div className={styles.page}>
      <section className={styles.hero}>
        <div className={styles.heroCopy}>
          <div className={styles.eyebrow}>AI AGENT CREATIVE WORKSPACE</div>
          <Title className={styles.heroTitle}>你好，{user?.username || '创作者'}</Title>
          <span className={styles.heroSubTitle}>从一个想法开始，把提示词变成图片、视频和可复用的创作资产。</span>
        </div>
        <div className={styles.heroActions}>
          <Button type="primary" icon={<PictureOutlined />} onClick={() => navigate('/workspace/image')}>创建图片</Button>
          <Button icon={<VideoCameraOutlined />} onClick={() => navigate('/workspace/video')}>创建视频</Button>
          <Button icon={<ClockCircleOutlined />} onClick={() => navigate('/tasks')}>查看任务</Button>
        </div>
      </section>

      <section className={styles.section}>
        <div className={styles.sectionHeader}>
          <Title className={styles.sectionTitle}>创作概览</Title>
          <span className={styles.sectionHint}>{isAdmin ? '当前租户任务状态' : '你的创作配额与任务状态'}</span>
        </div>
        <div className={styles.metricGrid}>
          <div className={styles.metric}><div className={styles.metricLabel}>图片配额</div><div className={styles.metricValue}>{imageQuota?.remaining ?? '-'}<span>剩余</span></div></div>
          <div className={styles.metric}><div className={styles.metricLabel}>视频配额</div><div className={styles.metricValue}>{videoQuota?.remaining ?? '-'}<span>剩余</span></div></div>
          <div className={styles.metric}><div className={styles.metricLabel}>进行中任务</div><div className={styles.metricValue}>{stats?.processing ?? '-'}<span>个</span></div></div>
          <div className={styles.metric}><div className={styles.metricLabel}>已完成任务</div><div className={styles.metricValue}>{stats?.done ?? '-'}<span>个</span></div></div>
        </div>
      </section>

      <section className={styles.section}>
        <div className={styles.contentGrid}>
          <div className={styles.panel}>
            <div className={styles.panelHeader}>
              <Title className={styles.panelTitle}>快速开始</Title>
              <span className={styles.sectionHint}>选择一个创作入口</span>
            </div>
            <div className={styles.quickGrid}>
              <div className={styles.quickAction} onClick={() => navigate('/workspace/image')}>
                <span className={styles.quickIcon}><PictureOutlined /></span>
                <span className={styles.quickTitle}>图片生成</span>
                <span className={styles.quickDescription}>从文字和参考图开始</span>
              </div>
              <div className={styles.quickAction} onClick={() => navigate('/workspace/video')}>
                <span className={styles.quickIcon}><VideoCameraOutlined /></span>
                <span className={styles.quickTitle}>视频生成</span>
                <span className={styles.quickDescription}>让画面动起来</span>
              </div>
              <div className={styles.quickAction} onClick={() => navigate('/workspace/pipeline')}>
                <span className={styles.quickIcon}><CheckCircleOutlined /></span>
                <span className={styles.quickTitle}>流水线创作</span>
                <span className={styles.quickDescription}>组合多个生成步骤</span>
              </div>
            </div>
          </div>
          <div className={styles.panel}>
            <div className={styles.panelHeader}>
              <Title className={styles.panelTitle}>最近任务</Title>
              <Button type="link" onClick={() => navigate('/tasks')}>查看全部</Button>
            </div>
            <div className={styles.tableWrap}>
              <Table
                dataSource={recentTasks}
                columns={columns}
                rowKey="id"
                loading={loading}
                size="small"
                pagination={false}
                onRow={(record) => ({ onClick: () => navigate(`/tasks/${record.id}`), style: { cursor: 'pointer' } })}
              />
            </div>
          </div>
        </div>
      </section>
    </div>
  );
};

export default DashboardPage;
