import http from './client';
import {
  ApiResult,
  CreativeBrief,
  PageResult,
  ProjectDetail,
  ProjectMember,
  ProjectRole,
  ProjectRow,
  ProjectTimeline,
} from '@/types/api';

export const projectApi = {
  list: (params: { page?: number; size?: number; scope?: 'mine' | 'all' }) =>
    http.get<ApiResult<PageResult<ProjectRow>>>('/projects', { params }),
  detail: (id: string) => http.get<ApiResult<ProjectDetail>>(`/projects/${id}`),
  create: (data: { name: string; deadline?: string | null }) =>
    http.post<ApiResult<ProjectRow>>('/projects', data),
  update: (id: string, data: { name?: string; coverAssetId?: string; deadline?: string | null }) =>
    http.put<ApiResult<ProjectRow>>(`/projects/${id}`, data),
  archive: (id: string) => http.post<ApiResult<null>>(`/projects/${id}/archive`),
  unarchive: (id: string) => http.post<ApiResult<null>>(`/projects/${id}/unarchive`),
  timeline: (id: string) => http.get<ApiResult<ProjectTimeline>>(`/projects/${id}/timeline`),
  // ===== 成员 =====
  listMembers: (id: string) => http.get<ApiResult<ProjectMember[]>>(`/projects/${id}/members`),
  addMember: (id: string, data: { userId: string; roleCode: ProjectRole }) =>
    http.post<ApiResult<ProjectMember>>(`/projects/${id}/members`, data),
  updateRole: (id: string, memberId: string, roleCode: ProjectRole) =>
    http.put<ApiResult<null>>(`/projects/${id}/members/${memberId}/role`, { roleCode }),
  removeMember: (id: string, memberId: string) =>
    http.delete<ApiResult<null>>(`/projects/${id}/members/${memberId}`),
  // ===== 简报 =====
  getBrief: (id: string) => http.get<ApiResult<CreativeBrief>>(`/projects/${id}/brief`),
  updateBrief: (
    id: string,
    data: { target?: string; style?: string; referenceAssets?: string; extra?: Record<string, unknown> }
  ) => http.put<ApiResult<null>>(`/projects/${id}/brief`, data),
  // ===== 知识库挂载 =====
  listDatasets: (id: string) =>
    http.get<ApiResult<Array<{ id: string; projectId: string; datasetId: string }>>>(
      `/projects/${id}/datasets`
    ),
  bindDataset: (id: string, datasetId: string) =>
    http.post<ApiResult<null>>(`/projects/${id}/datasets`, null, { params: { datasetId } }),
  unbindDataset: (id: string, datasetId: string) =>
    http.delete<ApiResult<null>>(`/projects/${id}/datasets`, { params: { datasetId } }),
};
