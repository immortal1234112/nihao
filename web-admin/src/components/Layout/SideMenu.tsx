import { useMemo } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { Menu } from 'antd';
import {
  DashboardOutlined,
  PictureOutlined,
  VideoCameraOutlined,
  AppstoreOutlined,
  FileImageOutlined,
  NodeIndexOutlined,
  UnorderedListOutlined,
  FolderOpenOutlined,
  ClusterOutlined,
  MessageOutlined,
  BookOutlined,
  FileTextOutlined,
  UserOutlined,
  KeyOutlined,
  TeamOutlined,
  UsergroupAddOutlined,
  BarChartOutlined,
  AuditOutlined,
  FileSearchOutlined,
  SettingOutlined,
  DatabaseOutlined,
  ProjectOutlined,
  InboxOutlined,
} from '@ant-design/icons';
import { useAuthStore } from '@/stores/authStore';

const SideMenu: React.FC = () => {
  const navigate = useNavigate();
  const location = useLocation();
  const { user } = useAuthStore();
  const roles = user?.roles || [];

  const isAdmin = roles.includes('TENANT_ADMIN') || roles.includes('SUPER_ADMIN');

  // 菜单 key 均为路由前缀：/projects/xxx 也应高亮 /projects
  const selectedKey = useMemo(() => {
    const pathname = location.pathname;
    if (pathname === '/projects' || pathname.startsWith('/projects/')) return '/projects';
    return pathname;
  }, [location.pathname]);

  const menuItems = [
    { key: '/dashboard', icon: <DashboardOutlined />, label: '首页仪表盘' },
    {
      type: 'group' as const,
      label: '项目与资产',
      children: [
        { key: '/projects', icon: <ProjectOutlined />, label: '项目' },
        { key: '/assets', icon: <InboxOutlined />, label: '资产中心' },
      ],
    },
    {
      type: 'group' as const,
      label: 'AI 创作',
      children: [
        { key: '/workspace/image', icon: <PictureOutlined />, label: '图片生成' },
        { key: '/workspace/video', icon: <VideoCameraOutlined />, label: '视频生成' },
        { key: '/workspace/batch', icon: <AppstoreOutlined />, label: '批量生成' },
        { key: '/workspace/pipeline', icon: <NodeIndexOutlined />, label: '流水线' },
        { key: '/workspace/gallery', icon: <FileImageOutlined />, label: '作品画廊' },
      ],
    },
    { key: '/tasks', icon: <UnorderedListOutlined />, label: '任务管理' },
    { key: '/files', icon: <FolderOpenOutlined />, label: '文件管理' },
    {
      type: 'group' as const,
      label: 'LLM 对话',
      children: [
        { key: '/llm/models', icon: <ClusterOutlined />, label: '模型广场' },
        { key: '/llm/chat', icon: <MessageOutlined />, label: 'AI 对话' },
        { key: '/llm/memories', icon: <BookOutlined />, label: '记忆管理' },
      ],
    },
    {
      type: 'group' as const,
      label: '知识库',
      children: [
        { key: '/rag/datasets', icon: <DatabaseOutlined />, label: '知识库管理' },
      ],
    },
    {
      type: 'group' as const,
      label: '个人中心',
      children: [
        { key: '/user/profile', icon: <UserOutlined />, label: '个人信息' },
        { key: '/user/quota', icon: <DashboardOutlined />, label: '配额管理' },
        { key: '/user/api-keys', icon: <KeyOutlined />, label: 'API Key' },
        { key: '/user/templates', icon: <FileTextOutlined />, label: '我的模板' },
      ],
    },
    {
      type: 'group' as const,
      label: '租户管理',
      children: [
        { key: '/tenant/info', icon: <TeamOutlined />, label: '租户信息' },
        ...(isAdmin
          ? [{ key: '/tenant/members' as const, icon: <UsergroupAddOutlined />, label: '成员管理' }]
          : []),
      ],
    },
    ...(isAdmin
      ? [
          {
            type: 'group' as const,
            label: '管理后台',
            children: [
              { key: '/admin/stats' as const, icon: <BarChartOutlined />, label: '系统统计' },
              { key: '/admin/review' as const, icon: <AuditOutlined />, label: '任务审核' },
              { key: '/admin/model-configs' as const, icon: <SettingOutlined />, label: '模型配置' },
              { key: '/admin/audit-logs' as const, icon: <FileSearchOutlined />, label: '审计日志' },
            ],
          },
        ]
      : []),
  ];

  return (
    <Menu
      theme="dark"
      mode="inline"
      selectedKeys={[selectedKey]}
      defaultOpenKeys={['/workspace', '/llm', '/user', '/tenant', '/admin']}
      items={menuItems}
      onClick={({ key }) => navigate(key)}
    />
  );
};

export default SideMenu;
