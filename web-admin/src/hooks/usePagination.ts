import { useCallback, useEffect, useRef, useState } from 'react';
import type { PageResult } from '@/types/api';

interface UsePaginationOptions {
  /** 每页条数，默认 20 */
  size?: number;
}

/**
 * 分页表格 hook：管理 page/size/records/total/loading + antd Table pagination 配置。
 *
 * 用法：
 *   const table = usePagination<AgentTask>(async (page, size) => {
 *     const res = await taskApi.list({ page, size, status: statusFilter });
 *     return res.data.data; // PageResult<T>
 *   }, [statusFilter]); // 筛选变化自动回到第 1 页并重取
 *
 * 内部用 tick 状态驱动 refresh：手动刷新（删除/取消后）只需调 refresh()。
 */
export function usePagination<T>(
  fetcher: (page: number, size: number) => Promise<PageResult<T>>,
  filters: React.DependencyList = [],
  options: UsePaginationOptions = {},
) {
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(options.size ?? 20);
  const [tick, setTick] = useState(0);
  const [records, setRecords] = useState<T[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);

  const fetcherRef = useRef(fetcher);
  fetcherRef.current = fetcher;
  const pageRef = useRef(page);
  pageRef.current = page;
  const sizeRef = useRef(size);
  sizeRef.current = size;
  // 请求序号：新请求发起后，旧请求的结果一律丢弃。
  // 兼容 StrictMode 开发模式的双调用 effect（否则 cleanup 会把后续所有请求结果都判定为已取消，loading 永远卡死）。
  const requestIdRef = useRef(0);

  /** 筛选变化时：回第 1 页（重置进行中，fetch 效果会跳过本轮避免双请求） */
  const resetRef = useRef(false);
  useEffect(() => {
    if (pageRef.current !== 1) {
      resetRef.current = true;
      setPage(1);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, filters);

  useEffect(() => {
    if (resetRef.current) {
      resetRef.current = false; // 页码重置触发的本轮跳过，由 page=1 的下一次触发真正取数
      // 但旧筛选/旧页码的在途请求仍可能返回：作废它，避免旧数据错显在新筛选下
      requestIdRef.current += 1;
      return;
    }
    setLoading(true);
    const p = pageRef.current;
    const s = sizeRef.current;
    const requestId = ++requestIdRef.current;
    fetcherRef.current(p, s)
      .then((res) => {
        if (requestId !== requestIdRef.current) return;
        setRecords(res.records);
        setTotal(res.pagination.total);
        // 删除末页最后一条后自动回退上一页
        if (p > 1 && res.pagination.total <= (p - 1) * s) setPage(p - 1);
      })
      .catch(() => {
        // 错误已由 axios 拦截器提示
      })
      .finally(() => {
        if (requestId === requestIdRef.current) setLoading(false);
      });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page, size, tick, ...filters]);

  const refresh = useCallback(() => setTick((t) => t + 1), []);

  /** antd Table 分页配置：<Table pagination={table.pagination} .../> */
  const pagination = {
    current: page,
    pageSize: size,
    total,
    showSizeChanger: true,
    showTotal: (t: number) => `共 ${t} 条`,
    onChange: (p: number, s: number) => {
      setPage(p);
      setSize(s);
    },
  };

  return { records, loading, total, page, size, pagination, refresh };
}
