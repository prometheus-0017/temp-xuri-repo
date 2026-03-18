import { PlainProxyManager,RunnableProxyManager,MessageReceiver,Client,asProxy,getMessageReceiver,setHostId, setDebugFlag } from '../../src/index.js'
import {WebSocket} from 'ws'
import { assertV } from './util.js'

import { WebSocketServer } from 'ws'

const port=18081

export const getClient=async ()=>{
    
    //define a sender
    class Sender {
        constructor(ws) {
            this.ws = ws
        }
        async send(message) {
            //message is an object can be jsonified
            this.ws.send(JSON.stringify(message))
        }
    }
    setHostId('frontend')
    let client = new Client()

    const ws = new WebSocket('ws://localhost:' + port);
    await new Promise((resolve, reject) => {
        ws.on('open', resolve);
        ws.on('error', (e) => {
            console.error(e)
            assertV(false, 'connect error')
        });
    });
    ws.on('message', (data) => {
        getMessageReceiver().onReceiveMessage(JSON.parse(data), client)
        console.log(`收到服务器消息: ${data}`);
    })
    client.setSender(new Sender(ws))

    return client
}

export const onServe=(mainObject)=>{
    // 设置hostName
    setHostId('backend')
    //创建一个Sender
    class Sender{
        constructor(ws){
            this.ws=ws
        }
        async send(message){
            this.ws.send(JSON.stringify(message))
        }
    }
    //设置用于提供起始方法的main对象
    getMessageReceiver().setMain(mainObject)
    
    
    const wss = new WebSocketServer({ port: port });
    wss.on('connection', (ws, request) => {
      
      //创建一个client用于发送返回信息
      let client=new Client()
      client.setSender(new Sender(ws))
    
      ws.on('message', (data) => {
    
        //here handle message
        //处理接收到的信息
        getMessageReceiver().onReceiveMessage(JSON.parse(data),client)
    
      });
    
      ws.on('error', (error) => {
        console.error('客户端连接错误:', error);
        assertV(false, 'connect error')
      });
    });
    wss.on('error', (error) => {
      console.error('服务器错误:', error);
      assertV(false, 'connect error')
    });
}