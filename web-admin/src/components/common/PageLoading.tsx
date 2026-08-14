import { Spin } from 'antd';

interface PageLoadingProps {
  /** 顶部留白，默认 100px（沿用各页现有视觉） */
  top?: number;
}

/** 页面级居中加载态（替代各页重复的内联 Spin 写法） */
const PageLoading: React.FC<PageLoadingProps> = ({ top = 100 }) => (
  <Spin size="large" style={{ display: 'block', margin: `${top}px auto` }} />
);

export default PageLoading;
