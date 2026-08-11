import http from './client';
import {
  ApiResult,
  PageResult,
  RagChunk,
  RagDataset,
  RagDocument,
  RagEmbeddingProvider,
  RagSearchResult,
} from '@/types/api';

/** RAG 知识库 API */
export const ragApi = {
  listDatasets: (params?: { page?: number; size?: number }) =>
    http.get<ApiResult<PageResult<RagDataset>>>('/rag/datasets', { params }),
  getDataset: (id: string) => http.get<ApiResult<RagDataset>>(`/rag/datasets/${id}`),
  createDataset: (data: {
    name: string;
    description?: string;
    embeddingProvider?: string;
    embeddingModel?: string;
  }) => http.post<ApiResult<RagDataset>>('/rag/datasets', data),
  updateDataset: (id: string, data: { name?: string; description?: string }) =>
    http.put<ApiResult<void>>(`/rag/datasets/${id}`, data),
  deleteDataset: (id: string) => http.delete<ApiResult<void>>(`/rag/datasets/${id}`),

  uploadDocument: (datasetId: string, file: File) => {
    const formData = new FormData();
    formData.append('file', file);
    return http.post<ApiResult<RagDocument>>(`/rag/datasets/${datasetId}/documents`, formData);
  },
  listDocuments: (datasetId: string, params?: { page?: number; size?: number }) =>
    http.get<ApiResult<PageResult<RagDocument>>>(`/rag/datasets/${datasetId}/documents`, { params }),
  deleteDocument: (datasetId: string, docId: string) =>
    http.delete<ApiResult<void>>(`/rag/datasets/${datasetId}/documents/${docId}`),
  reparse: (docId: string) => http.post<ApiResult<void>>(`/rag/documents/${docId}/reparse`),

  listChunks: (docId: string, params?: { page?: number; size?: number }) =>
    http.get<ApiResult<PageResult<RagChunk>>>(`/rag/documents/${docId}/chunks`, { params }),

  search: (data: { datasetId: string; query: string; topK?: number }) =>
    http.post<ApiResult<RagSearchResult>>('/rag/search', data),

  listEmbeddingProviders: () => http.get<ApiResult<RagEmbeddingProvider[]>>('/rag/embedding-providers'),
};
