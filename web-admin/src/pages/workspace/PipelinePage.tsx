import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Card, Form, Input, Select, Button, Row, Col, Typography, Space, Steps, InputNumber } from 'antd';
import { SendOutlined, ClearOutlined, PlusOutlined, DeleteOutlined } from '@ant-design/icons';
import { taskApi } from '@/api/task';
import { useAppFeedback } from '@/utils/feedback';
import DatasetSelect from '@/components/rag/DatasetSelect';
import styles from './PipelinePage.module.css';

const { Title } = Typography;
const { TextArea } = Input;

type PipelineStep = {
  stepType: string;
  prompt?: string;
  datasetId?: string;
  topK?: number;
  [key: string]: unknown;
};

const PipelinePage: React.FC = () => {
  const [loading, setLoading] = useState(false);
  const [form] = Form.useForm();
  const navigate = useNavigate();
  const { message } = useAppFeedback();
  const [steps, setSteps] = useState<PipelineStep[]>([
    { stepType: 'IMAGE' },
    { stepType: 'VIDEO' },
  ]);

  const addStep = () => setSteps([...steps, { stepType: 'IMAGE' }]);
  const removeStep = (index: number) => setSteps(steps.filter((_, i) => i !== index));
  const updateStep = (index: number, patch: Partial<PipelineStep>) => {
    setSteps(steps.map((s, i) => (i === index ? { ...s, ...patch } : s)));
  };

  const onFinish = async (values: { prompt: string }) => {
    if (steps.length < 2) {
      message.warning('流水线至少需要 2 个步骤');
      return;
    }
    if (steps.some((s) => s.stepType === 'RAG_RETRIEVE' && !s.datasetId)) {
      message.warning('知识库检索步骤需要选择知识库');
      return;
    }
    setLoading(true);
    try {
      const res = await taskApi.submitWorkflow({
        prompt: values.prompt,
        workflowType: 'PIPELINE',
        engineType: 'IMAGE',
        pipelineSteps: steps,
      });
      message.success('流水线任务提交成功');
      const taskId = res.data.data?.task?.id;
      if (taskId) navigate(`/tasks/${taskId}`);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className={styles.page}>
      <div className={styles.pageHeader}>
        <div>
          <Title level={3} className={styles.pageTitle}>流水线编排</Title>
          <Typography.Text type="secondary" className={styles.pageHint}>组合多个生成与处理步骤，提交一条可追踪的任务流水线</Typography.Text>
        </div>
      </div>
      <Row gutter={[16, 16]}>
        <Col xs={24} xl={16}>
          <Card className={styles.panel}>
            <Form form={form} layout="vertical" onFinish={onFinish}>
              <Form.Item name="prompt" label="初始提示词" rules={[{ required: true, message: '请输入提示词' }]}>
                <TextArea rows={3} placeholder="描述整体创作意图..." maxLength={2000} showCount />
              </Form.Item>

              <div className={styles.stepsSection}>
                <div className={styles.stepsHeader}>
                  <span className={styles.stepsTitle}>流水线步骤 ({steps.length})</span>
                  <Button type="dashed" size="small" icon={<PlusOutlined />} onClick={addStep}>添加步骤</Button>
                </div>
                <Steps
                  direction="vertical"
                  current={-1}
                  items={steps.map((step, i) => ({
                    title: (
                      <div>
                        <Space>
                          <Select
                            value={step.stepType}
                            onChange={(v) => updateStep(i, { stepType: v })}
                            style={{ width: 140 }}
                            options={[
                              { value: 'IMAGE', label: '图片生成' },
                              { value: 'VIDEO', label: '视频生成' },
                              { value: 'RAG_RETRIEVE', label: '知识库检索' },
                              { value: 'AUDIT', label: '安全审核' },
                              { value: 'POST_PROCESS', label: '后处理' },
                            ]}
                          />
                          {steps.length > 2 && (
                            <Button type="text" danger size="small" icon={<DeleteOutlined />} onClick={() => removeStep(i)} />
                          )}
                        </Space>
                        {step.stepType === 'RAG_RETRIEVE' && (
                          <Space style={{ marginTop: 8 }} wrap>
                            <DatasetSelect
                              value={step.datasetId}
                              onChange={(v) => updateStep(i, { datasetId: v })}
                              style={{ width: 220 }}
                              placeholder="选择知识库（必选）"
                            />
                            <InputNumber
                              min={1}
                              max={10}
                              defaultValue={3}
                              value={step.topK ?? 3}
                              onChange={(v) => updateStep(i, { topK: v ?? undefined })}
                              addonBefore="topK"
                              style={{ width: 110 }}
                            />
                          </Space>
                        )}
                      </div>
                    ),
                    description: step.stepType === 'RAG_RETRIEVE'
                      ? `步骤 ${i + 1}：检索结果将注入后续步骤`
                      : `步骤 ${i + 1}`,
                  }))}
              />
              </div>

              <Form.Item>
                <Space>
                  <Button type="primary" htmlType="submit" icon={<SendOutlined />} loading={loading}>
                    提交流水线
                  </Button>
                  <Button icon={<ClearOutlined />} onClick={() => { form.resetFields(); setSteps([{ stepType: 'IMAGE' }, { stepType: 'VIDEO' }]); }}>
                    重置
                  </Button>
                </Space>
              </Form.Item>
            </Form>
          </Card>
        </Col>
        <Col xs={24} xl={8}>
          <Card className={styles.panel} title="流水线说明">
            <ul>
              <li>流水线支持多个步骤串联执行</li>
              <li>每个步骤的输出作为下一个步骤的输入</li>
              <li>支持图片→视频、图片→审核等组合</li>
              <li>中间环节失败会自动重试</li>
            </ul>
          </Card>
        </Col>
      </Row>
    </div>
  );
};

export default PipelinePage;
