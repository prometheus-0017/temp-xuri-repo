import { PlainProxyManager,RunnableProxyManager,MessageReceiver,Client,asProxy,getMessageReceiver,setHostId } from 'xuri-rpc'
import { WebSocketServer } from 'ws'

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
getMessageReceiver().setMain({
    plus(a,b,callback){
        let r=a+b
        callback(r)
        return r
    }
})


const wss = new WebSocketServer({ port: 18081 });
wss.on('listening', () => {
    console.log('ready')
});
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
  });

});

wss.on('error', (error) => {
  console.error('服务器错误:', error);
});