import {createRpcMain} from 'xuri-rpc-ts-ws'
async function main(){
    const main=await createRpcMain('localhost',18080,'',)
    main.hello(1,2,null)
}
main()