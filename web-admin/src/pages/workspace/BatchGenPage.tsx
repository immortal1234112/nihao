import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Card, Form, Input, Select, InputNumber, Button, Row, Col, Typography, Space } from 'antd';
import { SendOutlined, ClearOutlined } from '@ant-design/icons';
import { taskApi } from '@/api/task';
import { useAppFeedback } from '@/utils/feedback';

const { Title } = Typography;
const { TextArea } = Input;

const BatchGenPage: React.FC = () => {
  const [loading, setLoading] = useState(false);
  const [form] = Form.useForm();
  const navigate = useNavigate();
  const { message } = useAppFeedback();

  const onFinish = async (values: Record<string, unknown>) => {
    setLoading(true);
    try {
      const res = await taskApi.submitBatch({
        prompt: values.prompt as string,
        workflowType: 'BATCH',
        engineType: values.engineType as 'IMAGE' | 'VIDEO',
        batchCount: values.batchCount as number,
        size: values.size as string,
        style: values.style as string,
        quality: values.quality as string,
        format: values.format as string,
        resolution: values.resolution as string,
        duration: values.duration as number,
      });
      message.success('批量任务提交成功');
      const taskId = res.data.data?.task?.id;
      if (taskId) navigate(`/tasks/${taskId}`);
    } finally {
      setLoading(false);
    }
  };

  const engineType = Form.useWatch('engineType', form);

  return (
    <div>
      <Title level={4} style={{ marginBottom: 24 }}>批量生成</Title>
      <Row gutter={24}>
        <Col span={16}>
          <Card>
            <Form form={form} layout="vertical" onFinish={onFinish}
              initialValues={{ engineType: 'IMAGE', batchCount: 4, size: '1024x1024', quality: 'standard' }}>
              <Form.Item name="prompt" label="提示词" rules={[{ required: true, message: '请输入提示词' }]}>
                <TextArea rows={4} placeholder="描述你想要生成的内容..." maxLength={2000} showCount />
              </Form.Item>
              <Row gutter={16}>
                <Col span={8}>
                  <Form.Item name="engineType" label="引擎类型">
                    <Select options={[{ value: 'IMAGE', label: '图片' }, { value: 'VIDEO', label: '视频' }]} />
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Form.Item name="batchCount" label="批量数量">
                    <InputNumber min={2} max={100} style={{ width: '100%' }} />
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Form.Item name="style" label="风格">
                    <Select allowClear placeholder="选择风格" options={[
                      { value: 'realistic', label: '写实' },
                      { value: 'anime', label: '动漫' },
                    ]} />
                  </Form.Item>
                </Col>
              </Row>
              {engineType === 'IMAGE' ? (
                <Row gutter={16}>
                  <Col span={8}>
                    <Form.Item name="size" label="尺寸">
                      <Select options={[
                        { value: '512x512', label: '512×512' },
                        { value: '1024x1024', label: '1024×1024' },
                      ]} />
                    </Form.Item>
                  </Col>
                  <Col span={8}>
                    <Form.Item name="quality" label="画质">
                      <Select options={[{ value: 'standard', label: '标准' }, { value: 'hd', label: '高清' }]} />
                    </Form.Item>
                  </Col>
                  <Col span={8}>
                    <Form.Item name="format" label="格式">
                      <Select options={[{ value: 'png', label: 'PNG' }, { value: 'jpeg', label: 'JPEG' }]} />
                    </Form.Item>
                  </Col>
                </Row>
              ) : (
                <Row gutter={16}>
                  <Col span={12}>
                    <Form.Item name="duration" label="时长（秒）">
                      <InputNumber min={1} max={30} defaultValue={5} style={{ width: '100%' }} />
                    </Form.Item>
                  </Col>
                  <Col span={12}>
                    <Form.Item name="resolution" label="分辨率">
                      <Select options={[{ value: '1920x1080', label: '1080p' }, { value: '1280x720', label: '720p' }]} />
                    </Form.Item>
                  </Col>
                </Row>
              )}
              <Form.Item>
                <Space>
                  <Button type="primary" htmlType="submit" icon={<SendOutlined />} loading={loading}>
                    提交批量生成
                  </Button>
                  <Button icon={<ClearOutlined />} onClick={() => form.resetFields()}>重置</Button>
                </Space>
              </Form.Item>
            </Form>
          </Card>
        </Col>
        <Col span={8}>
          <Card title="批量生成说明">
            <ul>
              <li>批量生成可一次提交多个相同参数的任务</li>
              <li>图片批量最多 100 张，视频批量最多 10 个</li>
              <li>每个子任务独立消耗配额</li>
              <li>任务将以并行方式处理</li>
            </ul>
          </Card>
        </Col>
      </Row>
    </div>
  );
};

export default BatchGenPage;
