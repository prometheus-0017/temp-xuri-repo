import { getMessageReceiver,Client,type ISender,type Message, MessageReceiver, asProxy, setHostId } from "xuri-rpc";
import { WebSocketServer,WebSocket } from 'ws'
let port=18080
let wss=new WebSocketServer({ port });
export function serve(port:number,mainObject:any,hostId?:string){

    // 设置hostName
       setHostId('backend')
       //创建一个Sender
       class Sender{
        ws:WebSocket
           constructor(ws:WebSocket){
               this.ws=ws
           }
           async send(message:Message){
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