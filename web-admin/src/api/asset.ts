import http from './client';
import { ApiResult, AssetLineage, AssetRow, ModelCapability, PageResult } from '@/types/api';

export interface AssetListParams {
  page?: number;
  size?: number;
  projectId?: string;
  assetType?: string;
  status?: string;
  tag?: string;
  starred?: boolean;
  keyword?: string;
  derivationType?: string;
}

export const assetApi = {
  list: (params: AssetListParams) =>
    http.get<ApiResult<PageResult<AssetRow>>>('/assets', { params }),
  detail: (id: string) => http.get<ApiResult<AssetRow>>(`/assets/${id}`),
  lineage: (id: string) => http.get<ApiResult<AssetLineage>>(`/assets/${id}/lineage`),
  listByTask: (taskId: string) => http.get<ApiResult<AssetRow[]>>(`/assets/by-task/${taskId}`),
  create: (data: {
    projectId?: string | null;
    parentAssetId?: string | null;
    derivationType: string;
    assetType: string;
    title?: string;
    description?: string;
    fileId?: string;
    contentText?: string;
    prompt?: string;
    modelProvider?: string;
    modelCode?: string;
    params?: Record<string, unknown>;
    tags?: string[];
  }) => http.post<ApiResult<AssetRow>>('/assets', data),
  update: (
    id: string,
    data: { title?: string; description?: string; projectId?: string | null; status?: string; tags?: string[] }
  ) => http.put<ApiResult<AssetRow>>(`/assets/${id}`, data),
  star: (id: string) => http.post<ApiResult<null>>(`/assets/${id}/star`),
  unstar: (id: string) => http.post<ApiResult<null>>(`/assets/${id}/unstar`),
  submitReview: (id: string) => http.post<ApiResult<null>>(`/assets/${id}/submit-review`),
  review: (id: string, approved: boolean) =>
    http.post<ApiResult<null>>(`/assets/${id}/review`, null, { params: { approved } }),
  delete: (id: string) => http.delete<ApiResult<null>>(`/assets/${id}`),
};

export const modelCapabilityApi = {
  list: () => http.get<ApiResult<Record<string, ModelCapability[]>>>('/model-capabilities'),
};
