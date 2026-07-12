import http from './client';
import { ApiResult, PageResult, StorageFile, UploadResult } from '@/types/api';

export const fileApi = {
  upload: (file: File, storageClass: string = 'ASSET') => {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('storageClass', storageClass);
    return http.post<ApiResult<UploadResult>>('/files/upload', formData);
  },
  getPresignedUrl: (filename: string, contentType: string = 'application/octet-stream') =>
    http.get<ApiResult<{ uploadUrl: string }>>('/files/presign', { params: { filename, contentType } }),
  presignCallback: (fileId: string) =>
    http.post<ApiResult<null>>('/files/presign/callback', null, { params: { fileId } }),
  list: (params: { page?: number; size?: number }) =>
    http.get<ApiResult<PageResult<StorageFile>>>('/files', { params }),
  getById: (id: string) => http.get<ApiResult<StorageFile>>(`/files/${id}`),
  getDownloadUrl: (id: string) => `/api/v1/files/${id}/download`,
  delete: (id: string) => http.delete<ApiResult<null>>(`/files/${id}`),
};
