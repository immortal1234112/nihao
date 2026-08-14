import { useMemo, useState } from 'react';
import { Empty, Flex, Input, List, Modal, Select, Tag, Tooltip, Typography } from 'antd';
import { FileTextOutlined } from '@ant-design/icons';
import { templateApi } from '@/api/template';
import { PromptTemplate } from '@/types/api';
import { useRequest } from '@/hooks/useRequest';

const { Text } = Typography;

interface PromptTemplateModalProps {
  open: boolean;
  /** 当前创作页的引擎类型，用于过滤适用模板 */
  engineType: 'IMAGE' | 'VIDEO';
  onClose: () => void;
  /** 点击模板后回调（同时自动上报使用计数） */
  onSelect: (template: PromptTemplate) => void;
}

const CATEGORIES = ['摄影', '插画', '国风', '赛博朋克', '动漫', '产品', '海报', '人像', '风景', '视频'];

/**
 * 提示词模板弹窗 — 创作页「模板」按钮：分类 + 搜索 + 点击一键填充提示词。
 */
const PromptTemplateModal: React.FC<PromptTemplateModalProps> = ({ open, engineType, onClose, onSelect }) => {
  const { data, loading } = useRequest(
    async () => {
      const res = await templateApi.list({ page: 1, size: 100, engineType });
      return res.data.data.records;
    },
    [open, engineType],
    { manual: !open }
  );

  const [category, setCategory] = useState<string | undefined>(undefined);
  const [keyword, setKeyword] = useState('');

  const filtered = useMemo(() => {
    const templates = data ?? [];
    const kw = keyword.trim().toLowerCase();
    return templates.filter((t) => {
      if (category && t.category !== category) return false;
      if (kw && !t.name.toLowerCase().includes(kw) && !t.prompt.toLowerCase().includes(kw)) return false;
      return true;
    });
  }, [data, category, keyword]);

  const handleSelect = (t: PromptTemplate) => {
    templateApi.recordUsage(t.id).catch(() => {});
    onSelect(t);
    onClose();
  };

  return (
    <Modal
      title={<span><FileTextOutlined /> 提示词模板</span>}
      open={open}
      onCancel={onClose}
      footer={null}
      width={720}
      destroyOnHidden
    >
      <Flex vertical gap={12}>
        <Select
          allowClear
          placeholder="按分类筛选"
          style={{ width: 200 }}
          value={category}
          onChange={setCategory}
          options={CATEGORIES.map((c) => ({ value: c, label: c }))}
        />
        <Input.Search
          placeholder="搜索模板名称或内容..."
          allowClear
          enterButton="搜索"
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
        />
        {loading ? (
          <div style={{ textAlign: 'center', padding: 40 }}>加载中...</div>
        ) : filtered.length === 0 ? (
          <Empty description="暂无可用模板" />
        ) : (
          <List
            dataSource={filtered}
            style={{ maxHeight: 480, overflow: 'auto' }}
            renderItem={(t) => (
              <List.Item
                style={{ cursor: 'pointer' }}
                onClick={() => handleSelect(t)}
              >
                <List.Item.Meta
                  title={
                    <Flex align="center" gap={8}>
                      <span>{t.name}</span>
                      <Tag color="blue">{t.category}</Tag>
                      {t.scope === 'PUBLIC' && <Tag color="gold">官方</Tag>}
                    </Flex>
                  }
                  description={
                    <Tooltip title={t.prompt}>
                      <Text type="secondary" style={{ fontSize: 12 }} ellipsis>
                        {t.prompt}
                      </Text>
                    </Tooltip>
                  }
                />
                <Text type="secondary" style={{ fontSize: 12, whiteSpace: 'nowrap' }}>
                  {t.usageCount} 次使用
                </Text>
              </List.Item>
            )}
          />
        )}
      </Flex>
    </Modal>
  );
};

export default PromptTemplateModal;
