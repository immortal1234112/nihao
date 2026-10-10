import React, { useState, useRef, useEffect } from 'react';
import './DesktopPet.css';

const DesktopPet: React.FC = () => {
  const [position, setPosition] = useState({ x: window.innerWidth - 150, y: window.innerHeight - 150 });
  const [isDragging, setIsDragging] = useState(false);
  const [dragOffset, setDragOffset] = useState({ x: 0, y: 0 });
  const [currentFrame, setCurrentFrame] = useState(0);
  const petRef = useRef<HTMLDivElement>(null);
  const animationRef = useRef<number>();

  const TOTAL_FRAMES = 24; // 使用新的24帧
  const FPS = 8; // 每秒8帧
  const FRAME_DURATION = 1000 / FPS;

  // 预加载所有帧
  const [frames, setFrames] = useState<HTMLImageElement[]>([]);
  const [isLoaded, setIsLoaded] = useState(false);

  useEffect(() => {
    const loadFrames = async () => {
      const loadedFrames: HTMLImageElement[] = [];

      for (let i = 0; i < TOTAL_FRAMES; i++) {
        const img = new Image();
        const promise = new Promise<void>((resolve, reject) => {
          img.onload = () => resolve();
          img.onerror = reject;
        });
        img.src = `/pet_final/frame_${String(i).padStart(3, '0')}.png`;
        loadedFrames.push(img);
        await promise;
      }

      setFrames(loadedFrames);
      setIsLoaded(true);
      console.log('桌面宠物帧加载完成');
    };

    loadFrames();
  }, []);

  // 动画循环
  useEffect(() => {
    if (!isLoaded || isDragging) return;

    let lastTime = Date.now();

    const animate = () => {
      const now = Date.now();
      const elapsed = now - lastTime;

      if (elapsed >= FRAME_DURATION) {
        setCurrentFrame((prev) => (prev + 1) % TOTAL_FRAMES);
        lastTime = now;
      }

      animationRef.current = requestAnimationFrame(animate);
    };

    animationRef.current = requestAnimationFrame(animate);

    return () => {
      if (animationRef.current) {
        cancelAnimationFrame(animationRef.current);
      }
    };
  }, [isLoaded, isDragging, TOTAL_FRAMES, FRAME_DURATION]);

  const handleMouseDown = (e: React.MouseEvent) => {
    if (petRef.current) {
      const rect = petRef.current.getBoundingClientRect();
      setDragOffset({
        x: e.clientX - rect.left,
        y: e.clientY - rect.top,
      });
      setIsDragging(true);
    }
  };

  useEffect(() => {
    const handleMouseMove = (e: MouseEvent) => {
      if (isDragging) {
        setPosition({
          x: e.clientX - dragOffset.x,
          y: e.clientY - dragOffset.y,
        });
      }
    };

    const handleMouseUp = () => {
      setIsDragging(false);
    };

    if (isDragging) {
      document.addEventListener('mousemove', handleMouseMove);
      document.addEventListener('mouseup', handleMouseUp);
    }

    return () => {
      document.removeEventListener('mousemove', handleMouseMove);
      document.removeEventListener('mouseup', handleMouseUp);
    };
  }, [isDragging, dragOffset]);

  if (!isLoaded || frames.length === 0) {
    return null;
  }

  return (
    <div
      ref={petRef}
      className={`desktop-pet ${isDragging ? 'dragging' : ''}`}
      style={{
        left: `${position.x}px`,
        top: `${position.y}px`,
      }}
      onMouseDown={handleMouseDown}
    >
      <img
        src={frames[currentFrame]?.src}
        alt="桌面宠物"
        className="pet-image"
        draggable={false}
      />
    </div>
  );
};

export default DesktopPet;
