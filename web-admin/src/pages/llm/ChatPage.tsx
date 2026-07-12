import { useState, useRef, useEffect } from 'react';
import { Card, Input, Button, Select, Typography, Space, Spin, Empty } from 'antd';
import { SendOutlined, DeleteOutlined } from '@ant-design/icons';
import ReactMarkdown from 'react-markdown';
import { llmApi } from '@/api/llm';
import { LlmModel } from '@/types/api';

const { Title } = Typography;
const { TextArea } = Input;

interface Message {
  role: 'user' | 'assistant';
  content: string;
  time: string;
}

const ChatPage: React.FC = () => {
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  const [models, setModels] = useState<LlmModel[]>([]);
  const [selectedProvider, setSelectedProvider] = useState<string>('');
  const chatEndRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    llmApi.listModels().then((res) => {
      const available = res.data.data.filter((m) => m.available);
      setModels(available);
      if (available.length > 0) setSelectedProvider(available[0].provider);
    }).catch(() => {});
  }, []);

  useEffect(() => { chatEndRef.current?.scrollIntoView({ behavior: 'smooth' }); }, [messages]);

  const handleSend = async () => {
    if (!input.trim() || loading) return;
    const userMsg: Message = { role: 'user', content: input.trim(), time: new Date().toLocaleTimeString() };
    setMessages((prev) => [...prev, userMsg]);
    setInput('');
    setLoading(true);

    try {
      const res = await llmApi.chat({ provider: selectedProvider, message: userMsg.content });
      const assistantMsg: Message = {
        role: 'assistant',
        content: res.data.data.content,
        time: new Date().toLocaleTimeString(),
      };
      setMessages((prev) => [...prev, assistantMsg]);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div style={{ display: 'flex', height: 'calc(100vh - 200px)', gap: 16 }}>
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 12 }}>
          <Space>
            <Select
              value={selectedProvider}
              onChange={setSelectedProvider}
              style={{ width: 220 }}
              options={models.map((m) => {
                const names: Record<string, string> = {
                  openai:'OpenAI', anthropic:'Claude', deepseek:'DeepSeek',
                  qwen:'通义千问', baidu:'文心一言', spark:'讯飞星火',
                  zhipu:'智谱GLM', moonshot:'Kimi', google:'Gemini'
                };
                return { value: m.provider, label: `${names[m.provider] || m.provider} — ${m.model}` };
              })}
            />
          </Space>
          <Button icon={<DeleteOutlined />} onClick={() => setMessages([])} disabled={messages.length === 0}>
            清空对话
          </Button>
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
    </div>
  );
};

export default ChatPage;
