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
  /** 作品名称（可选，画廊/项目展示用） */
  name?: string;
  /** 后端按提交端点注入（/image /video /batch /workflow），前端可省略 */
  workflowType?: WorkflowType;
  /** 后端按提交端点注入（/image /video），前端可省略 */
  engineType?: EngineType;
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
  /** Phase1 项目归属：项目 ID（可选） */
  projectId?: string;
  /** Phase1 输入资产：源资产 ID（可选，图生图/图生视频） */
  sourceAssetId?: string;
  /** 输入资产列表（多图合成，可选；传了优先于 sourceAssetId） */
  sourceAssetIds?: string[];
  /** Phase1 批量分组：批量任务分组 ID（可选） */
  batchGroupId?: string;
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
  /** 作品名称（用户生成时命名，可空） */
  name?: string | null;
  result: string | null;
  errorMsg: string | null;
  retryCount: number;
  priority: number;
  createdAt: string;
  updatedAt: string;
  completedAt: string | null;
  /** Phase1：项目归属 */
  projectId?: string | null;
  /** Phase1：源资产 ID */
  sourceAssetId?: string | null;
  /** Phase1：批量分组 ID */
  batchGroupId?: string | null;
  /** Phase1：估算成本 */
  cost?: number | null;
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
  /** 记忆空间 ID（可选）；缺省落到用户默认空间。会话已绑定时必须与绑定值一致 */
  spaceId?: string;
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
  /** 本次生效的记忆空间 */
  spaceId?: string;
}

// ========== 对话会话 ==========
export interface Conversation {
  id: string;
  title: string;
  provider: string;
  datasetId: string | null;
  /** 绑定的记忆空间（创建后不可变更） */
  spaceId: string | null;
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

// ========== 记忆空间 ==========
/**
 * 记忆空间：一套独立的「长期 + 短期」记忆。
 * 对话创建时绑定一个空间，该对话的注入与提取都限定在内，从而可同时开多个对话各自参考不同记忆。
 */
export interface MemorySpace {
  id: string;
  name: string;
  description: string;
  /** 默认空间：每用户唯一，不可删、不可改名 */
  isDefault: boolean;
  sortOrder: number;
  createdAt: string;
}

// ========== 长期记忆 ==========
export interface MemoryItem {
  id: string;
  content: string;
  memoryType: 'FACT' | 'PREFERENCE';
  category: 'LONG_TERM' | 'SHORT_TERM';
  /** 所属记忆空间 */
  spaceId?: string;
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
  /** 引用来源：'WEB' + url/site 仅为渲染**存量**历史消息保留（联网搜索已下线）；
   *  'RAG' 或 undefined = 知识库（更早的存量数据无此字段） */
  sourceType?: 'RAG' | 'WEB';
  /** 网页地址，仅 sourceType='WEB' */
  url?: string;
  /** 站点名，仅 sourceType='WEB' */
  site?: string;
}

// ========== 流式对话（SSE）事件载荷 ==========

/** 首帧：会话已就绪，前端可立刻高亮侧栏并刷新会话列表 */
export interface ChatStreamMeta {
  conversationId: string;
  title: string;
  provider: string;
  model: string;
  /** 本次生效的记忆空间 */
  spaceId?: string;
}

/** 流水线阶段变化：目前只有 generate = 模型开始生成 */
export interface ChatStreamPhase {
  phase: 'generate';
  status: 'start' | 'done' | 'failed';
  count: number;
  message?: string | null;
}

export interface ChatStreamReferences {
  references: RagReference[];
  /** 存量历史消息的引用里可能含 WEB 来源，故计数保留 */
  webCount: number;
  ragCount: number;
}

/** 收尾帧：content 是服务端权威全文，用它覆盖本地累积串可免疫漏帧/渲染漂移 */
export interface ChatStreamDone {
  messageId: string;
  conversationId: string;
  durationMs: number;
  content: string;
  references: RagReference[];
  /** 本次生效的记忆空间 */
  spaceId?: string;
}

export interface ChatStreamError {
  code: number;
  message: string;
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

// ========== 项目 ==========
export type ProjectRole = 'OWNER' | 'EDITOR' | 'REVIEWER' | 'VIEWER';

export interface ProjectRow {
  id: string;
  name: string;
  status: string;
  deadline: string | null;
  coverAssetId: string | null;
  memberCount: number;
  assetCount: number;
  createdAt: string;
}

export interface CreativeBrief {
  id: string;
  projectId: string;
  target: string | null;
  style: string | null;
  referenceAssets: string | null;
  extra: string | null;
  updatedAt: string | null;
}

export interface ProjectDetail {
  id: string;
  tenantId: string;
  name: string;
  status: string;
  deadline: string | null;
  coverAssetId: string | null;
  brief: CreativeBrief | null;
  memberCount: number;
  assetCount: number;
  createdAt: string;
}

export interface ProjectMember {
  id: string;
  projectId: string;
  userId: string;
  roleCode: ProjectRole;
  createdAt: string;
}

export type TimelineEventType = 'TASK' | 'ASSET';

export interface TimelineEvent {
  type: TimelineEventType;
  id: string;
  title: string;
  status?: string;
  assetType?: string;
  derivationType?: string;
  parentAssetId?: string | null;
  batchGroupId?: string | null;
  createdAt: string;
}

export interface ProjectTimeline {
  events: TimelineEvent[];
  taskCount: number;
  assetCount: number;
}

// ========== 资产 ==========
export type AssetType = 'IMAGE' | 'VIDEO' | 'AUDIO' | 'TEXT' | 'FILE';
export type AssetStatus = 'DRAFT' | 'UNDER_REVIEW' | 'APPROVED' | 'REJECTED';

export interface AssetRow {
  id: string;
  projectId: string | null;
  parentAssetId: string | null;
  derivationType: string;
  assetType: AssetType;
  title: string;
  fileId: string | null;
  thumbFileId: string | null;
  status: AssetStatus;
  starred: number;
  createdAt: string;
  createdBy: string;
  description?: string;
  contentText?: string;
  prompt?: string;
  modelProvider?: string;
  modelCode?: string;
  params?: string;
  tags?: string[];
  file?: {
    id: string;
    url: string;
    contentType: string;
    fileSize: number;
    width: number | null;
    height: number | null;
    durationMs: number | null;
  };
}

export interface AssetLineage {
  asset: AssetRow;
  parents: Array<{ id: string; title: string; derivationType: string; deleted: boolean }>;
  children: AssetRow[];
  tasks: Array<{
    taskId: string;
    relationType: string;
    status: string;
    workflowType: string;
    createdAt: string;
  }>;
}

export interface ModelCapability {
  id: string;
  capability: string;
  provider: string;
  modelCode: string;
  label: string;
  tier: string;
  priority: number;
  costPerUnit: number;
  costUnit: string;
  enabled: number;
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
