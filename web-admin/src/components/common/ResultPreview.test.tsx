import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import ResultPreview, { parseResultUrls, isVideoUrl } from './ResultPreview';

vi.mock('@/utils/feedback', () => ({
  useAppFeedback: () => ({
    message: { loading: vi.fn(), success: vi.fn(), error: vi.fn(), warning: vi.fn() },
  }),
}));

describe('parseResultUrls', () => {
  it('解析 outputFiles[] 结构', () => {
    const raw = JSON.stringify({ outputFiles: [{ url: 'http://x/a.png' }, { url: 'http://x/b.png' }, { name: 'no-url' }] });
    expect(parseResultUrls(raw)).toEqual(['http://x/a.png', 'http://x/b.png']);
  });

  it('解析 url / videoUrl 结构', () => {
    const raw = JSON.stringify({ url: 'http://x/c.png', videoUrl: 'http://x/d.mp4' });
    expect(parseResultUrls(raw)).toEqual(['http://x/c.png', 'http://x/d.mp4']);
  });

  it('混合结构全部收集', () => {
    const raw = JSON.stringify({ outputFiles: [{ url: 'http://x/1.png' }], url: 'http://x/2.png' });
    expect(parseResultUrls(raw)).toEqual(['http://x/1.png', 'http://x/2.png']);
  });

  it('非法 JSON / 空输入返回 []', () => {
    expect(parseResultUrls('not json')).toEqual([]);
    expect(parseResultUrls(null)).toEqual([]);
    expect(parseResultUrls(undefined)).toEqual([]);
    expect(parseResultUrls('')).toEqual([]);
  });

  it('无输出字段返回 []', () => {
    expect(parseResultUrls(JSON.stringify({ prompt: 'hello' }))).toEqual([]);
  });
});

describe('isVideoUrl', () => {
  it('识别 .mp4 / .webm / video 路径', () => {
    expect(isVideoUrl('http://x/a.mp4')).toBe(true);
    expect(isVideoUrl('http://x/a.webm')).toBe(true);
    expect(isVideoUrl('http://x/video/result/1')).toBe(true);
  });

  it('图片与未知路径为 false', () => {
    expect(isVideoUrl('http://x/a.png')).toBe(false);
    expect(isVideoUrl('http://x/a.jpg')).toBe(false);
  });
});

describe('ResultPreview 组件', () => {
  it('无结果时渲染空', () => {
    const { container } = render(<ResultPreview raw={null} />);
    expect(container.innerHTML).toBe('');
  });

  it('图片结果渲染 Image', () => {
    render(<ResultPreview raw={JSON.stringify({ url: 'http://x/img.png' })} />);
    expect(screen.getByAltText('生成结果 1')).toBeInTheDocument();
  });

  it('视频结果渲染 video 控件', () => {
    render(<ResultPreview raw={JSON.stringify({ videoUrl: 'http://x/v.mp4' })} />);
    const video = document.querySelector('video');
    expect(video).not.toBeNull();
    expect(video?.getAttribute('src')).toBe('http://x/v.mp4');
  });

  it('多结果渲染多个条目', () => {
    const raw = JSON.stringify({ outputFiles: [{ url: 'http://x/1.png' }, { url: 'http://x/2.png' }] });
    render(<ResultPreview raw={raw} />);
    expect(screen.getByAltText('生成结果 1')).toBeInTheDocument();
    expect(screen.getByAltText('生成结果 2')).toBeInTheDocument();
  });
});
