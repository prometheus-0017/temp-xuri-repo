import { PlainProxyManager,RunnableProxyManager,MessageReceiver,Client,asProxy,getMessageReceiver,setHostId, setDebugFlag } from '../../src/index.js'
import {WebSocket} from 'ws'
import { getClient } from './base/ws-rpc.js'
import { assertV } from './base/util.js'
async function main(){
    
    const client=await getClient()

    let main=await client.getMain()
    let result=await main.plus(1,2,asProxy((result)=>console.log('from callback',result)))
    console.log('from rpc',result)
    try{
        result=await main.plus(1,2,null)
        assertV(false,'should not reach here')
    }catch(e){
        console.log('right')
    }
    setDebugFlag(true)
    result=await main.plus(/a(.*?)/g,2,null)
}
main()