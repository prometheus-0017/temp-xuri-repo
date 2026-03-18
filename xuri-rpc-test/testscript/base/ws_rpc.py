import asyncio
import json
import websockets
from xuri_rpc import PlainProxyManager, RunnableProxyManager, MessageReceiver, Client, asProxy, getMessageReceiver, setHostId,dict2obj

from util import assertV
port=18081
#无限可能？
async def getClient():

    setHostId('backend')
    from xuri_rpc import setDebugFlag
    setDebugFlag(True)


    class Sender:
        def __init__(self, ws):
            self.ws = ws

        async def send(self, message):
            # message is an object can be jsonified
            await self.ws.send(json.dumps(message))

    setHostId('frontend')
    client = Client()

    ws = await websockets.connect(f'ws://localhost:{port}')

    async def on_message(data):
        await getMessageReceiver().onReceiveMessage(json.loads(data), client)
        print(f'收到服务器消息: {data}')

    # Run message reception in the background
    async def listen():
        async for message in ws:
            asyncio.ensure_future(on_message(message))

    # Start listening in the background
    asyncio.ensure_future(listen())

    client.setSender(Sender(ws))

    return client
def onServe( mainObject ):

    # 设置hostName
    setHostId('backend')

    # 创建一个Sender
    class Sender:
        def __init__(self, ws):
            self.ws = ws

        async def send(self, message):
            await self.ws.send(json.dumps(message))

    # 设置用于提供起始方法的main对象
    getMessageReceiver().setMain(dict2obj(mainObject))

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
        server = await websockets.serve(handle_connection, "localhost", port)
        await server.wait_closed()
    return main()