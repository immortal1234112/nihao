import { RouterProvider } from 'react-router-dom';
import { router } from '@/router';

const App = () => {
  // v7_startTransition：状态更新包一层 startTransition（v7 默认行为，v6 提前对齐消除弃用警告）
  return <RouterProvider router={router} future={{ v7_startTransition: true }} />;
};

export default App;
