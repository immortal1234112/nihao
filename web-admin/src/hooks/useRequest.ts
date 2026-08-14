import { useCallback, useEffect, useRef, useState } from 'react';

interface UseRequestOptions {
  /** true 时不自动执行，需手动调用 refresh */
  manual?: boolean;
}

/**
 * 统一数据请求 hook：消灭各页面重复的 useState+useEffect+loading 样板。
 *
 * 用法：
 *   const { data, loading, refresh } = useRequest(async () => {
 *     const res = await taskApi.list({ page: 1, size: 20 });
 *     return res.data.data;
 *   }, []);
 *
 * @param fetcher 请求函数（闭包捕获外部状态）
 * @param deps    依赖数组：变化时自动重取（等价于 useEffect 的 deps）
 */
export function useRequest<T>(
  fetcher: () => Promise<T>,
  deps: React.DependencyList = [],
  options: UseRequestOptions = {},
) {
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(!options.manual);
  const [error, setError] = useState<Error | null>(null);

  // 始终指向最新 fetcher，避免 deps 变化前的旧回调持有过期闭包
  const fetcherRef = useRef(fetcher);
  fetcherRef.current = fetcher;
  // 请求序号：新请求发起后，旧请求的结果一律丢弃。
  // 兼容 StrictMode 开发模式的双调用 effect（否则 cleanup 会把后续所有请求结果都判定为已取消）。
  const requestIdRef = useRef(0);

  const refresh = useCallback(async () => {
    const requestId = ++requestIdRef.current;
    setLoading(true);
    setError(null);
    try {
      const result = await fetcherRef.current();
      if (requestId === requestIdRef.current) setData(result);
      return result;
    } catch (e) {
      if (requestId === requestIdRef.current) setError(e as Error);
      throw e;
    } finally {
      if (requestId === requestIdRef.current) setLoading(false);
    }
    // deps 变化时重建回调并重取；fetcher 经 ref 读取避免过期闭包
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);

  useEffect(() => {
    if (!options.manual) {
      refresh().catch(() => {
        // 错误已由 axios 拦截器提示，此处仅防止未捕获 rejection
      });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [refresh]);

  return { data, loading, error, refresh };
}
