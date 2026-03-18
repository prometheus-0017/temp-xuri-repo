import { getMessageReceiver,Client,type ISender,type Message, MessageReceiver, asProxy, setHostId } from "xuri-rpc";
import ReconnectingWebSocket from "reconnecting-websocket";
export async function createRpcMain(host:string,port:number,path?:string,hostId?:string):any{
    let websocket=new ReconnectingWebSocket(`ws://${host}:${port}${path?path:""}`)
    const client=new Client(hostId);
    client.setSender(new WebSocketSender(websocket))
    const messageReceiver:MessageReceiver =new MessageReceiver(hostId)
    websocket.onmessage=(event)=>{
        let data=event.data;
        let obj=JSON.parse(data);
        messageReceiver.onReceiveMessage(obj,client)
    }
    return await client.getMain()
}
export class WebSocketSender implements ISender{
    ws:ReconnectingWebSocket
    constructor(ws:ReconnectingWebSocket){
        this.ws=ws
    }
    async send(message:Message){
        let connection=this.ws
        connection.send(JSON.stringify(message))
    }
}
export function serve(port:number,object:any,hostId?:string){

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