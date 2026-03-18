@echo off
set name=%1
mkdir %name%
cd %name%
mkdir src
pnpm init
pnpm add tsc
pnpm link C:\Users\lzy\Desktop\xuri-rpc
pnpm install
cd ..
@pause