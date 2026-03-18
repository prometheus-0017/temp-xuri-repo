import asyncio
import json
import websockets
from xuri_rpc import PlainProxyManager, RunnableProxyManager, MessageReceiver, Client, asProxy, getMessageReceiver, setHostId

# define a sender
class Sender:
    def __init__(self, ws):
        self.ws = ws

    async def send(self, message):
        # message is an object can be jsonified
        await self.ws.send(json.dumps(message))

async def main():
    setHostId('frontend')
    client = Client()

    ws = await websockets.connect('ws://localhost:18081')
    
    # Listen for messages
    async def listen():
        async for data in ws:
            asyncio.ensure_future(getMessageReceiver().onReceiveMessage(json.loads(data), client))
            print(f'收到服务器消息: {data}')

    # Run listener and proceed
    asyncio.create_task(listen())

    client.setSender(Sender(ws))

    main_obj = await client.getObject('greeting')
    result = await main_obj.greeting()
    print(result)

asyncio.run(main())