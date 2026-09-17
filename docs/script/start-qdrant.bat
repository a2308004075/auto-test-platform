@echo off
chcp 65001 >nul
title Qdrant
echo ============================================
echo   启动 Qdrant
echo ============================================
echo.
echo   说明: 默认使用本地安装的 Qdrant，
echo         监听 6333 (REST API) 端口。
echo.

set QDRANT_HOME=D:\software\qdrant

if not exist "%QDRANT_HOME%\qdrant.exe" (
    echo       未找到 %QDRANT_HOME%\qdrant.exe
    pause
    exit /b 1
)

"%QDRANT_HOME%\qdrant.exe"

pause
