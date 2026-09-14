// Isolated integration acceptance. Never connects to the developer's existing database or Redis.
import {execFileSync,spawn} from 'node:child_process';
import {mkdtempSync,writeFileSync,readFileSync,openSync,closeSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join,resolve} from 'node:path';
import {randomBytes,randomUUID} from 'node:crypto';
import assert from 'node:assert/strict';
const dir=mkdtempSync(join(tmpdir(),'redis-ops-identity-'));
const secret=randomBytes(24).toString('hex'), initial=randomBytes(24).toString('hex'), password=randomBytes(24).toString('hex');
const processes=[];let container;
const run=(bin,args,options={})=>execFileSync(bin,args,{encoding:'utf8',stdio:['pipe','pipe','pipe'],...options});
const wait=ms=>new Promise(r=>setTimeout(r,ms));
async function start(index,port){
  const config=join(dir,`instance-${index}.properties`),log=join(dir,`instance-${index}.log`);
  writeFileSync(config,`server.address=127.0.0.1\nserver.port=0\nspring.datasource.url=jdbc:mysql://127.0.0.1:${port}/identity_test?serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false\nspring.datasource.username=root\nspring.datasource.password=${secret}\nidentity.bootstrap.password=${initial}\nidentity.cookie-secure=false\nredis-ops.credential.keys=test:${randomBytes(32).toString('base64')}\ncollector.enabled=false\nworker.enabled=false\n`,{mode:0o600});
  const fd=openSync(log,'a',0o600),child=spawn('java',['-Xmx256m','-jar',resolve('redis-ops-platform/bootstrap/target/redis-ops-platform-bootstrap-0.1.0-SNAPSHOT.jar'),`--spring.config.additional-location=file:${config}`],{stdio:['ignore',fd,fd]});
  closeSync(fd);processes.push(child);
  for(let n=0;n<120;n++){
    const text=readFileSync(log,'utf8');const match=text.match(/Tomcat started on port (\d+)/);
    if(match&&text.includes('Started PlatformApplication'))return `http://127.0.0.1:${match[1]}`;
    if(child.exitCode!=null)throw Error(`Instance ${index} failed; inspect protected local log ${log}`);
    await wait(1000);
  }
  throw Error(`Instance ${index} startup timeout`);
}
function client(){
  let cookie='';
  const call=async(base,path,method='GET',body,extra={})=>{
    const headers={Cookie:cookie,...extra};
    if(body!==undefined)headers['Content-Type']='application/json';
    if(method!=='GET'){
      const token=await call(base,'/api/v1/auth/csrf');assert.equal(token.status,200);
      headers.Cookie=cookie;headers[token.body.data.headerName]=token.body.data.token;
    }
    const response=await fetch(base+path,{method,headers,body:body===undefined?undefined:JSON.stringify(body)});
    for(const header of response.headers.getSetCookie())if(header.startsWith('REDIS_OPS_SESSION='))cookie=header.split(';')[0];
    return {status:response.status,body:await response.json().catch(()=>({}))};
  };
  return {call};
}
try{
  container=run('docker',['run','-d','--name',`redis-ops-identity-${randomBytes(4).toString('hex')}`,'-e',`MYSQL_ROOT_PASSWORD=${secret}`,'-e','MYSQL_ROOT_HOST=%','-p','127.0.0.1::3306','mysql:8.4']).trim();
  const sql=text=>run('docker',['exec','-i','-e',`MYSQL_PWD=${secret}`,container,'mysql','-uroot','--batch','--skip-column-names'],{input:text});
  for(let i=0;;i++){try{sql('SELECT 1;');break}catch{if(i===90)throw Error('Isolated MySQL startup timeout');await wait(1000)}}
  sql('CREATE DATABASE identity_test CHARACTER SET utf8mb4;');
  const port=run('docker',['port',container,'3306']).trim().split(':').at(-1);
  const a=await start(1,port),b=await start(2,port),admin=client();
  assert.equal((await admin.call(a,'/api/v1/clusters')).status,401);
  const noCsrf=await fetch(a+'/api/v1/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'});assert.equal(noCsrf.status,403);
  let login=await admin.call(a,'/api/v1/auth/login','POST',{username:'admin',password:initial});assert.equal(login.status,200);assert.equal(login.body.data.passwordChangeRequired,true);
  assert.equal((await admin.call(b,'/api/v1/clusters')).status,403);
  assert.equal((await admin.call(b,'/api/v1/auth/password','POST',{oldPassword:initial,newPassword:password},{'If-Match':String(login.body.data.version)})).status,200);
  login=await admin.call(a,'/api/v1/auth/login','POST',{username:'admin',password});assert.equal(login.status,200);
  assert.equal((await admin.call(b,'/api/v1/auth/me')).body.data.id,login.body.data.id);
  assert.equal((await admin.call(b,'/api/v1/clusters')).status,200);
  console.log('PASS: anonymous rejection, CSRF, forced password change, shared JDBC session across two Platform instances');
  const createKey=randomUUID(),temp=randomBytes(24).toString('hex');
  let created=await admin.call(a,'/api/v1/users','POST',{login:'operator',displayName:'Operator',role:'OPERATOR',password:temp},{'Idempotency-Key':createKey});assert.equal(created.status,200);
  const replay=await admin.call(b,'/api/v1/users','POST',{login:'operator',displayName:'Operator',role:'OPERATOR',password:randomBytes(24).toString('hex')},{'Idempotency-Key':createKey});assert.equal(replay.body.data.id,created.body.data.id);
  const operator=client();let op=await operator.call(b,'/api/v1/auth/login','POST',{username:'operator',password:temp});assert.equal(op.status,200);
  const opPassword=randomBytes(24).toString('hex');assert.equal((await operator.call(b,'/api/v1/auth/password','POST',{oldPassword:temp,newPassword:opPassword},{'If-Match':String(op.body.data.version)})).status,200);
  op=await operator.call(a,'/api/v1/auth/login','POST',{username:'operator',password:opPassword});assert.equal(op.status,200);
  assert.equal((await operator.call(b,'/api/v1/users')).status,403);
  const disabled=await admin.call(a,`/api/v1/users/${op.body.data.id}`,'PATCH',{displayName:'Operator',status:'DISABLED',role:'OPERATOR'},{'If-Match':String(op.body.data.version),'Idempotency-Key':randomUUID()});assert.equal(disabled.status,200);
  assert.equal((await operator.call(b,'/api/v1/clusters')).status,401);
  const last=await admin.call(a,`/api/v1/users/${login.body.data.id}`,'PATCH',{displayName:'Admin',status:'DISABLED',role:'ADMIN'},{'If-Match':String(login.body.data.version),'Idempotency-Key':randomUUID()});assert.equal(last.body.code,'LAST_LOCAL_ADMIN');
  console.log('PASS: user creation, secret-free idempotent replay, operator restrictions, cross-instance revocation, last-admin protection');
  assert.equal(sql('SELECT COUNT(*) FROM identity_test.platform_user;').trim(),'2');
  const logout=await admin.call(b,'/api/v1/auth/logout','POST');assert.equal(logout.status,200);assert.equal((await admin.call(a,'/api/v1/auth/me')).status,401);
  console.log('PASS: no repeated bootstrap, logout invalidation');
}catch(error){console.error(error.message);process.exitCode=1}
finally{
  for(const child of processes)child.kill('SIGTERM');
  await wait(1000);
  if(container)run('docker',['rm','-f','-v',container]);
  console.log('Removed only isolated identity test container; local services untouched.');
}
