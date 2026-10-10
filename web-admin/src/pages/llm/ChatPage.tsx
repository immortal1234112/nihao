import { useState, useRef, useEffect, useCallback } from 'react';
import {
  Card, Input, Button, Select, Space, Empty, Collapse, List, Modal, App,
} from 'antd';
import {
  SendOutlined, PlusOutlined, DeleteOutlined, EditOutlined, MessageOutlined,
  StopOutlined,
} from '@ant-design/icons';
import ReactMarkdown from 'react-markdown';
import { llmApi } from '@/api/llm';
import { SseAbortedError, SseRequestError } from '@/api/sse';
import DatasetSelect from '@/components/rag/DatasetSelect';
import { LlmModel, RagReference, Conversation, ChatMessageItem } from '@/types/api';
import './ChatPage.css';

const { TextArea } = Input;

interface Message {
  /** 本地稳定标识：流式期间按 id 更新，避免用下标在并发更新中错位 */
  id: string;
  role: 'user' | 'assistant';
  content: string;
  time: string;
  references?: RagReference[];
  /** 正在流式接收：显示阶段条与光标 */
  streaming?: boolean;
  /** 流水线阶段文案（生成中…） */
  phase?: string;
  /** 失败提示；内容可能已部分生成，故与正文并存而非替换 */
  error?: string;
}

const PROVIDER_NAMES: Record<string, string> = {
  openai: 'OpenAI', anthropic: 'Claude', deepseek: 'DeepSeek',
  qwen: '通义千问', baidu: '文心一言', spark: '讯飞星火',
  zhipu: '智谱GLM', moonshot: 'Kimi', google: 'Gemini', agnes: 'Agnes',
};

let idSeq = 0;
const nextId = () => `m${++idSeq}`;

const ChatPage: React.FC = () => {
  const { message } = App.useApp();
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  const [models, setModels] = useState<LlmModel[]>([]);
  const [selectedProvider, setSelectedProvider] = useState<string>('');
  const [selectedDataset, setSelectedDataset] = useState<string | undefined>(undefined);
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [currentId, setCurrentId] = useState<string | null>(null);
  const [renaming, setRenaming] = useState<Conversation | null>(null);
  const [renameTitle, setRenameTitle] = useState('');
  const chatEndRef = useRef<HTMLDivElement>(null);
  /** 「停止」按钮用：中断 fetch 流 */
  const abortRef = useRef<AbortController | null>(null);

  const loadConversations = useCallback(() => {
    llmApi.listConversations().then((res) => {
      setConversations(res.data.data.records);
    }).catch(() => {});
  }, []);

  useEffect(() => {
    llmApi.listModels().then((res) => {
      const available = res.data.data.filter((m) => m.available);
      setModels(available);
      if (available.length > 0) setSelectedProvider(available[0].provider);
    }).catch(() => {});
    loadConversations();
  }, [loadConversations]);

  // 流式期间每帧都会更新 messages：此时用瞬时滚动跟随，平滑滚动在高频调用下会互相打断
  useEffect(() => {
    chatEndRef.current?.scrollIntoView({ behavior: loading ? 'auto' : 'smooth' });
  }, [messages, loading]);

  // 卸载时中断在途的流，避免请求悬挂到服务端超时
  useEffect(() => () => abortRef.current?.abort(), []);

  // 选中会话 → 拉历史消息回填（以服务端为准）
  const selectConversation = async (conv: Conversation) => {
    setCurrentId(conv.id);
    setLoading(true);
    try {
      const res = await llmApi.listMessages(conv.id);
      const records = res.data.data.records;
      setMessages(records.map((m: ChatMessageItem) => ({
        id: nextId(),
        role: m.role,
        content: m.content,
        time: m.createdAt ? m.createdAt.slice(11, 16) : '',
        references: m.references,
      })));
    } catch {
      setMessages([]);
    } finally {
      setLoading(false);
    }
  };

  const handleNewChat = () => {
    setCurrentId(null);
    setMessages([]);
    setSelectedDataset(undefined);
  };

  const handleDeleteConversation = async (conv: Conversation) => {
    try {
      await llmApi.deleteConversation(conv.id);
      message.success('会话已删除');
      loadConversations();
      if (currentId === conv.id) handleNewChat();
    } catch {
      // 失败原因已由拦截器提示
    }
  };

  const handleRename = async () => {
    if (!renaming || !renameTitle.trim()) return;
    try {
      await llmApi.renameConversation(renaming.id, renameTitle.trim());
      message.success('已重命名');
      setRenaming(null);
      loadConversations();
    } catch {
      // 失败原因已由拦截器提示
    }
  };

  const handleStop = () => {
    abortRef.current?.abort();
    setLoading(false);
    setMessages((prev) => prev.map((m) => (
      m.streaming ? { ...m, streaming: false, phase: undefined } : m
    )));
  };

  const handleSend = async () => {
    if (!input.trim() || loading) return;
    const text = input.trim();
    const userMsg: Message = { id: nextId(), role: 'user', content: text, time: new Date().toLocaleTimeString() };
    const assistantId = nextId();
    setMessages((prev) => [...prev, userMsg, {
      id: assistantId, role: 'assistant', content: '', time: '', streaming: true,
    }]);
    setInput('');
    setLoading(true);

    const controller = new AbortController();
    abortRef.current = controller;

    // 逐字渲染节流：每个 delta 都 setState 会让长回答每帧重渲染整段 Markdown。
    // 用 rAF 合并到每帧最多一次；done 帧会用服务端权威全文整体覆盖，剩余缓冲直接丢弃。
    let buffered = '';
    let rafId: number | null = null;
    const patch = (updater: (m: Message) => Message) =>
      setMessages((prev) => prev.map((m) => (m.id === assistantId ? updater(m) : m)));
    const flush = () => {
      if (rafId !== null) { cancelAnimationFrame(rafId); rafId = null; }
      if (!buffered) return;
      const chunk = buffered;
      buffered = '';
      // 首个 delta 到达即收起阶段条：正文开始产出后它就没有信息量了
      patch((m) => ({ ...m, content: m.content + chunk, phase: undefined }));
    };

    try {
      await llmApi.chatStream(
        {
          provider: selectedProvider,
          message: text,
          datasetId: selectedDataset,
          topK: 3,
          conversationId: currentId ?? undefined,
        },
        {
          onMeta: (meta) => {
            // 新会话：拿到服务端 id 后立刻高亮并刷新侧栏
            if (meta.conversationId !== currentId) {
              setCurrentId(meta.conversationId);
            }
            loadConversations();
          },
          onPhase: (p) => {
            if (p.phase !== 'generate' || p.status !== 'start') return;
            // 上下文组装（含 RAG 检索）已完成，模型开始产出——此时给一行明确文案
            patch((m) => ({ ...m, phase: '正在生成回答…' }));
          },
          onReferences: (refs) => {
            patch((m) => ({ ...m, references: refs.references }));
          },
          onDelta: (chunk) => {
            buffered += chunk;
            if (rafId === null) rafId = requestAnimationFrame(() => { rafId = null; flush(); });
          },
          onDone: (done) => {
            if (rafId !== null) { cancelAnimationFrame(rafId); rafId = null; }
            buffered = '';
            patch((m) => ({
              ...m,
              // 服务端全文为准，天然免疫漏帧
              content: done.content,
              references: done.references?.length ? done.references : m.references,
              time: new Date().toLocaleTimeString(),
              streaming: false,
              phase: undefined,
            }));
            loadConversations();
          },
          onError: (err) => {
            if (rafId !== null) { cancelAnimationFrame(rafId); rafId = null; }
            flush();
            patch((m) => ({ ...m, streaming: false, phase: undefined, error: err.message }));
          },
        },
        controller.signal,
      );
    } catch (e) {
      if (rafId !== null) { cancelAnimationFrame(rafId); rafId = null; }
      flush();
      if (e instanceof SseAbortedError) {
        // 用户主动停止：已生成的内容与服务端落库的部分内容一致，保留即可
        patch((m) => ({ ...m, streaming: false, phase: undefined }));
      } else {
        // 流建立前失败（鉴权/参数/模型不可用）。注意用户消息此时可能已落库，
        // 不本地回滚——回滚会与服务端不一致，改为在气泡里就地报错。
        patch((m) => ({
          ...m,
          streaming: false,
          phase: undefined,
          error: e instanceof SseRequestError ? e.message : '连接中断，请稍后重试',
        }));
      }
    } finally {
      abortRef.current = null;
      setLoading(false);
    }
  };

  return (
    <div style={{ display: 'flex', height: 'calc(100vh - 200px)', gap: 16 }}>
      {/* 左侧会话列表 */}
      <Card style={{ width: 240, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}
        styles={{ body: { padding: 8, flex: 1, overflow: 'auto', display: 'flex', flexDirection: 'column' } }}>
        <Button type="primary" icon={<PlusOutlined />} block onClick={handleNewChat} style={{ marginBottom: 8 }}>
          新建对话
        </Button>
        <List
          dataSource={conversations}
          size="small"
          locale={{ emptyText: <Empty description="暂无会话" image={Empty.PRESENTED_IMAGE_SIMPLE} /> }}
          renderItem={(conv) => (
            <div
              key={conv.id}
              onClick={() => selectConversation(conv)}
              style={{
                padding: '8px 10px',
                borderRadius: 8,
                cursor: 'pointer',
                marginBottom: 4,
                background: currentId === conv.id ? '#e6f4ff' : 'transparent',
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'center',
              }}
            >
              <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', fontSize: 13 }}>
                <MessageOutlined style={{ marginRight: 6, color: '#999' }} />
                {conv.title}
              </span>
              <Space size={2} onClick={(e) => e.stopPropagation()}>
                <Button type="text" size="small" icon={<EditOutlined />}
                  onClick={() => { setRenaming(conv); setRenameTitle(conv.title); }} />
                <Button type="text" size="small" danger icon={<DeleteOutlined />}
                  onClick={() => handleDeleteConversation(conv)} />
              </Space>
            </div>
          )}
        />
      </Card>

      {/* 右侧聊天区 */}
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 12 }}>
          <Space>
            <Select
              value={selectedProvider}
              onChange={setSelectedProvider}
              style={{ width: 220 }}
              options={models.map((m) => ({
                value: m.provider,
                label: `${PROVIDER_NAMES[m.provider] || m.provider} — ${m.model}`,
              }))}
            />
            <DatasetSelect
              value={selectedDataset}
              onChange={setSelectedDataset}
              style={{ width: 180 }}
              placeholder="知识库（可选，RAG 增强）"
            />
          </Space>
        </div>

        <Card
          style={{
            flex: 1,
            overflow: 'auto',
            marginBottom: 12,
            position: 'relative',
            backgroundImage: 'url("/chat-bg.png")',
            backgroundSize: 'cover',
            backgroundPosition: 'center',
            backgroundRepeat: 'no-repeat',
          }}
          styles={{ body: {
            padding: 16,
            position: 'relative',
            zIndex: 1,
          } }}
        >
          {/* 流星特效层 */}
          <div className="shooting-stars-container"></div>

          {messages.length === 0 ? (
            <Empty description="开始一段对话" style={{ marginTop: 80 }} />
          ) : (
            messages.map((msg) => (
              <div
                key={msg.id}
                className={`chat-message-wrapper ${msg.role === 'user' ? 'user-message' : 'assistant-message'}`}
                style={{
                  marginBottom: 16,
                  textAlign: msg.role === 'user' ? 'right' : 'left',
                  animation: 'messageSlideIn 0.4s ease-out'
                }}
              >
                <div className={`chat-bubble ${msg.role === 'user' ? 'user-bubble' : 'assistant-bubble'}`}>
                  {msg.role === 'assistant' ? (
                    <>
                      {/* 阶段状态条：上下文组装 + 首个 token 之前的等待有明确进度，而不是一个转圈 */}
                      {msg.streaming && msg.phase && (
                        <div className="chat-phase">
                          <span className="chat-phase-dot" />
                          <span>{msg.phase}</span>
                        </div>
                      )}
                      {msg.content
                        ? <ReactMarkdown>{msg.content}</ReactMarkdown>
                        : msg.streaming && !msg.phase
                          ? <span className="chat-typing"><i /><i /><i /></span>
                          : null}
                      {msg.streaming && msg.content && <span className="chat-caret" />}
                      {msg.error && <div className="chat-error">{msg.error}</div>}
                    </>
                  ) : (
                    msg.content
                  )}
                </div>
                {msg.role === 'assistant' && msg.references && msg.references.length > 0 && (
                  <Collapse
                    size="small"
                    ghost
                    style={{ marginTop: 4, maxWidth: '80%', display: 'inline-block' }}
                    items={[{
                      key: 'refs',
                      label: `参考资料（${msg.references.length} 条${
                        msg.references.some((r) => r.sourceType === 'WEB')
                          ? ` · 含 ${msg.references.filter((r) => r.sourceType === 'WEB').length} 条网页`
                          : ''
                      }）`,
                      children: msg.references.map((ref, idx) => (
                        ref.sourceType === 'WEB' ? (
                          <div key={ref.url || ref.chunkId || idx} style={{ marginBottom: 8, fontSize: 12 }}>
                            <a href={ref.url} target="_blank" rel="noreferrer" className="chat-ref-web">
                              [{idx + 1}] {ref.documentName}
                            </a>
                            {ref.site && <span className="chat-ref-site">{ref.site}</span>}
                            <div style={{ color: '#666', marginTop: 2 }}>{ref.content}</div>
                          </div>
                        ) : (
                          <div key={ref.chunkId || idx} style={{ marginBottom: 8, fontSize: 12 }}>
                            <div style={{ color: '#1677ff' }}>
                              [{ref.documentName}] 相似度 {(ref.similarity * 100).toFixed(1)}%
                            </div>
                            <div style={{ color: '#666', marginTop: 2 }}>{ref.content}</div>
                          </div>
                        )
                      )),
                    }]}
                  />
                )}
                {msg.time && <div style={{ fontSize: 12, color: '#999', marginTop: 4 }}>{msg.time}</div>}
              </div>
            ))
          )}
          <div ref={chatEndRef} />
        </Card>

        <div style={{ display: 'flex', gap: 8 }}>
          <TextArea
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onPressEnter={(e) => { if (!e.shiftKey) { e.preventDefault(); handleSend(); } }}
            placeholder="输入消息，Enter 发送，Shift+Enter 换行"
            rows={3}
            disabled={loading}
            style={{ flex: 1 }}
          />
          {loading ? (
            <Button danger icon={<StopOutlined />} onClick={handleStop} style={{ height: 'auto' }}>
              停止
            </Button>
          ) : (
            <Button type="primary" icon={<SendOutlined />} onClick={handleSend} style={{ height: 'auto' }}>
              发送
            </Button>
          )}
        </div>
      </div>

      <Modal
        title="重命名会话"
        open={renaming !== null}
        onOk={handleRename}
        onCancel={() => setRenaming(null)}
        okText="保存"
        cancelText="取消"
      >
        <Input value={renameTitle} onChange={(e) => setRenameTitle(e.target.value)} placeholder="输入新标题" />
      </Modal>
    </div>
  );
};

export default ChatPage;
