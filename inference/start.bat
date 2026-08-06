@echo off
chcp 65001 >nul
cd /d "%~dp0"
title AI Inference Server

echo ================================================================
echo     AI Inference Server - 图片/视频生成
echo ================================================================
echo.

:: Check Python
python --version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Python not found. Please install Python 3.10+.
    pause
    exit /b 1
)

:: Create virtual environment if not exists
if not exist "venv\" (
    echo [1/3] Creating Python virtual environment...
    python -m venv venv
    if errorlevel 1 (
        echo [ERROR] Failed to create venv.
        pause
        exit /b 1
    )
) else (
    echo [1/3] Virtual environment found ✓
)

:: Activate and install dependencies
echo [2/3] Installing dependencies...
call venv\Scripts\activate.bat
pip install -r requirements.txt --quiet --index-url https://pypi.tuna.tsinghua.edu.cn/simple 2>&1
if errorlevel 1 (
    :: Fallback: try default PyPI
    pip install -r requirements.txt --quiet 2>&1
)
echo    Dependencies ✓

:: Start server
echo [3/3] Starting server on http://localhost:7860...
echo.
echo   API docs: http://localhost:7860/docs
echo   Health:   http://localhost:7860/api/v1/health
echo.
echo   First request will download models from HuggingFace.
echo   This may take a few minutes for each model.
echo.
echo ================================================================
echo.

call venv\Scripts\activate.bat
python server.py

pause
