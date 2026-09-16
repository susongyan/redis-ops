// Run from the repository root. Generates the latest snapshot from versioned migration sources, never live data.
import {execFileSync} from 'node:child_process';
import {mkdtempSync, mkdirSync, writeFileSync, readFileSync, readdirSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join, resolve} from 'node:path';
import {randomBytes, createHash} from 'node:crypto';

const root=process.cwd();
const run=mkdtempSync(join(tmpdir(),'redis-ops-ddl-'));
const output=resolve('redis-ops-platform/sql/latest');
const jar=resolve(process.env.BASELINE_PLATFORM_JAR || 'redis-ops-platform/bootstrap/target/redis-ops-platform-bootstrap-0.1.0-SNAPSHOT.jar');
const password=randomBytes(24).toString('hex');
const command=(bin,args,options={})=>{
 try{return execFileSync(bin,args,{encoding:'utf8',stdio:['pipe','pipe','pipe'],maxBuffer:32*1024*1024,...options});}
 catch(error){throw new Error(`${bin} failed: ${String(error.stderr??'').replaceAll(password,'[REDACTED]').slice(0,2000)}`);}
};
const git=(...args)=>command('git',args);
const sourceCommit=git('rev-parse','HEAD').trim();
const prefix='redis-ops-platform/bootstrap/src/main/resources/db/migration/';
const migrations=readdirSync(prefix).filter(p=>/^V\d+__.*\.sql$/.test(p)).map(p=>prefix+p)
  .sort((a,b)=>Number(a.match(/\/V(\d+)__/)[1])-Number(b.match(/\/V(\d+)__/)[1]));
if(!migrations.length)throw Error('No migrations found');
const version=Number(migrations.at(-1).match(/\/V(\d+)__/)[1]);
if(migrations.some((p,i)=>Number(p.match(/\/V(\d+)__/)[1])!==i+1))throw Error('Migration versions must be unique and contiguous');
const committed=git('ls-tree','-r','--name-only','HEAD',prefix).trim().split('\n').filter(p=>/\/V\d+__.*\.sql$/.test(p));
for(const p of committed)if(!readFileSync(p).equals(Buffer.from(git('show',`HEAD:${p}`))))throw Error(`Committed migration changed: ${p}`);
const migrationSources=new Map(migrations.map(p=>[p,readFileSync(p)]));
const sourceIncludesUncommittedMigrations=migrations.some(p=>!committed.includes(p));
mkdirSync(join(run,'migrations'));
for(const p of migrations)writeFileSync(join(run,'migrations',p.slice(prefix.length)),migrationSources.get(p));
command('unzip',['-q',jar,'BOOT-INF/lib/*','-d',run]);
const classpath=join(run,'BOOT-INF/lib/*');
command('javac',['-cp',classpath,'-d',run,resolve('scripts/fixtures/BaselineDatabase.java')]);
let container;
try {
 container=command('docker',['run','-d','--name',`redis-ops-ddl-${randomBytes(5).toString('hex')}`,
  '-e',`MYSQL_ROOT_PASSWORD=${password}`,'-e','MYSQL_ROOT_HOST=%',
  '-p','127.0.0.1::3306','mysql:8.4']).trim();
 const docker=(args,options={})=>command('docker',['exec','-e',`MYSQL_PWD=${password}`,container,...args],options);
 const mysql=(sql)=>command('docker',['exec','-i','-e',`MYSQL_PWD=${password}`,container,'mysql','-uroot','--batch','--skip-column-names'],{input:sql});
 for(let n=0;;n++){
  try {mysql('SELECT 1;');break;}catch{if(n>=90)throw Error('Temporary MySQL not ready');await new Promise(r=>setTimeout(r,1000));}
 }
 const port=command('docker',['port',container,'3306']).trim().split(':').at(-1);
 const flyway=(db,operation)=>command('java',['-cp',`${run}:${classpath}`,'BaselineDatabase',operation,join(run,'migrations'),String(version)],
  {env:{...process.env,BASELINE_DB_PASSWORD:password,BASELINE_JDBC_URL:`jdbc:mysql://127.0.0.1:${port}/${db}?serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false`}});
 mysql('CREATE DATABASE reference CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; CREATE DATABASE restored CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;');
 flyway('reference','migrate');
 console.log(`V1–V${version} migration chain applied in isolated MySQL`);
 const dump=(db,flags,tables=[])=>docker(['mysqldump','-uroot','--skip-comments','--skip-add-drop-table','--skip-add-locks',
  '--skip-lock-tables','--no-tablespaces','--set-gtid-purged=OFF','--hex-blob',...flags,db,...tables]).trimEnd()+'\n';
 const tables=mysql('SHOW TABLES FROM reference;').trim().split('\n').filter(t=>t!=='flyway_schema_history');
 const seeded=tables.filter(t=>Number(mysql(`SELECT COUNT(*) FROM reference.\`${t}\`;`).trim())>0);
 const ddl=dump('reference',['--no-data'],tables);
 const seed=seeded.length?dump('reference',['--no-create-info','--complete-insert','--order-by-primary'],seeded):'-- No seed rows\n';
 mysql('USE restored;\n'+ddl+'\n'+seed);
 flyway('restored','baseline');
 flyway('restored','verify');
 const history=dump('restored',[],['flyway_schema_history']);
 const combined=`-- Redis Ops V${version} fresh database initialization; base commit ${sourceCommit}\n-- Exact migration sources and hashes: manifest.json (may include uncommitted additions).\n-- MySQL 8.x. EMPTY ENVIRONMENT ONLY. Do not use mysql --force.\n-- Contains real Flyway BASELINE version ${version}, not fabricated migration checksums.\nCREATE DATABASE redis_governance CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;\nUSE redis_governance;\nSET time_zone = '+00:00';\n\n${ddl}\n${seed}\n${history}`;
 // Exercise the exact delivered script in another fresh database, including CREATE DATABASE and USE.
 mysql(combined);
 flyway('redis_governance','verify');
 // MySQL may render an inherited charset explicitly after a dump/import.
 // Keep COLLATE intact and separately compare effective column metadata below.
 const normalize=s=>s.replace(/ CHARACTER SET utf8mb4(?= COLLATE utf8mb4_)/g,'');
 const restoredDdl=dump('redis_governance',['--no-data'],tables);
 if(normalize(restoredDdl)!==normalize(ddl)){
  writeFileSync(join(run,'expected.sql'),ddl);
  writeFileSync(join(run,'actual.sql'),restoredDdl);
  throw Error(`DDL roundtrip mismatch; compare ${run}/expected.sql and actual.sql`);
 }
 const columns=db=>mysql(`SELECT TABLE_NAME,COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT,EXTRA,CHARACTER_SET_NAME,COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='${db}' AND TABLE_NAME<>'flyway_schema_history' ORDER BY TABLE_NAME,ORDINAL_POSITION;`);
 if(columns('reference')!==columns('redis_governance'))throw Error('Column metadata roundtrip mismatch');
 for(const t of tables){
  const expected=dump('reference',['--no-create-info','--complete-insert','--order-by-primary'],[t]);
  const actual=dump('redis_governance',['--no-create-info','--complete-insert','--order-by-primary'],[t]);
  if(expected!==actual)throw Error(`Data roundtrip mismatch: ${t}`);
 }
 mkdirSync(output,{recursive:true});
 // Check actor writes/reads on the disposable restored schema, never on the snapshot source.
 command('javac',['-cp',classpath,'-d',run,resolve('scripts/fixtures/ActorSnapshotDatabase.java')]);
 command('java',['-cp',`${run}:${classpath}`,'ActorSnapshotDatabase'],{env:{...process.env,BASELINE_DB_PASSWORD:password,BASELINE_JDBC_URL:`jdbc:mysql://127.0.0.1:${port}/restored?serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false`}});
 for(const p of migrations)if(!readFileSync(p).equals(migrationSources.get(p)))throw Error(`Migration changed during generation: ${p}`);
 writeFileSync(join(output,'redis-governance-init.sql'),combined);
 writeFileSync(join(output,'schema.sql'),ddl);
 writeFileSync(join(output,'seed.sql'),seed);
 writeFileSync(join(output,'flyway-baseline.sql'),history);
 const manifest={sourceCommit,sourceIncludesUncommittedMigrations,mysqlVersion:mysql('SELECT VERSION();').trim(),baselineVersion:version,
  businessTables:tables.length,seedRows:Object.fromEntries(seeded.map(t=>[t,Number(mysql(`SELECT COUNT(*) FROM reference.\`${t}\`;`).trim())])),
  tests:[`${migrations.length} migrations applied`,'combined SQL imported into fresh database','all business table DDL and effective column metadata compared','all business table data compared','Flyway validate passed; migrate executed 0 migrations'],
  migrations:migrations.map(p=>({path:p,sha256:createHash('sha256').update(migrationSources.get(p)).digest('hex')})),
  files:Object.fromEntries(['redis-governance-init.sql','schema.sql','seed.sql','flyway-baseline.sql'].map(f=>[f,createHash('sha256').update(readFileSync(join(output,f))).digest('hex')]))};
 writeFileSync(join(output,'manifest.json'),JSON.stringify(manifest,null,2)+'\n');
 console.log(`Validated ${tables.length} business tables; seed tables: ${seeded.join(', ')}; output: ${output}`);
} finally {
 if(container)command('docker',['rm','-f','-v',container]);
}
