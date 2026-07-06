import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Card, Form, Input, Select, Button, Row, Col, Typography, message, Space, Steps } from 'antd';
import { SendOutlined, ClearOutlined, PlusOutlined, DeleteOutlined } from '@ant-design/icons';
import { taskApi } from '@/api/task';

const { Title } = Typography;
const { TextArea } = Input;

const PipelinePage: React.FC = () => {
  const [loading, setLoading] = useState(false);
  const [form] = Form.useForm();
  const navigate = useNavigate();
  const [steps, setSteps] = useState<Array<{ stepType: string; prompt?: string }>>([
    { stepType: 'IMAGE' },
    { stepType: 'VIDEO' },
  ]);

  const addStep = () => setSteps([...steps, { stepType: 'IMAGE' }]);
  const removeStep = (index: number) => setSteps(steps.filter((_, i) => i !== index));

  const onFinish = async (values: { prompt: string }) => {
    if (steps.length < 2) {
      message.warning('流水线至少需要 2 个步骤');
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
    <div>
      <Title level={4} style={{ marginBottom: 24 }}>流水线编排</Title>
      <Row gutter={24}>
        <Col span={16}>
          <Card>
            <Form form={form} layout="vertical" onFinish={onFinish}>
              <Form.Item name="prompt" label="初始提示词" rules={[{ required: true, message: '请输入提示词' }]}>
                <TextArea rows={3} placeholder="描述整体创作意图..." maxLength={2000} showCount />
              </Form.Item>

              <div style={{ marginBottom: 24 }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
                  <span style={{ fontWeight: 500 }}>流水线步骤 ({steps.length})</span>
                  <Button type="dashed" size="small" icon={<PlusOutlined />} onClick={addStep}>添加步骤</Button>
                </div>
                <Steps
                  direction="vertical"
                  current={-1}
                  items={steps.map((step, i) => ({
                    title: (
                      <Space>
                        <Select
                          value={step.stepType}
                          onChange={(v) => {
                            const newSteps = [...steps];
                            newSteps[i].stepType = v;
                            setSteps(newSteps);
                          }}
                          style={{ width: 120 }}
                          options={[
                            { value: 'IMAGE', label: '图片生成' },
                            { value: 'VIDEO', label: '视频生成' },
                            { value: 'AUDIT', label: '安全审核' },
                            { value: 'POST_PROCESS', label: '后处理' },
                          ]}
                        />
                        {steps.length > 2 && (
                          <Button type="text" danger size="small" icon={<DeleteOutlined />} onClick={() => removeStep(i)} />
                        )}
                      </Space>
                    ),
                    description: `步骤 ${i + 1}`,
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
        <Col span={8}>
          <Card title="流水线说明">
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
