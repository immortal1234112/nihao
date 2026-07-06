import { useEffect, useState } from 'react';
import { Card, Descriptions, Tag, Typography, Spin, Empty } from 'antd';
import { useAuthStore } from '@/stores/authStore';
import { tenantApi } from '@/api/tenant';
import { Tenant } from '@/types/api';

const { Title } = Typography;

const TenantInfoPage: React.FC = () => {
  const { user } = useAuthStore();
  const [tenant, setTenant] = useState<Tenant | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (user?.tenantId) {
      tenantApi.getTenant(user.tenantId)
        .then((res) => setTenant(res.data.data))
        .catch(() => {})
        .finally(() => setLoading(false));
    }
  }, [user]);

  if (loading) return <Spin size="large" style={{ display: 'block', margin: '100px auto' }} />;
  if (!tenant) return <Empty description="租户信息不可用" />;

  return (
    <div>
      <Title level={4} style={{ marginBottom: 24 }}>租户信息</Title>
      <Card>
        <Descriptions bordered column={2}>
          <Descriptions.Item label="租户ID">{tenant.id}</Descriptions.Item>
          <Descriptions.Item label="租户名称">{tenant.name}</Descriptions.Item>
          <Descriptions.Item label="状态">
            <Tag color={tenant.status === 'ACTIVE' ? 'success' : 'error'}>{tenant.status}</Tag>
          </Descriptions.Item>
          <Descriptions.Item label="存储提供商">{tenant.storageProvider}</Descriptions.Item>
          <Descriptions.Item label="创建时间">{tenant.createdAt}</Descriptions.Item>
          <Descriptions.Item label="更新时间">{tenant.updatedAt}</Descriptions.Item>
          <Descriptions.Item label="配额配置" span={2}>
            <pre style={{ margin: 0, fontSize: 12 }}>{JSON.stringify(JSON.parse(tenant.quotaConfig || '{}'), null, 2)}</pre>
          </Descriptions.Item>
        </Descriptions>
      </Card>
    </div>
  );
};

export default TenantInfoPage;
