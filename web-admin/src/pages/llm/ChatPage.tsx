import { useState, useRef, useEffect, useCallback } from 'react';
import {
  Card, Input, Button, Select, Typography, Space, Spin, Empty, Collapse, List, Modal, App,
} from 'antd';
import {
  SendOutlined, PlusOutlined, DeleteOutlined, EditOutlined, MessageOutlined,
} from '@ant-design/icons';
import ReactMarkdown from 'react-markdown';
import { llmApi } from '@/api/llm';
import DatasetSelect from '@/components/rag/DatasetSelect';
import { LlmModel, RagReference, Conversation, ChatMessageItem } from '@/types/api';

const { Title } = Typography;
const { TextArea } = Input;

interface Message {
  role: 'user' | 'assistant';
  content: string;
  time: string;
  references?: RagReference[];
}

const PROVIDER_NAMES: Record<string, string> = {
  openai: 'OpenAI', anthropic: 'Claude', deepseek: 'DeepSeek',
  qwen: '通义千问', baidu: '文心一言', spark: '讯飞星火',
  zhipu: '智谱GLM', moonshot: 'Kimi', google: 'Gemini', agnes: 'Agnes',
};

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

  useEffect(() => { chatEndRef.current?.scrollIntoView({ behavior: 'smooth' }); }, [messages]);

  // 选中会话 → 拉历史消息回填（以服务端为准）
  const selectConversation = async (conv: Conversation) => {
    setCurrentId(conv.id);
    setLoading(true);
    try {
      const res = await llmApi.listMessages(conv.id);
      const records = res.data.data.records;
      setMessages(records.map((m: ChatMessageItem) => ({
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
      message.error('删除失败');
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
      message.error('重命名失败');
    }
  };

  const handleSend = async () => {
    if (!input.trim() || loading) return;
    const userMsg: Message = { role: 'user', content: input.trim(), time: new Date().toLocaleTimeString() };
    setMessages((prev) => [...prev, userMsg]);
    setInput('');
    setLoading(true);

    try {
      const res = await llmApi.chat({
        provider: selectedProvider,
        message: userMsg.content,
        datasetId: selectedDataset,
        topK: 3,
        conversationId: currentId ?? undefined,
      });
      const data = res.data.data;
      const assistantMsg: Message = {
        role: 'assistant',
        content: data.content,
        time: new Date().toLocaleTimeString(),
        references: data.references,
      };
      // 以服务端返回为准：新会话用返回的 conversationId，并刷新会话列表
      if (currentId !== data.conversationId) {
        setCurrentId(data.conversationId ?? null);
        loadConversations();
      } else {
        loadConversations();
      }
      setMessages((prev) => [...prev, assistantMsg]);
    } catch (e) {
      message.error('对话失败，请重试');
      setMessages((prev) => prev.filter((m) => m !== userMsg));
    } finally {
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
          style={{ flex: 1, overflow: 'auto', marginBottom: 12, background: '#fafafa' }}
          styles={{ body: { padding: 16 } }}
        >
          {messages.length === 0 ? (
            <Empty description="开始一段对话" style={{ marginTop: 80 }} />
          ) : (
            messages.map((msg, i) => (
              <div key={i} style={{ marginBottom: 16, textAlign: msg.role === 'user' ? 'right' : 'left' }}>
                <div style={{
                  display: 'inline-block',
                  maxWidth: '80%',
                  padding: '8px 16px',
                  borderRadius: 12,
                  background: msg.role === 'user' ? '#1677ff' : '#fff',
                  color: msg.role === 'user' ? '#fff' : '#000',
                  boxShadow: '0 1px 4px rgba(0,0,0,0.08)',
                  textAlign: 'left',
                }}>
                  {msg.role === 'assistant' ? (
                    <ReactMarkdown>{msg.content}</ReactMarkdown>
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
                      label: `参考资料（${msg.references.length} 条）`,
                      children: msg.references.map((ref) => (
                        <div key={ref.chunkId} style={{ marginBottom: 8, fontSize: 12 }}>
                          <div style={{ color: '#1677ff' }}>
                            [{ref.documentName}] 相似度 {(ref.similarity * 100).toFixed(1)}%
                          </div>
                          <div style={{ color: '#666', marginTop: 2 }}>{ref.content}</div>
                        </div>
                      )),
                    }]}
                  />
                )}
                <div style={{ fontSize: 12, color: '#999', marginTop: 4 }}>{msg.time}</div>
              </div>
            ))
          )}
          {loading && <div style={{ textAlign: 'center' }}><Spin /></div>}
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
          <Button type="primary" icon={<SendOutlined />} onClick={handleSend} loading={loading} style={{ height: 'auto' }}>
            发送
          </Button>
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
