import type { ThemeConfig } from 'antd';

/**
 * 全局主题 token。
 * 主色 #6366f1（靛蓝）与登录页品牌渐变色一致，统一全站视觉。
 * 暗色模式预留：后续可在此处按 isDark 分支切换 token，配合 ConfigProvider 的 algorithm。
 */
export const themeConfig: ThemeConfig = {
  token: {
    colorPrimary: '#6366f1',
    colorInfo: '#6366f1',
    borderRadius: 8,
    colorBgLayout: '#f5f6fa',
    fontFamily:
      "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', sans-serif",
  },
  components: {
    Layout: {
      siderBg: '#141420',
      headerBg: '#ffffff',
      headerHeight: 56,
    },
    Menu: {
      darkItemBg: '#141420',
      darkSubMenuItemBg: '#10101a',
      darkItemSelectedBg: 'rgba(99,102,241,0.85)',
    },
    Table: {
      headerBg: '#fafafa',
    },
  },
};
