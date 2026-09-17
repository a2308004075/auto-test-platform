@echo off
chcp 65001 >nul
echo ============================================
echo   一键启动所有中间件
echo ============================================
echo.

echo [1/4] 启动 MySQL ...
tasklist /FI "IMAGENAME eq mysqld.exe" 2>nul | find /I "mysqld.exe" >nul
if "%errorlevel%"=="0" (
    echo       MySQL 已经在运行，跳过启动。
) else (
    start "MySQL" cmd /k "D:\software\mysql-8.0\bin\mysqld --console"
)

echo 等待 MySQL 就绪 ...
ping -n 6 127.0.0.1 >nul

echo [2/4] 启动 Redis ...
start "Redis" cmd /k "D:\software\redis\redis-server.exe"

echo 等待 Redis 就绪 ...
ping -n 4 127.0.0.1 >nul

echo [3/4] 启动 RabbitMQ ...
start "RabbitMQ" cmd /k "call D:\develop\auto-test-platform\docs\script\start-rabbitmq.bat"

echo 等待 RabbitMQ 就绪 ...
ping -n 6 127.0.0.1 >nul

echo [4/4] 启动 Qdrant ...
tasklist /FI "IMAGENAME eq qdrant.exe" 2>nul | find /I "qdrant.exe" >nul
if "%errorlevel%"=="0" (
    echo       Qdrant 已经在运行，跳过启动。
) else (
    start "Qdrant" cmd /k "call D:\develop\auto-test-platform\docs\script\start-qdrant.bat"
)

echo.
echo ============================================
echo   所有中间件启动命令已发送！
echo   MySQL        : localhost:3306
echo   Redis        : localhost:6379
echo   RabbitMQ UI  : http://localhost:15672
echo   Qdrant       : localhost:6333
echo ============================================
echo.
pause
