// ========== 通用 ==========
export interface ApiResult<T = unknown> {
  code: number;
  message: string;
  data: T;
  requestId: string | null;
  timestamp: number;
}

export interface PageInfo {
  page: number;
  size: number;
  total: number;
  totalPages: number;
}

export interface PageResult<T> {
  records: T[];
  pagination: PageInfo;
}

// ========== 认证 ==========
export interface LoginRequest {
  username: string;
  password: string;
}

export interface RegisterRequest {
  username: string;
  password: string;
  email: string;
  phone?: string;
}

export interface TokenPair {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
}

export interface RefreshTokenRequest {
  refreshToken: string;
  /** 可选：登出时一并拉黑 access token */
  accessToken?: string;
}

// ========== 用户 ==========
export interface UserInfo {
  id: string;
  username: string;
  email: string;
  phone: string;
  avatarUrl: string;
  status: string;
  roles: string[];
  tenantId: string;
}

export interface UpdateUserRequest {
  email?: string;
  phone?: string;
  avatarUrl?: string;
}

export interface QuotaInfo {
  limit: number;
  used: number;
  remaining: number;
}

export interface ApiKey {
  id: string;
  name: string;
  keyPrefix: string;
  rateLimitQpm: number;
  lastUsedAt: string | null;
  expiresAt: string | null;
  status: string;
  createdAt: string;
}

export interface CreateApiKeyRequest {
  name: string;
  rateLimitQpm?: number;
  expiresAt?: string;
}

// ========== 任务 ==========
export type WorkflowType = 'SINGLE' | 'PIPELINE' | 'BATCH';
export type EngineType = 'IMAGE' | 'VIDEO';
export type TaskStatus =
  | 'CREATED' | 'VALIDATING' | 'PENDING' | 'PROCESSING'
  | 'DONE' | 'FAILED' | 'CANCELLED' | 'REVIEWING';

export interface TaskSubmitRequest {
  prompt: string;
  workflowType: WorkflowType;
  engineType: EngineType;
  modelProvider?: string;
  size?: string;
  count?: number;
  style?: string;
  format?: string;
  quality?: string;
  negativePrompt?: string;
  aspectRatio?: string;
  duration?: number;
  fps?: number;
  resolution?: string;
  batchCount?: number;
  pipelineSteps?: Array<{ stepType: string; [key: string]: unknown }>;
  /** RAG 知识库增强：知识库 ID（可选） */
  datasetId?: string;
  /** RAG 检索条数（可选） */
  ragTopK?: number;
}

// ========== 提示词模板 ==========
export interface PromptTemplate {
  id: string;
  name: string;
  category: string;
  engineType: 'IMAGE' | 'VIDEO' | 'ALL';
  prompt: string;
  negativePrompt: string | null;
  scope: 'PUBLIC' | 'TENANT' | 'PRIVATE';
  userId: string | null;
  tenantId: string | null;
  usageCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface PromptTemplateForm {
  name: string;
  category: string;
  engineType: 'IMAGE' | 'VIDEO' | 'ALL';
  prompt: string;
  negativePrompt?: string;
  scope: 'PUBLIC' | 'TENANT' | 'PRIVATE';
}

export interface AgentTask {
  id: string;
  userId: string;
  tenantId: string;
  workflowType: WorkflowType;
  status: TaskStatus;
  requestParams: string;
  result: string | null;
  errorMsg: string | null;
  retryCount: number;
  priority: number;
  createdAt: string;
  updatedAt: string;
  completedAt: string | null;
}

export interface WorkflowStep {
  id: string;
  taskId: string;
  stepOrder: number;
  stepType: string;
  status: string;
  input: string | null;
  output: string | null;
  errorMsg: string | null;
  startedAt: string | null;
  finishedAt: string | null;
}

export interface TaskDetail {
  task: AgentTask;
  steps: WorkflowStep[];
}

export interface TaskResult {
  status: TaskStatus;
  result: unknown;
  errorMsg: string | null;
  steps: WorkflowStep[];
}

// ========== 审计日志 ==========
export interface AuditLog {
  id: string;
  userId: string;
  tenantId: string;
  action: string;
  detail: string | null;
  ip: string | null;
  actionTime: string;
  result: 'SUCCESS' | 'FAIL';
}

// ========== 文件 ==========
export interface StorageFile {
  id: string;
  userId: string;
  tenantId: string;
  originalName: string;
  objectKey: string;
  contentType: string;
  fileSize: number;
  fileMd5: string | null;
  width: number | null;
  height: number | null;
  durationMs: number | null;
  thumbnailKey: string | null;
  provider: string;
  storageClass: string;
  visibility: string;
  expireAt: string | null;
  sourceTaskId: string | null;
  sourceType: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface UploadResult {
  id: string;
  objectKey: string;
  originalName: string;
  contentType: string;
  fileSize: number;
  storageClass: string;
}

// ========== LLM ==========
export interface LlmModel {
  model: string;
  provider: string;
  available: boolean;
}

export interface ChatRequest {
  provider?: string;
  systemPrompt?: string;
  message: string;
  options?: Record<string, unknown>;
  /** RAG 知识库增强：知识库 ID（可选） */
  datasetId?: string;
  /** RAG 检索条数（可选） */
  topK?: number;
  /** 会话 ID（续聊）；缺省自动创建新会话 */
  conversationId?: string;
}

export interface ChatResult {
  model: string;
  provider: string;
  content: string;
  durationMs: number;
  references?: RagReference[];
  /** 会话 ID（服务端为准） */
  conversationId?: string;
  /** 会话标题 */
  title?: string;
  /** assistant 消息 ID */
  messageId?: string;
}

// ========== 对话会话 ==========
export interface Conversation {
  id: string;
  title: string;
  provider: string;
  datasetId: string | null;
  systemPrompt: string | null;
  messageCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface ChatMessageItem {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  references?: RagReference[];
  createdAt: string;
}

// ========== 长期记忆 ==========
export interface MemoryItem {
  id: string;
  content: string;
  memoryType: 'FACT' | 'PREFERENCE';
  sourceConversationId: string;
  createdAt: string;
}

// ========== RAG 知识库 ==========
export interface RagDataset {
  id: string;
  name: string;
  description: string;
  tenantId: string;
  userId: string;
  embeddingProvider: string;
  embeddingModel: string;
  dimension: number;
  docCount: number;
  chunkCount: number;
  status: string;
  createdAt: string;
  updatedAt: string;
}

export interface RagDocument {
  id: string;
  datasetId: string;
  tenantId: string;
  fileName: string;
  fileSize: number;
  fileType: string;
  chunkCount: number;
  status: 'PARSING' | 'READY' | 'FAILED';
  errorMsg: string | null;
  parsedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface RagChunk {
  id: string;
  chunkIndex: number;
  content: string;
  documentId: string;
  documentName: string;
}

export interface RagReference {
  chunkId: string;
  content: string;
  similarity: number;
  documentId: string;
  documentName: string;
}

export interface RagSearchResult {
  query: string;
  chunks: RagReference[];
}

export interface RagEmbeddingProvider {
  provider: string;
  model: string;
  dimension: number;
  available: boolean;
}

// ========== 租户 ==========
export interface Tenant {
  id: string;
  name: string;
  status: string;
  quotaConfig: string;
  storageProvider: string;
  createdAt: string;
  updatedAt: string;
}

export interface TenantMember {
  userId: string;
  username: string;
  email: string;
  roleCode: string;
  joinedAt: string;
}

// ========== 管理 ==========
export interface TaskStats {
  total: number;
  pending: number;
  processing: number;
  done: number;
  failed: number;
  cancelled: number;
}

// ========== 错误码 ==========
export const ErrorCode = {
  PARAM_INVALID: 1001,
  TOKEN_EXPIRED: 2001,
  TOKEN_INVALID: 2002,
  NO_PERMISSION: 2003,
  API_KEY_INVALID: 2004,
  QUOTA_EXCEEDED: 3001,
  RATE_LIMITED: 3003,
  TASK_NOT_FOUND: 4001,
  FILE_NOT_FOUND: 4002,
  USER_NOT_FOUND: 4003,
  CONTENT_BLOCKED: 9001,
  SENSITIVE_PROMPT: 9002,
  INTERNAL_ERROR: 5099,
} as const;
