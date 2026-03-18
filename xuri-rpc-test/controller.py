import subprocess
import sys
import os
from typing import Optional,cast
from pathlib import Path
import traceback
class TestRunner:
    def __init__(self, feature_point: str, server_lang: str, client_lang: str, 
                 server_external: bool, client_external: bool):
        self.feature_point = feature_point
        self.server_lang = server_lang
        self.client_lang = client_lang
        self.server_external = server_external
        self.client_external = client_external
        self.server_process:subprocess.Popen = cast(subprocess.Popen,None)
        
    def get_script_name(self, role: str, lang: str) -> str:
        """获取脚本文件名"""
        ext_map = {
            'python': '.py',
            'js': '.js'
        }
        if lang not in ext_map:
            raise ValueError(f"不支持的语言: {lang}")
        
        return str(Path(f"./testscript/{self.feature_point}-{role}{ext_map[lang]}").absolute())
    
    def start_server(self):
        """启动服务器进程"""
        server_script = self.get_script_name("server", self.server_lang)
        if not os.path.exists(server_script):
            raise FileNotFoundError(f"服务器脚本不存在: {server_script}")
        
        print(f"启动服务器: {server_script}")
        self.server_process = subprocess.Popen(
            [*self.get_cmd(self.server_lang,server_script) , server_script],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            cwd="./testscript",
            bufsize=1,
            universal_newlines=True,
            encoding='utf-8',  # Explicitly set encoding to UTF-8
            errors='replace'   # Replace invalid characters instead of raising exceptions
        )
        
        # 等待服务器输出 "ready"
        while True:
            assert self.server_process.stdout is not None
            output=self.server_process.stdout.readline()
            if "ready" in output.lower():
                print("服务器就绪")
                break
            elif self.server_process.poll() is not None:
                # 进程已退出
                break
    
    def get_cmd(self,lang,filename):
        if(lang=='python'):
            return 'python'.split()
        elif(lang=='js'):
            return 'pnpm exec node'.split()
        else:
            raise ValueError(f"不支持的语言: {lang}");
    def run_client(self):
        """运行客户端"""
        client_script = self.get_script_name("client", self.client_lang)
        if not os.path.exists(client_script):
            raise FileNotFoundError(f"客户端脚本不存在: {client_script}")
        
        print(f"运行客户端: {client_script}")
        result = subprocess.run(
            [*self.get_cmd(self.client_lang,client_script) , client_script],
            capture_output=True,
            cwd="./testscript",
            text=True,
            encoding='utf-8',  # Explicitly set encoding to UTF-8
            errors='replace'   # Replace invalid characters instead of raising exceptions
        )
        
        if result.returncode != 0:
            print(f"客户端执行失败，错误输出:\n{result.stderr}")
        else:
            print("客户端执行成功")
        
        return result.returncode
    
    def stop_server(self):
        """停止服务器进程"""
        if self.server_process and self.server_process.poll() is None:
            print("正在停止服务器...")
            self.server_process.terminate()
            try:
                self.server_process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.server_process.kill()
                print("服务器强制终止")
            
            # 检查服务器退出状态
            exit_code = self.server_process.returncode
            if exit_code is not None:
                # 区分不同的退出情况
                if exit_code == 0 or exit_code == 1:
                    print("服务器正常关闭")
                elif exit_code > 0:
                    # 正常的错误退出码（应用程序自身错误）
                    assert self.server_process.stderr is not None
                    error_output = self.server_process.stderr.read()
                    print(f"服务器异常退出 (退出码: {exit_code})，错误输出:\n{error_output}")
                elif exit_code < 0:
                    # 负数退出码通常表示被信号终止
                    signal_num = abs(exit_code)
                    if signal_num == 15:  # SIGTERM
                        print("服务器被正常终止 (SIGTERM)")
                    elif signal_num == 9:  # SIGKILL
                        print("服务器被强制杀死 (SIGKILL)")
                    else:
                        print(f"服务器被信号 {signal_num} 终止")
                else:
                    # 其他情况
                    assert self.server_process.stderr is not None
                    error_output = self.server_process.stderr.read()
                    print(f"服务器未知退出状态 (退出码: {exit_code})，错误输出:\n{error_output}")
    
    def run_test(self):
        """执行测试流程"""
        try:
            if self.server_external:
                # 服务器外部执行模式
                input("请手动启动服务器，完成后按回车键继续...")
            else:
                # 启动服务器进程
                self.start_server()
            
            if self.client_external:
                # 客户端外部执行模式
                input("请手动运行客户端，完成后按回车键继续...")
            else:
                # 运行客户端
                client_result = self.run_client()
                if client_result != 0:
                    print("客户端执行失败，跳过服务器清理")
                    return
            
            # 测试完成，停止服务器
            self.stop_server()
            
        except FileNotFoundError as e:
            traceback.print_exc()
            print(f"错误: {e}")
        except Exception as e:
            traceback.print_exc()
            print(f"测试过程中发生错误: {e}")


def main():
    if len(sys.argv) < 4:
        print("用法: python test_runner.py <功能点名称> <server语言> <client语言> [server外部执行] [client外部执行]")
        print("示例: python test_runner.py pointA python js false true")
        print("支持的语言: python, js")
        print("外部执行参数: true/false (默认false)")
        return
    
    feature_point = sys.argv[1]
    server_lang = sys.argv[2].lower()
    client_lang = sys.argv[3].lower()
    
    server_external = len(sys.argv) > 4 and sys.argv[4].lower() == 'true'
    client_external = len(sys.argv) > 5 and sys.argv[5].lower() == 'true'
    
    if server_lang not in ['python', 'js'] or client_lang not in ['python', 'js']:
        print("错误: 目前仅支持 python 和 js 语言")
        return
    
    runner = TestRunner(feature_point, server_lang, client_lang, server_external, client_external)
    runner.run_test()


if __name__ == "__main__":
    runner=TestRunner('pt1','js','js',False,False)
    runner.run_test()
    # main()