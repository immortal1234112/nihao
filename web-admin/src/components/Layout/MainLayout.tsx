import { useMemo, useState } from 'react';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { Layout, Button, Dropdown, Avatar, Flex, Typography, Tooltip } from 'antd';
import {
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  UserOutlined,
  LogoutOutlined,
  SettingOutlined,
} from '@ant-design/icons';
import { useAuthStore } from '@/stores/authStore';
import SideMenu from './SideMenu';
import styles from './MainLayout.module.css';

const { Header, Sider, Content } = Layout;

const PAGE_TITLES: Record<string, string> = {
  '/dashboard': '首页',
  '/projects': '项目',
  '/assets': '资产中心',
  '/workspace/image': '图片生成',
  '/workspace/video': '视频生成',
  '/workspace/batch': '批量生成',
  '/workspace/pipeline': '流水线',
  '/workspace/gallery': '作品画廊',
  '/tasks': '任务管理',
  '/files': '文件管理',
  '/llm/models': '模型广场',
  '/llm/chat': 'AI 对话',
  '/llm/memories': '记忆管理',
  '/rag/datasets': '知识库',
};

const MainLayout: React.FC = () => {
  const [collapsed, setCollapsed] = useState(false);
  const { user, logout } = useAuthStore();
  const navigate = useNavigate();
  const location = useLocation();

  const pageTitle = useMemo(() => {
    const exact = PAGE_TITLES[location.pathname];
    if (exact) return exact;
    const match = Object.entries(PAGE_TITLES).find(([path]) => location.pathname.startsWith(`${path}/`));
    return match?.[1] ?? '工作台';
  }, [location.pathname]);

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  const userMenuItems = {
    items: [
      {
        key: 'profile',
        icon: <UserOutlined />,
        label: '个人信息',
        onClick: () => navigate('/user/profile'),
      },
      {
        key: 'settings',
        icon: <SettingOutlined />,
        label: '配额管理',
        onClick: () => navigate('/user/quota'),
      },
      { type: 'divider' as const },
      {
        key: 'logout',
        icon: <LogoutOutlined />,
        label: '退出登录',
        onClick: handleLogout,
      },
    ],
  };

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider
        trigger={null}
        collapsible
        collapsed={collapsed}
        theme="dark"
        width={220}
        style={{
          overflow: 'auto',
          height: '100vh',
          position: 'fixed',
          left: 0,
          top: 0,
          bottom: 0,
          zIndex: 100,
        }}
      >
        <div className={styles.logo}>
          <span className={styles.logoMark}>A</span>
          {!collapsed && <span>AI Agent</span>}
        </div>
        <SideMenu />
      </Sider>
      <Layout className={styles.shell} style={{ marginLeft: collapsed ? 80 : 220 }}>
        <Header className={styles.header}>
          <Flex align="center" gap={16}>
            <Tooltip title={collapsed ? '展开导航' : '收起导航'}>
              <Button
                type="text"
                className={styles.menuButton}
                icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
                onClick={() => setCollapsed(!collapsed)}
              />
            </Tooltip>
            <div>
              <Typography.Text className={styles.eyebrow}>CREATIVE WORKSPACE</Typography.Text>
              <Typography.Title level={5} className={styles.pageTitle}>{pageTitle}</Typography.Title>
            </div>
          </Flex>
          <Dropdown menu={userMenuItems} placement="bottomRight">
            <Button type="text" className={styles.userButton}>
              <Avatar size={30} icon={<UserOutlined />} />
              <span>{user?.username || '用户'}</span>
            </Button>
          </Dropdown>
        </Header>
        <Content className={styles.content}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
};

export default MainLayout;
