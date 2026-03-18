from xuri_rpc import PlainProxyManager, RunnableProxyManager, MessageReceiver, Client, asProxy, getMessageReceiver, setHostId
from xuri_rpc import dict2obj
import websockets
import asyncio
import json

# 设置hostName
setHostId('backend')

# 创建一个Sender
class Sender:
    def __init__(self, ws):
        self.ws = ws

    async def send(self, message):
        await self.ws.send(json.dumps(message))

# 设置用于提供起始方法的main对象
getMessageReceiver().setMain(dict2obj({
}))
from xuri_rpc import dict2obj
getMessageReceiver().setObject("greeting",dict2obj( {
    "greeting": lambda context: f"hi,{context['a']} and {context['b']}"
}), True)
async def a(context,message,client,next):
    context['a']='mike'
    await next()
async def b(context,message,client,next):
    context['b']='john'
    await next()

getMessageReceiver().addInterceptor(a)
getMessageReceiver().addInterceptor(b)

async def handle_connection(websocket, path):
    # 创建一个client用于发送返回信息
    client = Client()
    client.setSender(Sender(websocket))

    async for data in websocket:
        try:
            # 处理接收到的信息
            asyncio.ensure_future(getMessageReceiver().onReceiveMessage(json.loads(data), client))
        except Exception as e:
            print('客户端连接错误:', e)

async def main():
    server = await websockets.serve(handle_connection, "localhost", 18081)
    await server.wait_closed()

if __name__ == "__main__":
    asyncio.run(main())