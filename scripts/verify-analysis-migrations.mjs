// Verifies the shipped V26 baseline can upgrade to V28 in an isolated MySQL container.
import {execFileSync} from 'node:child_process';
import {mkdtempSync,mkdirSync,readFileSync,writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join,resolve} from 'node:path';
import {randomBytes} from 'node:crypto';
const run=mkdtempSync(join(tmpdir(),'redis-ops-analysis-migration-'));
const password=randomBytes(24).toString('hex');
const exec=(bin,args,options={})=>{
  try{return execFileSync(bin,args,{encoding:'utf8',stdio:['pipe','pipe','pipe'],maxBuffer:32*1024*1024,...options});}
  catch(e){throw Error(`${bin} failed: ${String(e.stderr??'').replaceAll(password,'[REDACTED]').slice(0,2000)}`);}
};
const prefix='redis-ops-platform/bootstrap/src/main/resources/db/migration/';
mkdirSync(join(run,'migrations'));
const paths=exec('git',['ls-tree','-r','--name-only','HEAD',prefix]).trim().split('\n')
  .filter(p=>/\/V\d+__.*\.sql$/.test(p)&&Number(p.match(/\/V(\d+)__/)[1])<=26);
for(const p of paths)writeFileSync(join(run,'migrations',p.slice(prefix.length)),exec('git',['show',`HEAD:${p}`]));
for(const name of ['V27__analysis_agent_registry.sql','V28__analysis_runs.sql'])
  writeFileSync(join(run,'migrations',name),readFileSync(prefix+name));
exec('unzip',['-q',resolve('redis-ops-platform/bootstrap/target/redis-ops-platform-bootstrap-0.1.0-SNAPSHOT.jar'),'BOOT-INF/lib/*','-d',run]);
const cp=join(run,'BOOT-INF/lib/*');
exec('javac',['-cp',cp,'-d',run,resolve('scripts/fixtures/BaselineDatabase.java')]);
let container;
try{
 container=exec('docker',['run','-d','--name',`redis-ops-analysis-verify-${randomBytes(5).toString('hex')}`,
 '-e',`MYSQL_ROOT_PASSWORD=${password}`,'-e','MYSQL_ROOT_HOST=%','-p','127.0.0.1::3306','mysql:8.4']).trim();
 const sql=input=>exec('docker',['exec','-i','-e',`MYSQL_PWD=${password}`,container,'mysql','-uroot','-N','-B'],{input});
 for(let n=0;;n++){try{sql('SELECT 1;');break;}catch{if(n>=90)throw Error('MySQL startup timeout');await new Promise(r=>setTimeout(r,1000));}}
 sql(readFileSync('redis-ops-platform/sql/baseline-v26/redis-governance-v26-init.sql','utf8'));
 const port=exec('docker',['port',container,'3306']).trim().split(':').at(-1);
 exec('java',['-cp',`${run}:${cp}`,'BaselineDatabase','upgrade-analysis',join(run,'migrations')],{env:{...process.env,
 BASELINE_DB_PASSWORD:password,BASELINE_JDBC_URL:`jdbc:mysql://127.0.0.1:${port}/redis_governance?serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false`}});
 const tables=sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='redis_governance' AND table_name IN ('analysis_run','analysis_agent_profile');").trim();
 if(tables!=='2')throw Error('Missing analysis tables');
 sql("INSERT INTO redis_governance.analysis_agent_profile(name,protocol,enabled,supported_types_json) VALUES ('migration-check','RULE',false,'[\"SYNC\"]');");
 console.log('PASS: V26 baseline -> V27/V28; 2 analysis tables; seed insert; Flyway validate; repeat migrate=0');
}finally{if(container)exec('docker',['rm','-f','-v',container]);}
