import { lazy, Suspense } from 'react';
import { createBrowserRouter, Navigate } from 'react-router-dom';
import { Spin } from 'antd';
import AuthGuard from '@/components/AuthGuard';

const LazyLoad = (Component: React.LazyExoticComponent<React.ComponentType>) => (
  <Suspense fallback={<Spin size="large" style={{ display: 'block', margin: '200px auto' }} />}>
    <Component />
  </Suspense>
);

// 页面懒加载
const LoginPage = lazy(() => import('@/pages/auth/LoginPage'));
const RegisterPage = lazy(() => import('@/pages/auth/RegisterPage'));
const MainLayout = lazy(() => import('@/components/Layout/MainLayout'));
const DashboardPage = lazy(() => import('@/pages/dashboard/DashboardPage'));
const ImageGenPage = lazy(() => import('@/pages/workspace/ImageGenPage'));
const VideoGenPage = lazy(() => import('@/pages/workspace/VideoGenPage'));
const BatchGenPage = lazy(() => import('@/pages/workspace/BatchGenPage'));
const PipelinePage = lazy(() => import('@/pages/workspace/PipelinePage'));
const TaskListPage = lazy(() => import('@/pages/tasks/TaskListPage'));
const TaskDetailPage = lazy(() => import('@/pages/tasks/TaskDetailPage'));
const FileListPage = lazy(() => import('@/pages/files/FileListPage'));
const ModelListPage = lazy(() => import('@/pages/llm/ModelListPage'));
const ChatPage = lazy(() => import('@/pages/llm/ChatPage'));
const ProfilePage = lazy(() => import('@/pages/user/ProfilePage'));
const QuotaPage = lazy(() => import('@/pages/user/QuotaPage'));
const ApiKeyPage = lazy(() => import('@/pages/user/ApiKeyPage'));
const TenantInfoPage = lazy(() => import('@/pages/tenant/TenantInfoPage'));
const MemberPage = lazy(() => import('@/pages/tenant/MemberPage'));
const StatsPage = lazy(() => import('@/pages/admin/StatsPage'));
const ReviewPage = lazy(() => import('@/pages/admin/ReviewPage'));

export const router = createBrowserRouter([
  {
    path: '/login',
    element: <AuthGuard>{LazyLoad(LoginPage)}</AuthGuard>,
  },
  {
    path: '/register',
    element: <AuthGuard>{LazyLoad(RegisterPage)}</AuthGuard>,
  },
  {
    path: '/',
    element: <AuthGuard>{LazyLoad(MainLayout)}</AuthGuard>,
    children: [
      { index: true, element: <Navigate to="/dashboard" replace /> },
      { path: 'dashboard', element: LazyLoad(DashboardPage) },
      { path: 'workspace/image', element: LazyLoad(ImageGenPage) },
      { path: 'workspace/video', element: LazyLoad(VideoGenPage) },
      { path: 'workspace/batch', element: LazyLoad(BatchGenPage) },
      { path: 'workspace/pipeline', element: LazyLoad(PipelinePage) },
      { path: 'tasks', element: LazyLoad(TaskListPage) },
      { path: 'tasks/:id', element: LazyLoad(TaskDetailPage) },
      { path: 'files', element: LazyLoad(FileListPage) },
      { path: 'llm/models', element: LazyLoad(ModelListPage) },
      { path: 'llm/chat', element: LazyLoad(ChatPage) },
      { path: 'user/profile', element: LazyLoad(ProfilePage) },
      { path: 'user/quota', element: LazyLoad(QuotaPage) },
      { path: 'user/api-keys', element: LazyLoad(ApiKeyPage) },
      { path: 'tenant/info', element: LazyLoad(TenantInfoPage) },
      {
        path: 'tenant/members',
        element: (
          <AuthGuard requiredRoles={['TENANT_ADMIN', 'SUPER_ADMIN']}>
            {LazyLoad(MemberPage)}
          </AuthGuard>
        ),
      },
      {
        path: 'admin/stats',
        element: (
          <AuthGuard requiredRoles={['TENANT_ADMIN', 'SUPER_ADMIN']}>
            {LazyLoad(StatsPage)}
          </AuthGuard>
        ),
      },
      {
        path: 'admin/review',
        element: (
          <AuthGuard requiredRoles={['TENANT_ADMIN', 'SUPER_ADMIN']}>
            {LazyLoad(ReviewPage)}
          </AuthGuard>
        ),
      },
    ],
  },
]);
