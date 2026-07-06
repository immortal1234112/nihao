import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Card, Form, Input, Select, Slider, Button, Row, Col, Typography, message, Space } from 'antd';
import { SendOutlined, ClearOutlined } from '@ant-design/icons';
import { taskApi } from '@/api/task';

const { Title } = Typography;
const { TextArea } = Input;

const ImageGenPage: React.FC = () => {
  const [loading, setLoading] = useState(false);
  const [form] = Form.useForm();
  const navigate = useNavigate();

  const onFinish = async (values: Record<string, unknown>) => {
    setLoading(true);
    try {
      const res = await taskApi.submitImage({
        prompt: values.prompt as string,
        workflowType: 'SINGLE',
        engineType: 'IMAGE',
        size: values.size as string,
        count: values.count as number,
        style: values.style as string,
        format: values.format as string,
        quality: values.quality as string,
        negativePrompt: values.negativePrompt as string,
        aspectRatio: values.aspectRatio as string,
      });
      message.success('任务提交成功');
      const taskId = res.data.data?.task?.id;
      if (taskId) navigate(`/tasks/${taskId}`);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div>
      <Title level={4} style={{ marginBottom: 24 }}>AI 图片生成</Title>
      <Row gutter={24}>
        <Col span={16}>
          <Card>
            <Form form={form} layout="vertical" onFinish={onFinish}
              initialValues={{ count: 1, size: '1024x1024', format: 'png', quality: 'standard', aspectRatio: '1:1' }}>
              <Form.Item name="prompt" label="提示词" rules={[{ required: true, message: '请输入提示词' }]}>
                <TextArea rows={4} placeholder="描述你想要生成的图片内容..." maxLength={2000} showCount />
              </Form.Item>
              <Form.Item name="negativePrompt" label="负面提示词">
                <TextArea rows={2} placeholder="描述你不希望出现的元素（可选）" maxLength={500} />
              </Form.Item>
              <Row gutter={16}>
                <Col span={8}>
                  <Form.Item name="size" label="尺寸">
                    <Select options={[
                      { value: '512x512', label: '512×512' },
                      { value: '768x768', label: '768×768' },
                      { value: '1024x1024', label: '1024×1024' },
                      { value: '2048x2048', label: '2048×2048' },
                    ]} />
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Form.Item name="style" label="风格">
                    <Select allowClear placeholder="选择风格" options={[
                      { value: 'realistic', label: '写实' },
                      { value: 'anime', label: '动漫' },
                      { value: 'oil_painting', label: '油画' },
                      { value: 'watercolor', label: '水彩' },
                    ]} />
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Form.Item name="aspectRatio" label="宽高比">
                    <Select options={[
                      { value: '1:1', label: '1:1 正方形' },
                      { value: '16:9', label: '16:9 横屏' },
                      { value: '9:16', label: '9:16 竖屏' },
                      { value: '4:3', label: '4:3' },
                    ]} />
                  </Form.Item>
                </Col>
              </Row>
              <Row gutter={16}>
                <Col span={8}>
                  <Form.Item name="count" label="生成数量">
                    <Slider min={1} max={10} marks={{ 1: '1', 5: '5', 10: '10' }} />
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Form.Item name="quality" label="画质">
                    <Select options={[
                      { value: 'standard', label: '标准' },
                      { value: 'hd', label: '高清' },
                    ]} />
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Form.Item name="format" label="输出格式">
                    <Select options={[
                      { value: 'png', label: 'PNG' },
                      { value: 'jpeg', label: 'JPEG' },
                      { value: 'webp', label: 'WebP' },
                    ]} />
                  </Form.Item>
                </Col>
              </Row>
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
          <Card title="使用说明">
            <ul>
              <li>提示词建议使用英文，描述越详细效果越好</li>
              <li>生成一个图片消耗 1 次图片配额</li>
              <li>高清画质生成时间更长但质量更好</li>
              <li>负面提示词可用于排除不想要的元素</li>
            </ul>
          </Card>
        </Col>
      </Row>
    </div>
  );
};

export default ImageGenPage;
