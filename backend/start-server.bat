@echo off
cd /d D:\develop\auto-test-platform\backend\platform-server
call mvn spring-boot:run > D:\develop\auto-test-platform\backend\log\startup.log 2>&1
