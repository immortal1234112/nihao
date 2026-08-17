import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Button,
  Empty,
  Flex,
  Input,
  Radio,
  Select,
  Segmented,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd';
import { AppstoreOutlined, CheckOutlined, CloseOutlined, ReloadOutlined, StarOutlined, UnorderedListOutlined } from '@ant-design/icons';
import { assetApi } from '@/api/asset';
import { projectApi } from '@/api/project';
import { AssetRow, AssetStatus, ProjectRow } from '@/types/api';
import { usePagination } from '@/hooks/usePagination';
import { useAuthStore } from '@/stores/authStore';
import AssetGrid, { ASSET_TYPE_LABEL, DERIVATION_LABEL } from '@/components/assets/AssetGrid';
import AssetDetailDrawer from '@/components/assets/AssetDetailDrawer';
import AssetStatusTag from '@/components/assets/AssetStatusTag';

const { Text, Title } = Typography;

/** 资产类型筛选值 */
type TypeFilter = 'ALL' | 'IMAGE' | 'VIDEO' | 'TEXT' | 'AUDIO' | 'FILE';

/**
 * 资产中心 — 网格/列表双视图 + 过滤栏（项目/类型/状态/标签/星标/关键词）+ 详情抽屉 + 待审核视图。
 */
const AssetsPage: React.FC = () => {
  const navigate = useNavigate();
  const { user } = useAuthStore();
  const isAdmin = (user?.roles || []).some((r) => r === 'TENANT_ADMIN' || r === 'SUPER_ADMIN');

  const [view, setView] = useState<'grid' | 'list'>('grid');
  const [reviewOnly, setReviewOnly] = useState(false);
  const [projectFilter, setProjectFilter] = useState<string | undefined>();
  const [typeFilter, setTypeFilter] = useState<TypeFilter>('ALL');
  const [statusFilter, setStatusFilter] = useState<string | undefined>();
  const [starredOnly, setStarredOnly] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [projects, setProjects] = useState<ProjectRow[]>([]);
  const [openAssetId, setOpenAssetId] = useState<string | null>(null);

  const filters = useMemo(
    () => ({
      projectId: projectFilter,
      assetType: typeFilter === 'ALL' ? undefined : typeFilter,
      status: reviewOnly ? 'UNDER_REVIEW' : statusFilter,
      starred: starredOnly ? true : undefined,
      keyword: keyword || undefined,
    }),
    [projectFilter, typeFilter, statusFilter, reviewOnly, starredOnly, keyword]
  );

  const table = usePagination<AssetRow>(
    async (page, size) => {
      const res = await assetApi.list({ ...filters, page, size });
      return res.data.data;
    },
    [projectFilter, typeFilter, statusFilter, reviewOnly, starredOnly, keyword]
  );

  useEffect(() => {
    projectApi
      .list({ page: 1, size: 100, scope: isAdmin ? 'all' : 'mine' })
      .then((res) => setProjects(res.data.data.records))
      .catch(() => {});
  }, [isAdmin]);

  const quickReview = async (asset: AssetRow, approved: boolean) => {
    await assetApi.review(asset.id, approved);
    message.success(approved ? '已通过' : '已驳回');
    table.refresh();
  };

  return (
    <div>
      <Flex justify="space-between" align="center" wrap gap={8} style={{ marginBottom: 12 }}>
        <Flex align="center" gap={12}>
          <Title level={4} style={{ margin: 0 }}>资产中心</Title>
          <Segmented
            value={reviewOnly ? 'review' : 'all'}
            onChange={(v) => setReviewOnly(v === 'review')}
            options={[
              { label: '全部资产', value: 'all' },
              { label: '待审核', value: 'review' },
            ]}
          />
        </Flex>
        <Flex align="center" gap={12}>
          <Tooltip title="刷新">
            <ReloadOutlined onClick={table.refresh} style={{ cursor: 'pointer' }} />
          </Tooltip>
          <Segmented
            value={view}
            onChange={(v) => setView(v as 'grid' | 'list')}
            options={[
              { value: 'grid', icon: <AppstoreOutlined /> },
              { value: 'list', icon: <UnorderedListOutlined /> },
            ]}
          />
        </Flex>
      </Flex>

      {/* 过滤栏 */}
      <Flex gap={8} wrap style={{ marginBottom: 16 }}>
        <Select
          allowClear
          placeholder="项目"
          style={{ minWidth: 160 }}
          value={projectFilter}
          onChange={setProjectFilter}
          options={projects.map((p) => ({ value: p.id, label: p.name }))}
        />
        <Select
          allowClear
          placeholder="类型"
          style={{ minWidth: 100 }}
          value={typeFilter === 'ALL' ? undefined : typeFilter}
          onChange={(v) => setTypeFilter((v as TypeFilter) ?? 'ALL')}
          options={(['IMAGE', 'VIDEO', 'TEXT', 'AUDIO', 'FILE'] as const).map((t) => ({
            value: t,
            label: ASSET_TYPE_LABEL[t].text,
          }))}
        />
        {!reviewOnly && (
          <Select
            allowClear
            placeholder="状态"
            style={{ minWidth: 110 }}
            value={statusFilter}
            onChange={setStatusFilter}
            options={(['DRAFT', 'UNDER_REVIEW', 'APPROVED', 'REJECTED'] as AssetStatus[]).map((s) => ({
              value: s,
              label: { DRAFT: '草稿', UNDER_REVIEW: '审核中', APPROVED: '已通过', REJECTED: '已驳回' }[s],
            }))}
          />
        )}
        <Tooltip title="只看星标">
          <Button
            type={starredOnly ? 'primary' : 'default'}
            icon={<StarOutlined />}
            onClick={() => setStarredOnly(!starredOnly)}
          />
        </Tooltip>
        <Input.Search
          placeholder="搜索标题/关键词"
          allowClear
          style={{ width: 220 }}
          onSearch={(v) => setKeyword(v)}
        />
      </Flex>

      {table.loading && table.records.length === 0 ? (
        <Empty description="加载中…" style={{ marginTop: 80 }} />
      ) : view === 'grid' ? (
        <AssetGrid
          assets={table.records}
          onOpen={(a) => setOpenAssetId(a.id)}
          emptyText={reviewOnly ? '没有待审核资产' : '还没有资产，去创作页生成或上传素材吧'}
          renderActions={(a) =>
            reviewOnly && a.status === 'UNDER_REVIEW' ? (
              <Space size={4} onClick={(e) => e.stopPropagation()}>
                <Tooltip title="通过">
                  <Button
                    size="small"
                    type="primary"
                    icon={<CheckOutlined />}
                    onClick={() => quickReview(a, true)}
                  />
                </Tooltip>
                <Tooltip title="驳回">
                  <Button size="small" danger icon={<CloseOutlined />} onClick={() => quickReview(a, false)} />
                </Tooltip>
              </Space>
            ) : a.starred ? (
              <StarOutlined style={{ color: '#faad14' }} />
            ) : null
          }
        />
      ) : (
        <Table<AssetRow>
          rowKey="id"
          size="small"
          dataSource={table.records}
          loading={table.loading}
          pagination={table.pagination}
          onRow={(record) => ({ onClick: () => setOpenAssetId(record.id), style: { cursor: 'pointer' } })}
          columns={[
            {
              title: '标题',
              dataIndex: 'title',
              render: (t: string, r) => (
                <Space>
                  {r.starred ? <StarOutlined style={{ color: '#faad14' }} /> : null}
                  {t}
                </Space>
              ),
            },
            {
              title: '类型',
              dataIndex: 'assetType',
              width: 90,
              render: (t: string) => (
                <Tag color={ASSET_TYPE_LABEL[t]?.color ?? 'default'}>{ASSET_TYPE_LABEL[t]?.text ?? t}</Tag>
              ),
            },
            {
              title: '派生',
              dataIndex: 'derivationType',
              width: 90,
              render: (t: string) => (t ? DERIVATION_LABEL[t] ?? t : '-'),
            },
            { title: '状态', dataIndex: 'status', width: 100, render: (s: string) => <AssetStatusTag status={s} /> },
            {
              title: '项目',
              dataIndex: 'projectId',
              width: 140,
              render: (pid: string | null) =>
                pid ? (
                  <Typography.Link
                    onClick={(e) => {
                      e.stopPropagation();
                      navigate(`/projects/${pid}`);
                    }}
                  >
                    {projects.find((p) => p.id === pid)?.name ?? pid.slice(0, 8)}
                  </Typography.Link>
                ) : (
                  <Text type="secondary">租户级</Text>
                ),
            },
            {
              title: '创建时间',
              dataIndex: 'createdAt',
              width: 150,
              render: (t: string) => (t ? new Date(t).toLocaleString('zh-CN') : '-'),
            },
            {
              title: '操作',
              width: 140,
              render: (_, r) =>
                reviewOnly && r.status === 'UNDER_REVIEW' ? (
                  <Space onClick={(e) => e.stopPropagation()}>
                    <Button size="small" type="primary" onClick={() => quickReview(r, true)}>
                      通过
                    </Button>
                    <Button size="small" danger onClick={() => quickReview(r, false)}>
                      驳回
                    </Button>
                  </Space>
                ) : null,
            },
          ]}
        />
      )}

      <AssetDetailDrawer
        assetId={openAssetId}
        onClose={() => setOpenAssetId(null)}
        onChanged={table.refresh}
      />
    </div>
  );
};

export default AssetsPage;
