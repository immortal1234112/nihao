import React, { useCallback, useEffect, useState } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { Spin } from 'antd';
import { useAuthStore } from '@/stores/authStore';
import http from '@/api/client';

const WHITE_LIST = ['/login', '/register'];

interface AuthGuardProps {
  children: React.ReactNode;
  requiredRoles?: string[];
}

const AuthGuard: React.FC<AuthGuardProps> = ({ children, requiredRoles }) => {
  const { isAuthenticated, user, setUser, logout } = useAuthStore();
  const location = useLocation();
  const [loading, setLoading] = useState(true);
  const [authorized, setAuthorized] = useState(true);

  const checkRole = useCallback((roles: string[]) => {
    if (requiredRoles && requiredRoles.length > 0) {
      const hasRole = requiredRoles.some((r) => roles.includes(r));
      setAuthorized(hasRole);
    }
  }, [requiredRoles]);

  useEffect(() => {
    const checkAuth = async () => {
      if (WHITE_LIST.includes(location.pathname)) {
        setLoading(false);
        return;
      }

      if (!isAuthenticated) {
        setLoading(false);
        return;
      }

      // 已有用户信息则直接校验角色
      if (user) {
        checkRole(user.roles || []);
        setLoading(false);
        return;
      }

      // 获取用户信息
      try {
        const res = await http.get('/users/me');
        const userData = res.data.data;
        const userInfo = {
          id: userData.id,
          username: userData.username,
          email: userData.email,
          phone: userData.phone || '',
          avatarUrl: userData.avatarUrl || '',
          status: userData.status,
          roles: userData.roles || [],
          tenantId: userData.tenantId,
        };
        setUser(userInfo);
        checkRole(userInfo.roles);
      } catch {
        logout();
      } finally {
        setLoading(false);
      }
    };

    checkAuth();
  }, [location.pathname, isAuthenticated, user, setUser, logout, checkRole]);

  if (loading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100vh' }}>
        {/* tip 需配合嵌套子元素使用（antd 嵌套模式），避免无 children 警告 */}
        <Spin size="large" tip="加载中...">
          <div style={{ height: 160 }} />
        </Spin>
      </div>
    );
  }

  if (WHITE_LIST.includes(location.pathname)) {
    return <>{children}</>;
  }

  if (!isAuthenticated) {
    return <Navigate to="/login" state={{ from: location }} replace />;
  }

  if (!authorized) {
    return <Navigate to="/dashboard" replace />;
  }

  return <>{children}</>;
};

export default AuthGuard;
