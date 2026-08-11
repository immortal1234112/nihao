import { useEffect, useState } from 'react';
import { Select } from 'antd';
import { ragApi } from '@/api/rag';
import { RagDataset } from '@/types/api';

interface Props {
  value?: string;
  onChange?: (v?: string) => void;
  style?: React.CSSProperties;
  placeholder?: string;
}

/** 知识库下拉（可选），用于 RAG 增强的通用选择控件 */
const DatasetSelect: React.FC<Props> = ({ value, onChange, style, placeholder }) => {
  const [datasets, setDatasets] = useState<RagDataset[]>([]);

  useEffect(() => {
    ragApi.listDatasets({ page: 1, size: 100 })
      .then((res) => setDatasets(res.data.data.records.filter((d) => d.status === 'ACTIVE')))
      .catch(() => {});
  }, []);

  return (
    <Select
      allowClear
      placeholder={placeholder || '选择知识库（可选）'}
      style={style}
      value={value}
      onChange={onChange}
      options={datasets.map((d) => ({ value: d.id, label: `${d.name}（${d.docCount} 文档）` }))}
      notFoundContent={datasets.length === 0 ? '暂无知识库，请先在「知识库管理」创建' : '无匹配知识库'}
    />
  );
};

export default DatasetSelect;
