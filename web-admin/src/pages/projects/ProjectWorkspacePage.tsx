import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Avatar,
  Button,
  Card,
  Col,
  Descriptions,
  Empty,
  Flex,
  Form,
  Input,
  Modal,
  Popconfirm,
  Row,
  Select,
  Skeleton,
  Space,
  Spin,
  Table,
  Tabs,
  Tag,
  Timeline,
  Tooltip,
  Typography,
  message,
} from 'antd';
import {
  ArrowLeftOutlined,
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  ReloadOutlined,
  UserOutlined,
} from '@ant-design/icons';
import dayjs from 'dayjs';
import { projectApi } from '@/api/project';
import { tenantApi } from '@/api/tenant';
import { assetApi } from '@/api/asset';
import {
  AssetRow,
  CreativeBrief,
  ProjectDetail,
  ProjectMember,
  ProjectRole,
  ProjectTimeline,
  TenantMember,
  TimelineEvent,
} from '@/types/api';
import { useAuthStore } from '@/stores/authStore';
import { usePagination } from '@/hooks/usePagination';
import AssetGrid, { ASSET_TYPE_LABEL, DERIVATION_LABEL } from '@/components/assets/AssetGrid';
import AssetDetailDrawer from '@/components/assets/AssetDetailDrawer';
import AssetStatusTag from '@/components/assets/AssetStatusTag';
import ProjectCreatePanel from '@/components/create/ProjectCreatePanel';
import StatusTag from '@/components/common/StatusTag';

const { Text, Title } = Typography;

const ROLE_LABEL: Record<ProjectRole, string> = {
  OWNER: '所有者',
  EDITOR: '编辑者',
  REVIEWER: '审核者',
  VIEWER: '浏览者',
};

const ROLE_COLOR: Record<ProjectRole, string> = {
  OWNER: 'gold',
  EDITOR: 'blue',
  REVIEWER: 'purple',
  VIEWER: 'default',
};

/** 时间线事件折叠分组：批量任务（同 batchGroupId）聚合为一个节点。 */
interface TimelineGroup {
  kind: 'TASK' | 'ASSET' | 'BATCH';
  batchGroupId?: string;
  events: TimelineEvent[];
}

function groupTimeline(events: TimelineEvent[]): TimelineGroup[] {
  const groups: TimelineGroup[] = [];
  for (const e of events) {
    const last = groups[groups.length - 1];
    if (e.type === 'TASK' && e.batchGroupId && last?.kind === 'BATCH' && last.batchGroupId === e.batchGroupId) {
      last.events.push(e);
    } else if (e.type === 'TASK' && e.batchGroupId) {
      groups.push({ kind: 'BATCH', batchGroupId: e.batchGroupId, events: [e] });
    } else {
      groups.push({ kind: e.type, events: [e] });
    }
  }
  return groups;
}

/**
 * 项目工作台 — 概览（简报+时间线）/ 资产 / 创作 / 成员 四个 Tab。
 */
const ProjectWorkspacePage: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { user } = useAuthStore();
  const isAdmin = (user?.roles || []).some((r) => r === 'TENANT_ADMIN' || r === 'SUPER_ADMIN');

  const [detail, setDetail] = useState<ProjectDetail | null>(null);
  const [members, setMembers] = useState<ProjectMember[]>([]);
  const [timeline, setTimeline] = useState<ProjectTimeline | null>(null);
  const [loading, setLoading] = useState(true);
  const [timelineTick, setTimelineTick] = useState(0);
  const [openAssetId, setOpenAssetId] = useState<string | null>(null);
  const [briefEditOpen, setBriefEditOpen] = useState(false);
  const [briefSaving, setBriefSaving] = useState(false);
  const [briefForm] = Form.useForm<{ target?: string; style?: string }>();

  const loadAll = useCallback(async () => {
    if (!id) return;
    try {
      const [detailRes, memberRes, timelineRes] = await Promise.all([
        projectApi.detail(id),
        projectApi.listMembers(id),
        projectApi.timeline(id),
      ]);
      setDetail(detailRes.data.data);
      setMembers(memberRes.data.data);
      setTimeline(timelineRes.data.data);
    } catch {
      navigate('/projects', { replace: true });
    } finally {
      setLoading(false);
    }
  }, [id, navigate]);

  useEffect(() => {
    loadAll();
  }, [loadAll]);

  const myRole: ProjectRole | null = useMemo(
    () => members.find((m) => m.userId === user?.id)?.roleCode ?? null,
    [members, user]
  );
  const canEdit = myRole === 'OWNER' || myRole === 'EDITOR' || isAdmin;
  const canManageMembers = myRole === 'OWNER' || isAdmin;
  const brief = detail?.brief;

  const openBriefEdit = () => {
    briefForm.setFieldsValue({
      target: brief?.target ?? '',
      style: brief?.style ?? '',
    });
    setBriefEditOpen(true);
  };

  const saveBrief = async () => {
    if (!id) return;
    const values = await briefForm.validateFields();
    setBriefSaving(true);
    try {
      await projectApi.updateBrief(id, {
        target: values.target,
        style: values.style,
      });
      message.success('简报已保存');
      setBriefEditOpen(false);
      loadAll();
    } catch {
      /* 已提示 */
    } finally {
      setBriefSaving(false);
    }
  };

  const briefHint = useMemo(() => {
    const parts: string[] = [];
    if (brief?.target) parts.push(`目标：${brief.target}`);
    if (brief?.style) parts.push(`风格：${brief.style}`);
    return parts.join('；');
  }, [brief]);

  if (loading || !detail) {
    return (
      <div style={{ padding: 40 }}>
        <Skeleton active paragraph={{ rows: 6 }} />
      </div>
    );
  }

  const timelineGroups = timeline ? groupTimeline(timeline.events) : [];

  return (
    <div>
      {/* 头部 */}
      <Flex align="center" gap={12} style={{ marginBottom: 16 }}>
        <Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate('/projects')} />
        <Title level={4} style={{ margin: 0 }}>{detail.name}</Title>
        {detail.status === 'ARCHIVED' ? <Tag>已归档</Tag> : <Tag color="processing">进行中</Tag>}
        <Text type="secondary" style={{ fontSize: 12 }}>
          {detail.memberCount} 成员 · {detail.assetCount} 资产
          {detail.deadline && ` · 截止 ${dayjs(detail.deadline).format('YYYY-MM-DD')}`}
        </Text>
      </Flex>

      <Tabs
        tabPosition="left"
        items={[
          {
            key: 'overview',
            label: '概览',
            children: <OverviewTab
              brief={brief}
              briefHint={briefHint}
              canEdit={canEdit}
              groups={timelineGroups}
              onEditBrief={openBriefEdit}
              onOpenAsset={(assetId) => setOpenAssetId(assetId)}
              onRefresh={() => {
                setTimelineTick((t) => t + 1);
                loadAll();
              }}
            />,
          },
          {
            key: 'assets',
            label: '资产',
            children: <AssetsTab
              projectId={id!}
              onOpen={(a) => setOpenAssetId(a.id)}
              refreshKey={timelineTick}
              onChanged={loadAll}
            />,
          },
          {
            key: 'create',
            label: '创作',
            children: (
              <ProjectCreatePanel
                projectId={id}
                briefHint={briefHint || undefined}
                onSubmitted={() => {
                  setTimelineTick((t) => t + 1);
                  loadAll();
                }}
              />
            ),
          },
          {
            key: 'members',
            label: '成员',
            children: (
              <MembersTab
                projectId={id!}
                members={members}
                canManage={canManageMembers}
                myUserId={user?.id}
                onChanged={loadAll}
              />
            ),
          },
        ]}
      />

      <Modal
        title="编辑创作简报"
        open={briefEditOpen}
        onOk={saveBrief}
        confirmLoading={briefSaving}
        onCancel={() => setBriefEditOpen(false)}
      >
        <Form form={briefForm} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="target" label="创作目标">
            <Input.TextArea rows={2} placeholder="例如：为夏季新品拍摄一组清新风格的宣传物料" />
          </Form.Item>
          <Form.Item name="style" label="风格要求">
            <Input.TextArea rows={2} placeholder="例如：莫兰迪色调、简约、高对比" />
          </Form.Item>
        </Form>
      </Modal>

      <AssetDetailDrawer assetId={openAssetId} onClose={() => setOpenAssetId(null)} onChanged={loadAll} />
    </div>
  );
};

// ==================== 概览 Tab ====================

interface OverviewTabProps {
  brief: CreativeBrief | null | undefined;
  briefHint: string;
  canEdit: boolean;
  groups: TimelineGroup[];
  onEditBrief: () => void;
  onOpenAsset: (assetId: string) => void;
  onRefresh: () => void;
}

const OverviewTab: React.FC<OverviewTabProps> = ({
  brief,
  briefHint,
  canEdit,
  groups,
  onEditBrief,
  onOpenAsset,
  onRefresh,
}) => {
  const navigate = useNavigate();

  return (
    <Row gutter={24}>
      {/* 简报卡片 */}
      <Col span={10}>
        <Card
          title="创作简报"
          size="small"
          extra={
            <Space>
              {canEdit && <Button size="small" icon={<EditOutlined />} onClick={onEditBrief}>编辑</Button>}
              <Tooltip title="刷新">
                <ReloadOutlined onClick={onRefresh} style={{ cursor: 'pointer' }} />
              </Tooltip>
            </Space>
          }
        >
          {brief?.target || brief?.style ? (
            <Descriptions column={1} size="small">
              {brief.target && <Descriptions.Item label="目标">{brief.target}</Descriptions.Item>}
              {brief.style && <Descriptions.Item label="风格">{brief.style}</Descriptions.Item>}
            </Descriptions>
          ) : (
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description={canEdit ? '还没有简报，点击右上角编辑' : '暂无简报'}
            />
          )}
        </Card>
      </Col>
      {/* 时间线 */}
      <Col span={14}>
        <Card title="项目时间线" size="small" extra={<Text type="secondary" style={{ fontSize: 12 }}>任务与资产按时间聚合</Text>}>
          {groups.length === 0 ? (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有活动，去「创作」Tab 生成第一个作品吧" />
          ) : (
            <Timeline
              style={{ marginTop: 8 }}
              items={groups.map((g) => {
                if (g.kind === 'BATCH') {
                  const done = g.events.filter((e) => e.status === 'DONE').length;
                  return {
                    color: 'blue',
                    children: (
                      <div>
                        <Tag color="geekblue">批量生成</Tag>
                        <Text>{g.events.length} 个任务</Text>
                        <Text type="secondary" style={{ fontSize: 12, marginLeft: 8 }}>
                          {done}/{g.events.length} 完成 · {dayjs(g.events[0].createdAt).format('MM-DD HH:mm')}
                        </Text>
                        <div style={{ marginTop: 4 }}>
                          {g.events.map((e) => (
                            <div key={e.id}>
                              <Typography.Link onClick={() => navigate(`/tasks/${e.id}`)}>
                                {e.title} · {e.status}
                              </Typography.Link>
                            </div>
                          ))}
                        </div>
                      </div>
                    ),
                  };
                }
                if (g.kind === 'TASK') {
                  const e = g.events[0];
                  return {
                    color: e.status === 'DONE' ? 'green' : e.status === 'FAILED' ? 'red' : 'blue',
                    children: (
                      <div>
                        <Typography.Link onClick={() => navigate(`/tasks/${e.id}`)}>
                          {e.title} 任务
                        </Typography.Link>
                        <StatusTag status={e.status || ''} />
                        <Text type="secondary" style={{ fontSize: 12 }}>
                          {dayjs(e.createdAt).format('MM-DD HH:mm')}
                        </Text>
                      </div>
                    ),
                  };
                }
                const e = g.events[0];
                return {
                  color: 'gray',
                  dot: ASSET_TYPE_LABEL[e.assetType || ''] ? null : undefined,
                  children: (
                    <div>
                      <Typography.Link onClick={() => onOpenAsset(e.id)}>{e.title}</Typography.Link>
                      <Tag
                        color={ASSET_TYPE_LABEL[e.assetType || '']?.color ?? 'default'}
                        style={{ marginLeft: 8 }}
                      >
                        {ASSET_TYPE_LABEL[e.assetType || '']?.text ?? e.assetType}
                      </Tag>
                      {e.derivationType && <Tag>{DERIVATION_LABEL[e.derivationType]}</Tag>}
                      {e.status && <AssetStatusTag status={e.status} />}
                      <Text type="secondary" style={{ fontSize: 12 }}>
                        {dayjs(e.createdAt).format('MM-DD HH:mm')}
                      </Text>
                    </div>
                  ),
                };
              })}
            />
          )}
        </Card>
      </Col>
    </Row>
  );
};

// ==================== 资产 Tab ====================

interface AssetsTabProps {
  projectId: string;
  onOpen: (asset: AssetRow) => void;
  refreshKey: number;
  onChanged: () => void;
}

const AssetsTab: React.FC<AssetsTabProps> = ({ projectId, onOpen, refreshKey, onChanged }) => {
  const [typeFilter, setTypeFilter] = useState<string | undefined>();
  const table = usePagination<AssetRow>(
    async (page, size) => {
      const res = await assetApi.list({ projectId, assetType: typeFilter, page, size });
      return res.data.data;
    },
    [typeFilter, refreshKey]
  );

  return (
    <div>
      <Flex gap={8} wrap style={{ marginBottom: 12 }}>
        <Tag.CheckableTag checked={!typeFilter} onChange={() => setTypeFilter(undefined)}>
          全部
        </Tag.CheckableTag>
        {(['IMAGE', 'VIDEO', 'TEXT', 'AUDIO', 'FILE'] as const).map((t) => (
          <Tag.CheckableTag key={t} checked={typeFilter === t} onChange={() => setTypeFilter(t === typeFilter ? undefined : t)}>
            {ASSET_TYPE_LABEL[t].text}
          </Tag.CheckableTag>
        ))}
        <span style={{ flex: 1 }} />
        <Tooltip title="刷新">
          <ReloadOutlined onClick={table.refresh} style={{ cursor: 'pointer' }} />
        </Tooltip>
      </Flex>
      {table.loading && table.records.length === 0 ? (
        <Spin />
      ) : (
        <AssetGrid assets={table.records} onOpen={onOpen} emptyText="项目里还没有资产" />
      )}
    </div>
  );
};

// ==================== 成员 Tab ====================

interface MembersTabProps {
  projectId: string;
  members: ProjectMember[];
  canManage: boolean;
  myUserId?: string;
  onChanged: () => void;
}

const MembersTab: React.FC<MembersTabProps> = ({ projectId, members, canManage, myUserId, onChanged }) => {
  const { user } = useAuthStore();
  const [tenantMembers, setTenantMembers] = useState<TenantMember[]>([]);
  const [addOpen, setAddOpen] = useState(false);
  const [addingUserId, setAddingUserId] = useState<string | undefined>();
  const [addingRole, setAddingRole] = useState<ProjectRole>('VIEWER');

  const loadTenantMembers = useCallback(async () => {
    if (!user?.tenantId) return;
    try {
      const res = await tenantApi.getMembers(user.tenantId);
      setTenantMembers(res.data.data);
    } catch {
      /* 已提示 */
    }
  }, [user?.tenantId]);

  useEffect(() => {
    if (addOpen) loadTenantMembers();
  }, [addOpen, loadTenantMembers]);

  const usernameMap = useMemo(
    () => Object.fromEntries(tenantMembers.map((t) => [t.userId, t.username])),
    [tenantMembers]
  );

  const handleAdd = async () => {
    if (!addingUserId) return;
    await projectApi.addMember(projectId, { userId: addingUserId, roleCode: addingRole });
    message.success('成员已添加');
    setAddOpen(false);
    setAddingUserId(undefined);
    onChanged();
  };

  const handleRoleChange = async (member: ProjectMember, roleCode: ProjectRole) => {
    await projectApi.updateRole(projectId, member.id, roleCode);
    message.success('角色已更新');
    onChanged();
  };

  const handleRemove = async (member: ProjectMember) => {
    await projectApi.removeMember(projectId, member.id);
    message.success('成员已移除');
    onChanged();
  };

  const existingIds = new Set(members.map((m) => m.userId));

  return (
    <div>
      <Flex justify="space-between" align="center" style={{ marginBottom: 12 }}>
        <Text type="secondary" style={{ fontSize: 12 }}>
          项目角色与租户角色正交：项目内按项目角色授权，租户管理员可管理任意项目
        </Text>
        {canManage && (
          <Button type="primary" size="small" icon={<PlusOutlined />} onClick={() => setAddOpen(true)}>
            添加成员
          </Button>
        )}
      </Flex>
      <Table<ProjectMember>
        rowKey="id"
        dataSource={members}
        size="small"
        pagination={false}
        columns={[
          {
            title: '成员',
            dataIndex: 'userId',
            render: (uid: string) => (
              <Space>
                <Avatar size="small" icon={<UserOutlined />} />
                {usernameMap[uid] || uid.slice(0, 8)}
                {uid === myUserId && <Tag color="cyan">我</Tag>}
              </Space>
            ),
          },
          {
            title: '角色',
            dataIndex: 'roleCode',
            width: 200,
            render: (role: ProjectRole, record) =>
              canManage && role !== 'OWNER' ? (
                <Select
                  size="small"
                  value={role}
                  style={{ width: 140 }}
                  onChange={(v) => handleRoleChange(record, v)}
                  options={(Object.keys(ROLE_LABEL) as ProjectRole[])
                    .filter((r) => r !== 'OWNER')
                    .map((r) => ({ value: r, label: ROLE_LABEL[r] }))}
                />
              ) : (
                <Tag color={ROLE_COLOR[role]}>{ROLE_LABEL[role]}</Tag>
              ),
          },
          {
            title: '加入时间',
            dataIndex: 'createdAt',
            width: 160,
            render: (t: string) => (t ? dayjs(t).format('YYYY-MM-DD HH:mm') : '-'),
          },
          {
            title: '操作',
            width: 80,
            render: (_, record) =>
              canManage && record.roleCode !== 'OWNER' ? (
                <Popconfirm
                  title="移除该成员？"
                  onConfirm={() => handleRemove(record)}
                >
                  <Button size="small" danger type="text" icon={<DeleteOutlined />} />
                </Popconfirm>
              ) : null,
          },
        ]}
      />

      <Modal title="添加成员" open={addOpen} onOk={handleAdd} onCancel={() => setAddOpen(false)} okButtonProps={{ disabled: !addingUserId }}>
        <Flex gap={8} vertical style={{ marginTop: 16 }}>
          <Select
            style={{ width: '100%' }}
            placeholder="从租户成员中选择"
            value={addingUserId}
            onChange={setAddingUserId}
            options={tenantMembers
              .filter((t) => !existingIds.has(t.userId))
              .map((t) => ({ value: t.userId, label: `${t.username || t.userId.slice(0, 8)}（${t.email || '租户成员'}）` }))}
          />
          <Select
            style={{ width: '100%' }}
            value={addingRole}
            onChange={setAddingRole}
            options={(Object.keys(ROLE_LABEL) as ProjectRole[])
              .filter((r) => r !== 'OWNER')
              .map((r) => ({ value: r, label: ROLE_LABEL[r] }))}
          />
        </Flex>
      </Modal>
    </div>
  );
};

export default ProjectWorkspacePage;
