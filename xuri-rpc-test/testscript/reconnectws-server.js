import {serve} from 'xuri-rpc-ts-ws-ser'
let main={
    hello(a,pnmb,c){
        console.log(a,b)
        return a+b
    }
}
console.log('hello')
serve(18080,main,'bk')