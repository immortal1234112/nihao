import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Card, Form, Input, Select, Slider, Button, Row, Col, Typography, message, Space } from 'antd';
import { SendOutlined, ClearOutlined } from '@ant-design/icons';
import { taskApi } from '@/api/task';

const { Title } = Typography;
const { TextArea } = Input;

const videoModels = [
  { value: 'cogvideox-flash', label: 'CogVideoX-Flash (智谱·免费)', desc: '免费，最大10秒，1920×1080，文生视频/图生视频' },
  { value: 'runway', label: 'Runway Gen-3 Alpha', desc: '最大10秒，1280×768，电影级品质' },
  { value: 'kling', label: '快手可灵 (Kling V1)', desc: '最大10秒，1920×1080，中文提示词友好' },
  { value: 'pika', label: 'Pika 2.0', desc: '最大8秒，1920×1080，支持图+文转视频' },
  { value: 'luma', label: 'Luma Dream Machine V1', desc: '最大5秒，1360×752，电影感风格' },
  { value: 'deepseek', label: 'DeepSeek 提示词增强', desc: 'AI 自动优化提示词质量，提升出片效果' },
];

const VideoGenPage: React.FC = () => {
  const [loading, setLoading] = useState(false);
  const [form] = Form.useForm();
  const navigate = useNavigate();

  const onFinish = async (values: Record<string, unknown>) => {
    setLoading(true);
    try {
      const res = await taskApi.submitVideo({
        prompt: values.prompt as string,
        workflowType: 'SINGLE',
        engineType: 'VIDEO',
        modelProvider: values.modelProvider as string,
        duration: values.duration as number,
        fps: values.fps as number,
        resolution: values.resolution as string,
        style: values.style as string,
        negativePrompt: values.negativePrompt as string,
      });
      message.success('视频生成任务提交成功');
      const taskId = res.data.data?.task?.id;
      if (taskId) navigate(`/tasks/${taskId}`);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div>
      <Title level={4} style={{ marginBottom: 24 }}>AI 视频生成</Title>
      <Row gutter={24}>
        <Col span={16}>
          <Card>
            <Form form={form} layout="vertical" onFinish={onFinish}
              initialValues={{ duration: 5, fps: 24, resolution: '1920x1080' }}>
              <Form.Item name="prompt" label="提示词" rules={[{ required: true, message: '请输入提示词' }]}>
                <TextArea rows={4} placeholder="描述你想要生成的视频内容..." maxLength={2000} showCount />
              </Form.Item>
              <Form.Item name="negativePrompt" label="负面提示词">
                <TextArea rows={2} placeholder="描述你不希望出现的元素（可选）" maxLength={500} />
              </Form.Item>
              <Form.Item name="modelProvider" label="模型选择" initialValue="runway">
                <Select
                  placeholder="选择视频生成模型"
                  options={videoModels.map(m => ({ value: m.value, label: m.label }))}
                />
              </Form.Item>
              <Row gutter={16}>
                <Col span={8}>
                  <Form.Item name="duration" label="时长（秒）">
                    <Slider min={1} max={30} marks={{ 1: '1s', 5: '5s', 15: '15s', 30: '30s' }} />
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Form.Item name="fps" label="帧率">
                    <Select options={[{ value: 24, label: '24 fps' }, { value: 30, label: '30 fps' }]} />
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Form.Item name="resolution" label="分辨率">
                    <Select options={[
                      { value: '1920x1080', label: '1080p' },
                      { value: '1280x720', label: '720p' },
                    ]} />
                  </Form.Item>
                </Col>
              </Row>
              <Form.Item name="style" label="风格">
                <Select allowClear placeholder="选择风格" options={[
                  { value: 'realistic', label: '写实' },
                  { value: 'anime', label: '动漫' },
                  { value: 'cinematic', label: '电影感' },
                ]} />
              </Form.Item>
              <Form.Item>
                <Space>
                  <Button type="primary" htmlType="submit" icon={<SendOutlined />} loading={loading}>
                    提交生成
                  </Button>
                  <Button icon={<ClearOutlined />} onClick={() => form.resetFields()}>重置</Button>
                </Space>
              </Form.Item>
            </Form>
          </Card>
        </Col>
        <Col span={8}>
          <Card title="可用模型">
            <ul>
              {videoModels.map(m => (
                <li key={m.value}><strong>{m.label.split('(')[0].trim()}</strong>: {m.desc}</li>
              ))}
            </ul>
            <Title level={5} style={{ marginTop: 16 }}>使用说明</Title>
            <ul>
              <li>视频生成耗时较长，请耐心等待</li>
              <li>生成一个视频消耗 1 次视频配额</li>
              <li>建议从较短时长开始尝试</li>
              <li>1080p 分辨率视频需要更多处理时间</li>
            </ul>
          </Card>
        </Col>
      </Row>
    </div>
  );
};

export default VideoGenPage;
