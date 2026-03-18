// export function getBaseUrl() {
//   return process.env.BASE_URL || 'http://localhost:8080';
// }

export function assertV(condition, message='error'){
    const manual=false;
    if(condition) return;
    if (!manual) {
        console.error(message);
        process.exit(-1);
    }else{
        throw new Error(message);
    }
}