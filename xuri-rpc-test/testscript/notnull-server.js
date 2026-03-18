
import { assertV } from ' ./base/util.js'
import { onServe } from './base/ws-rpc.js'

let mainObject={
        plus(a,b,callback){
          if(callback==null){
              throw new Error('callback is null')
          }else{
              return null;
          }
      }
  }
onServe(mainObject)
