import React, { useEffect } from 'react';
import ReactDOM from 'react-dom/client';
import { ConfigProvider, App } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import AppRoot from './App';
import { themeConfig } from './styles/theme';
import { bindFeedback } from './utils/feedback';
import './styles/global.css';

/** 在 antd <App> 内部挂载 message 实例，供非组件环境（axios 拦截器）使用 */
function FeedbackBridge() {
  const { message } = App.useApp();
  useEffect(() => {
    bindFeedback({ message });
  }, [message]);
  return null;
}

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ConfigProvider locale={zhCN} theme={themeConfig}>
      <App>
        <FeedbackBridge />
        <AppRoot />
      </App>
    </ConfigProvider>
  </React.StrictMode>
);
