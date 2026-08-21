import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Button,
  Card,
  DatePicker,
  Empty,
  Flex,
  Form,
  Input,
  Modal,
  Segmented,
  Skeleton,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd';
import { PlusOutlined, ReloadOutlined, TeamOutlined, FileImageOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { projectApi } from '@/api/project';
import { ProjectRow } from '@/types/api';
import { usePagination } from '@/hooks/usePagination';
import { useAuthStore } from '@/stores/authStore';
import styles from './ProjectsPage.module.css';

const { Text } = Typography;

/**
 * 项目列表页 — 卡片网格 + 「我参与的 / 全部」切换（全部仅管理员可见）+ 新建项目弹窗。
 */
const ProjectsPage: React.FC = () => {
  const navigate = useNavigate();
  const { user } = useAuthStore();
  const isAdmin = (user?.roles || []).some((r) => r === 'TENANT_ADMIN' || r === 'SUPER_ADMIN');
  const [scope, setScope] = useState<'mine' | 'all'>('mine');
  const [createOpen, setCreateOpen] = useState(false);
  const [creating, setCreating] = useState(false);
  const [form] = Form.useForm();

  const table = usePagination<ProjectRow>(
    async (page, size) => {
      const res = await projectApi.list({ page, size, scope });
      return res.data.data;
    },
    [scope]
  );

  const handleCreate = async () => {
    try {
      const values = await form.validateFields();
      setCreating(true);
      const res = await projectApi.create({
        name: values.name,
        deadline: values.deadline ? values.deadline.format('YYYY-MM-DD HH:mm:ss') : null,
      });
      message.success('项目创建成功');
      setCreateOpen(false);
      form.resetFields();
      navigate(`/projects/${res.data.data.id}`);
    } catch {
      // 校验失败或请求错误：错误已由拦截器/表单提示
    } finally {
      setCreating(false);
    }
  };

  return (
    <div className={styles.page}>
      <Flex justify="space-between" align="center" wrap gap={12} className={styles.pageHeader}>
        <Flex align="center" gap={12}>
          <div>
            <Typography.Title level={3} className={styles.pageTitle}>项目</Typography.Title>
            <Text type="secondary" className={styles.pageHint}>管理创作目标、成员和项目资产</Text>
          </div>
          <Segmented
            value={scope}
            onChange={(v) => setScope(v as 'mine' | 'all')}
            options={[
              { label: '我参与的', value: 'mine' },
              ...(isAdmin ? [{ label: '全部', value: 'all' as const }] : []),
            ]}
          />
        </Flex>
        <Flex align="center" gap={12} className={styles.headerActions}>
          <Tooltip title="刷新">
            <ReloadOutlined onClick={table.refresh} style={{ cursor: 'pointer' }} />
          </Tooltip>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
            新建项目
          </Button>
        </Flex>
      </Flex>

      {table.loading && table.records.length === 0 ? (
        <div className={styles.projectGrid}>
          {Array.from({ length: 6 }, (_, i) => (
            <Card key={i} className={styles.projectCard}>
              <Skeleton active paragraph={{ rows: 2 }} />
            </Card>
          ))}
        </div>
      ) : table.records.length === 0 ? (
        <div className={styles.emptyState}>
          <Empty description="还没有项目，点击右上角新建一个吧" />
        </div>
      ) : (
        <div className={styles.projectGrid}>
          {table.records.map((p) => (
            <Card
              key={p.id}
              className={styles.projectCard}
              hoverable
              onClick={() => navigate(`/projects/${p.id}`)}
            >
              <Card.Meta
                title={
                  <Flex justify="space-between" align="center">
                    <Text strong ellipsis={{ tooltip: p.name }} className={styles.projectName}>
                      {p.name}
                    </Text>
                    {p.status === 'ARCHIVED' ? (
                      <Tag color="default">已归档</Tag>
                    ) : (
                      <Tag color="processing">进行中</Tag>
                    )}
                  </Flex>
                }
                description={
                  <Flex vertical gap={4}>
                    <Flex gap={16}>
                      <Text type="secondary" className={styles.projectMeta}>
                        <FileImageOutlined /> {p.assetCount} 资产
                      </Text>
                      <Text type="secondary" className={styles.projectMeta}>
                        <TeamOutlined /> {p.memberCount} 成员
                      </Text>
                    </Flex>
                    {p.deadline && (
                      <Text type="secondary" className={styles.projectMeta}>
                        截止 {dayjs(p.deadline).format('YYYY-MM-DD')}
                      </Text>
                    )}
                  </Flex>
                }
              />
            </Card>
          ))}
        </div>
      )}

      <Modal
        title="新建项目"
        open={createOpen}
        onOk={handleCreate}
        confirmLoading={creating}
        onCancel={() => {
          setCreateOpen(false);
          form.resetFields();
        }}
        destroyOnClose
      >
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item
            name="name"
            label="项目名称"
            rules={[{ required: true, message: '请输入项目名称' }, { max: 100, message: '最多 100 字' }]}
          >
            <Input placeholder="例如：夏季新品宣传片" maxLength={100} />
          </Form.Item>
          <Form.Item name="deadline" label="截止日期（可选）">
            <DatePicker style={{ width: '100%' }} showTime />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default ProjectsPage;
