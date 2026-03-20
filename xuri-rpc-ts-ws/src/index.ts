import { getMessageReceiver, Client, type ISender, type Message, MessageReceiver, asProxy, setHostId } from "xuri-rpc";
import ReconnectingWebSocket from "reconnecting-websocket";
export async function createRpcMain(host: string, port: number, path?: string, hostId?: string) {
    let websocket = new ReconnectingWebSocket(`ws://${host}:${port}${path ? path : ""}`)
    const client = new Client(hostId);
    client.setSender(new WebSocketSender(websocket))
    const messageReceiver: MessageReceiver = new MessageReceiver(hostId)
    websocket.onmessage = (event) => {
        let data = event.data;
        let obj = JSON.parse(data);
        messageReceiver.onReceiveMessage(obj, client)
    }
    return await client.getMain()
}
export class WebSocketSender implements ISender {
    ws: ReconnectingWebSocket
    constructor(ws: ReconnectingWebSocket) {
        this.ws = ws
    }
    async send(message: Message) {
        let connection = this.ws
        connection.send(JSON.stringify(message))
    }
}