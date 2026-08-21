import type { ThemeConfig } from 'antd';

/**
 * 全局主题 token。
 * 主色采用青绿色作为工作台强调色，搭配暖黄色品牌标记，避免全站只剩单一紫色。
 * 暗色模式预留：后续可在此处按 isDark 分支切换 token，配合 ConfigProvider 的 algorithm。
 */
export const themeConfig: ThemeConfig = {
  token: {
    colorPrimary: '#0f766e',
    colorInfo: '#0f766e',
    borderRadius: 8,
    colorBgLayout: '#f3f6f8',
    fontFamily:
      "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', sans-serif",
  },
  components: {
    Layout: {
      siderBg: '#102a33',
      headerBg: '#ffffff',
      headerHeight: 56,
    },
    Menu: {
      darkItemBg: '#102a33',
      darkSubMenuItemBg: '#0d222a',
      darkItemSelectedBg: '#0f766e',
      darkItemHoverBg: '#17404a',
    },
    Table: {
      headerBg: '#fafafa',
    },
  },
};
