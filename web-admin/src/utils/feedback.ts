import { App, message as staticMessage } from 'antd';
import type { MessageInstance } from 'antd/es/message/interface';

/**
 * antd 静态 message 无法消费动态主题上下文（控制台警告 + 样式漂移）。
 * 解决：在 antd <App> 内部把实例挂到模块级，供所有环境使用。
 * - 组件内用 useAppFeedback()（App.useApp() 的标准 hook 用法）
 * - 非组件环境（axios 拦截器等）用 getFeedbackMessage()
 */

let messageInstance: MessageInstance | null = null;

/** 由 main.tsx 的 FeedbackBridge（antd <App> 内部）调用 */
export function bindFeedback(instances: { message: MessageInstance }): void {
  messageInstance = instances.message;
}

/** 组件内使用：返回带主题上下文的 message/notification/modal */
export function useAppFeedback() {
  return App.useApp();
}

/** 非组件环境使用；未挂载前回退静态实例（正常情况 App 先于任何请求挂载） */
export function getFeedbackMessage(): MessageInstance {
  return messageInstance ?? staticMessage;
}
